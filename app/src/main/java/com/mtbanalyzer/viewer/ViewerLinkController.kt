package com.mtbanalyzer.viewer

import android.content.Context
import android.util.Base64
import android.util.Log
import com.mtbanalyzer.clips.LocalClipSource
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the running server and the one URL the QR code encodes.
 *
 * A singleton because both the settings screen and the foreground service need to see the
 * same state, and binding a service for a URL string is more machinery than this warrants.
 */
object ViewerLinkController {

    private const val TAG = "ViewerLinkController"
    private const val PREFERRED_PORT = 8080

    data class Status(
        val running: Boolean,
        val url: String? = null,
        val address: String? = null,
        val port: Int = 0,
        val error: String? = null
    )

    private val listeners = CopyOnWriteArrayList<(Status) -> Unit>()
    private var server: ViewerLinkServer? = null

    @Volatile
    private var status = Status(running = false)

    fun status(): Status = status

    fun addListener(listener: (Status) -> Unit) {
        listeners.add(listener)
        listener(status)
    }

    fun removeListener(listener: (Status) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    fun start(context: Context): Status {
        if (status.running) return status

        val app = context.applicationContext
        val address = NetworkAddress.best()
            ?: return publish(Status(false, error = "No Wi-Fi network. Turn on the hotspot first."))

        val host = address.hostAddress
            ?: return publish(Status(false, error = "Could not read this phone's address"))

        val token = newToken()
        val routes = ViewerLinkRoutes(
            context = app,
            clips = LocalClipSource(app),
            thumbnails = ClipThumbnails(app),
            token = token,
            allowedHosts = setOf(host, "localhost", "127.0.0.1")
        )

        return try {
            val instance = ViewerLinkServer(routes)
            val port = instance.start(address, PREFERRED_PORT)
            server = instance
            publish(
                Status(
                    running = true,
                    url = "http://$host:$port/?${ViewerLinkRoutes.TOKEN_PARAM}=$token",
                    address = host,
                    port = port
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not start viewer link", e)
            publish(Status(false, error = e.message ?: "Could not start the server"))
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        publish(Status(running = false))
    }

    /** 128 bits, new on every start, so a link never outlives the session that made it. */
    private fun newToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun publish(next: Status): Status {
        status = next
        for (listener in listeners) {
            try {
                listener(next)
            } catch (e: Exception) {
                Log.w(TAG, "Status listener failed", e)
            }
        }
        return next
    }
}
