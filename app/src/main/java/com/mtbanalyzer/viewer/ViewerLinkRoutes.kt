package com.mtbanalyzer.viewer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The read-only viewer API. Five endpoints, no writes — deleting and importing stay on the
 * recorder, which keeps the whole surface safe to expose on a shared hotspot.
 */
class ViewerLinkRoutes(
    private val context: Context,
    private val catalog: ClipCatalog,
    private val thumbnails: ClipThumbnails,
    private val token: String,
    private val allowedHosts: Set<String>
) {

    companion object {
        private const val TAG = "ViewerLinkRoutes"
        const val COOKIE_NAME = "mtbvl"
        const val TOKEN_PARAM = "t"
        private const val API_VERSION = 1
        private const val PAGE_ASSET = "viewer/index.html"
        private const val COPY_BUFFER = 64 * 1024
    }

    private val metaCache = ConcurrentHashMap<Long, String>()

    fun handle(request: HttpRequest): HttpResponse {
        if (request.method != "GET" && request.method != "HEAD") {
            return HttpResponse.error(405, "Only GET and HEAD are supported")
        }
        if (!hostAllowed(request)) {
            return HttpResponse.error(403, "Unexpected Host header")
        }

        val path = request.path
        return when {
            path == "/" || path == "/index.html" -> page(request)
            path == "/favicon.ico" -> HttpResponse.empty(404)
            path == "/api/health" -> health()
            path == "/api/clips" -> authorized(request) { clips(request) }
            path.startsWith("/api/clips/") -> authorized(request) { clipRoute(request, path) }
            else -> HttpResponse.error(404, "Not found")
        }
    }

    // --- routes ---------------------------------------------------------------------

    /**
     * The QR points here with the token in the query. Setting it as a cookie and
     * redirecting keeps the token out of the address bar, out of screenshots, and out of
     * any link the viewer might share.
     *
     * SameSite must stay Lax, never Strict. A QR scanner opening the link is a navigation
     * initiated by another app, which browsers treat as cross-site, and a Strict cookie is
     * withheld on the redirect that follows -- so the viewer lands back on "/" with no
     * query string and gets a 401. Pasting the same URL into the address bar works,
     * because that counts as browser-initiated and therefore same-site, which is why this
     * only ever showed up on a real phone. Lax still withholds the cookie from cross-site
     * POSTs, subresource loads and iframes; the server is read-only anyway.
     */
    private fun page(request: HttpRequest): HttpResponse {
        if (tokenMatches(request.query[TOKEN_PARAM])) {
            return HttpResponse.empty(
                302,
                mapOf(
                    "Location" to "/",
                    "Set-Cookie" to "$COOKIE_NAME=$token; Path=/; HttpOnly; SameSite=Lax",
                    "Cache-Control" to "no-store"
                )
            )
        }
        if (!tokenMatches(request.cookie(COOKIE_NAME))) {
            return HttpResponse.html(401, UNAUTHORIZED_PAGE)
        }
        val asset = try {
            context.assets.open(PAGE_ASSET).use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "Viewer page asset missing", e)
            return HttpResponse.error(500, "Viewer page unavailable")
        }
        return HttpResponse.bytes(
            200, "text/html; charset=utf-8", asset, mapOf("Cache-Control" to "no-cache")
        )
    }

    /** Unauthenticated on purpose: the viewer page needs it to tell "wrong network" from
     *  "wrong token". It reveals a device model and a count, nothing else. */
    private fun health(): HttpResponse {
        val payload = buildString {
            append("{\"ok\":true,\"device\":").append(Json.string(Build.MODEL ?: "Android"))
            append(",\"clips\":").append(catalog.list().size)
            append(",\"apiVersion\":").append(API_VERSION).append('}')
        }
        return HttpResponse.json(200, payload, mapOf("Cache-Control" to "no-store"))
    }

    private fun clips(request: HttpRequest): HttpResponse {
        val since = request.query["since"]?.toLongOrNull()
        return HttpResponse.json(
            200, catalog.listJson(since), mapOf("Cache-Control" to "no-store")
        )
    }

    private fun clipRoute(request: HttpRequest, path: String): HttpResponse {
        val rest = path.removePrefix("/api/clips/")
        val segments = rest.split('/')
        val id = segments.firstOrNull()?.toLongOrNull()
            ?: return HttpResponse.error(404, "Unknown clip")

        return when {
            segments.size == 1 -> media(request, id)
            segments.size == 2 && segments[1] == "thumb" -> thumb(id)
            segments.size == 2 && segments[1] == "meta" -> meta(id)
            else -> HttpResponse.error(404, "Not found")
        }
    }

    private fun thumb(id: Long): HttpResponse {
        val bytes = thumbnails.jpeg(id) ?: return HttpResponse.error(404, "No thumbnail")
        return HttpResponse.bytes(
            200, "image/jpeg", bytes, mapOf("Cache-Control" to "private, max-age=3600")
        )
    }

    private fun meta(id: Long): HttpResponse {
        metaCache[id]?.let { return HttpResponse.json(200, it) }
        val clip = catalog.find(id) ?: return HttpResponse.error(404, "Unknown clip")

        var frameRate = 0.0
        var frameCount = 0L
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, catalog.uriFor(id))
            frameRate = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toDoubleOrNull() ?: 0.0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                frameCount = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    ?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read metadata for $id", e)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Retriever release failed", e)
            }
        }

        // Phase 2 needs these because MediaMetadataRetriever cannot open an http:// URL
        // through the Context overload VideoPlayerView uses.
        val payload = format(
            "{\"id\":%d,\"durationMs\":%d,\"frameRate\":%s,\"frameCount\":%d}",
            id, clip.durationMs, formatDouble(frameRate), frameCount
        )
        metaCache[id] = payload
        return HttpResponse.json(200, payload)
    }

    /**
     * The clip bytes. Range support is what makes scrubbing usable: without it a seek
     * re-downloads the whole file, and Phase 2's ExoPlayer would refuse to seek at all.
     */
    private fun media(request: HttpRequest, id: Long): HttpResponse {
        catalog.find(id) ?: return HttpResponse.error(404, "Unknown clip")
        val total = fileLength(id) ?: return HttpResponse.error(404, "Clip unavailable")

        val common = linkedMapOf(
            "Accept-Ranges" to "bytes",
            // Clip bytes never change for a given id.
            "Cache-Control" to "private, max-age=3600"
        )

        return when (val parsed = HttpRange.parse(request.header("range"), total)) {
            is RangeResult.Unsatisfiable ->
                HttpResponse.json(
                    416,
                    Json.error("Range not satisfiable"),
                    common + mapOf("Content-Range" to "bytes */$total")
                )

            is RangeResult.Satisfiable -> {
                val range = parsed.range
                HttpResponse.stream(
                    206,
                    "video/mp4",
                    range.length,
                    common + mapOf(
                        "Content-Range" to "bytes ${range.start}-${range.endInclusive}/$total"
                    )
                ) { out -> stream(id, range.start, range.length, out) }
            }

            else -> HttpResponse.stream(200, "video/mp4", total, common) { out ->
                stream(id, 0L, total, out)
            }
        }
    }

    // --- helpers --------------------------------------------------------------------

    private fun authorized(request: HttpRequest, block: () -> HttpResponse): HttpResponse {
        val supplied = request.query[TOKEN_PARAM] ?: request.cookie(COOKIE_NAME)
        return if (tokenMatches(supplied)) block() else HttpResponse.error(401, "Unauthorized")
    }

    private fun tokenMatches(supplied: String?): Boolean {
        if (supplied == null) return false
        return MessageDigest.isEqual(
            supplied.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8)
        )
    }

    /** Cheap DNS-rebinding guard: only the address the QR advertised may be used. */
    private fun hostAllowed(request: HttpRequest): Boolean {
        val host = request.hostName() ?: return true // HTTP/1.0 clients send no Host
        return allowedHosts.contains(host.lowercase(Locale.US))
    }

    private fun fileLength(id: Long): Long? =
        try {
            context.contentResolver.openFileDescriptor(catalog.uriFor(id), "r")?.use { pfd ->
                val size = pfd.statSize
                if (size >= 0) size else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not size clip $id", e)
            null
        }

    private fun stream(id: Long, start: Long, length: Long, out: OutputStream) {
        context.contentResolver.openFileDescriptor(catalog.uriFor(id), "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                if (start > 0) input.channel.position(start)
                copy(input, out, length)
            }
        }
    }

    private fun copy(input: InputStream, out: OutputStream, length: Long) {
        val buffer = ByteArray(COPY_BUFFER)
        var remaining = length
        while (remaining > 0) {
            val wanted = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        out.flush()
    }

    private fun format(pattern: String, vararg args: Any): String =
        String.format(Locale.US, pattern, *args)

    private fun formatDouble(value: Double): String =
        if (value <= 0.0) "0" else String.format(Locale.US, "%.3f", value)
}

private val UNAUTHORIZED_PAGE = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Viewer Link</title>
<style>body{background:#111;color:#eee;font:16px/1.5 -apple-system,system-ui,sans-serif;
margin:0;display:flex;min-height:100vh;align-items:center;justify-content:center;padding:24px}
div{max-width:22rem;text-align:center}h1{font-size:1.1rem;margin:0 0 .5rem}
p{color:#9a9a9a;margin:0}</style></head>
<body><div><h1>Scan the code again</h1>
<p>This link needs the code shown on the recording phone. Open Settings &rsaquo; Viewer Link
there and scan it with your camera.</p></div></body></html>
""".trimIndent()
