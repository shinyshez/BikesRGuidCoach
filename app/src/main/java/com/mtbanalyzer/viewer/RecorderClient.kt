package com.mtbanalyzer.viewer

import com.mtbanalyzer.clips.ClipInfo
import com.mtbanalyzer.clips.ClipRef
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

/**
 * Where a recorder is, as its QR code says: `http://<host>:<port>/?t=<token>`. The same code
 * the browser viewer scans, so the recorder's screen does not change for the app.
 */
data class RecorderAddress(val host: String, val port: Int, val token: String) {

    companion object {
        /** Null for anything that is not a Viewer Link URL; the reason is not worth showing. */
        fun parse(text: String): RecorderAddress? {
            val uri = try {
                URI(text.trim())
            } catch (e: URISyntaxException) {
                return null
            }
            if (!"http".equals(uri.scheme, ignoreCase = true)) return null
            val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
            val port = if (uri.port == -1) 80 else uri.port
            if (port !in 1..65535) return null
            val token = HttpParser.parseQuery(uri.rawQuery ?: "")[ViewerLinkRoutes.TOKEN_PARAM]
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            return RecorderAddress(host, port, token)
        }
    }

    /**
     * An API URL. The token goes in the query, which the server accepts in place of the
     * browser's cookie; [withToken] false is for `/api/health`, which needs none.
     */
    fun url(path: String, query: Map<String, String> = emptyMap(), withToken: Boolean = true): String {
        val params = if (withToken) mapOf(ViewerLinkRoutes.TOKEN_PARAM to token) + query else query
        return buildString {
            append("http://").append(host).append(':').append(port).append(path)
            params.entries.forEachIndexed { index, (name, value) ->
                append(if (index == 0) '?' else '&').append(encode(name)).append('=').append(encode(value))
            }
        }
    }

    fun clipUrl(id: Long) = url("/api/clips/$id")
    fun thumbUrl(id: Long) = url("/api/clips/$id/thumb")

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}

/** What `/api/health` says about a recorder. */
data class RecorderHealth(
    val device: String,
    val clipCount: Long,
    val apiVersion: Long,
    /** Stable across Viewer Link restarts; null from a recorder that predates Phase 2. */
    val recorderId: String?
)

/** One of the recorder's clips, as the viewer knows it. */
data class RemoteClip(val recorderId: String, val info: ClipInfo) {
    val ref: ClipRef.Remote get() = ClipRef.Remote(recorderId, info.id)
}

/** A recorder the viewer has paired with, for as long as this process lives. */
data class Recorder(val address: RecorderAddress, val health: RecorderHealth) {
    val id: String get() = health.recorderId ?: throw IllegalStateException("Unpaired recorder")
    val device: String get() = health.device
}

/**
 * The viewer's side of the API, over [ViewerHttpClient] rather than a platform HTTP stack
 * (see that class for why). Blocking: call it off the main thread.
 */
class RecorderClient(private val http: ViewerHttpClient = ViewerHttpClient()) {

    companion object {
        const val SUPPORTED_API_VERSION = 1L
        private const val MAX_JSON_BYTES = 4 * 1024 * 1024
    }

    /**
     * Checks the address answers as a Viewer Link recorder and that the token is accepted,
     * then returns the recorder to pair with. Health is unauthenticated, so the token is
     * proven with a clip list request too — otherwise a stale code would pair and then show
     * an empty, broken-looking tab.
     */
    @Throws(IOException::class)
    fun pair(address: RecorderAddress): Recorder {
        val health = health(address)
        if (health.apiVersion != SUPPORTED_API_VERSION) {
            throw RecorderException("The recorder runs a different version. Update both phones.")
        }
        val id = health.recorderId ?: throw RecorderException("The recorder's app is too old. Update it.")
        // It names a cache directory and goes into ClipRef keys, and it came off the network.
        if (!isValidRecorderId(id)) throw RecorderException("The recorder sent a malformed id")
        val recorder = Recorder(address, health)
        clips(recorder)
        return recorder
    }

    @Throws(IOException::class)
    fun health(address: RecorderAddress): RecorderHealth {
        val json = getJson(address.url("/api/health", withToken = false))
        return RecorderHealth(
            device = json.optString("device") ?: "Recorder",
            clipCount = (json["clips"] as? Number)?.toLong() ?: 0L,
            apiVersion = json.long("apiVersion"),
            recorderId = json.optString("recorderId")
        )
    }

    /** Newest first, as the server orders them. */
    @Throws(IOException::class)
    fun clips(recorder: Recorder, sinceEpochSeconds: Long? = null): List<RemoteClip> {
        val query = sinceEpochSeconds?.let { mapOf("since" to it.toString()) } ?: emptyMap()
        return parseClips(recorder.id, getJson(recorder.address.url("/api/clips", query)))
    }

    private fun getJson(url: String): Map<String, Any?> {
        http.get(url).use { response ->
            when (response.status) {
                200 -> Unit
                401 -> throw UnauthorizedException()
                else -> throw IOException("Recorder answered HTTP ${response.status}")
            }
            if (response.contentLength > MAX_JSON_BYTES) throw IOException("Response too large")
            val bytes = response.body.readBytes()
            return JsonReader.parseObject(bytes.toString(Charsets.UTF_8))
        }
    }

    /** A failure whose message is written for the person holding the phone. */
    open class RecorderException(message: String) : IOException(message)

    /** The token was refused: Viewer Link was restarted on the recorder, so scan again. */
    class UnauthorizedException :
        RecorderException("Viewer Link was restarted on the recorder. Scan its code again.")
}

/** What the recorder generates (a UUID) and all the viewer will accept. */
internal fun isValidRecorderId(id: String): Boolean =
    id.length in 1..64 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }

/** Pure, so the wire shape is pinned by a unit test against [toJson]'s output. */
@Throws(IOException::class)
internal fun parseClips(recorderId: String, json: Map<String, Any?>): List<RemoteClip> {
    val list = json["clips"] as? List<*> ?: throw IOException("Missing 'clips'")
    return list.map { item ->
        @Suppress("UNCHECKED_CAST")
        val clip = item as? Map<String, Any?> ?: throw IOException("Bad clip entry")
        RemoteClip(
            recorderId,
            ClipInfo(
                id = clip.long("id"),
                name = clip.string("name"),
                dateAdded = clip.long("dateAdded"),
                durationMs = clip.long("durationMs"),
                sizeBytes = clip.long("sizeBytes"),
                kind = clip.string("kind")
            )
        )
    }
}
