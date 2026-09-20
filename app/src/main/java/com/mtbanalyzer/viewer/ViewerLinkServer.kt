package com.mtbanalyzer.viewer

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * A small HTTP/1.1 server for the viewer API.
 *
 * Hand-rolled rather than pulled in: the surface is five read-only endpoints, and the one
 * genuinely fiddly part (Range) has to be exactly right either way. If Phase 3 adds writes,
 * this is the point to reconsider and move to a real server library.
 */
class ViewerLinkServer(private val routes: ViewerLinkRoutes) {

    companion object {
        private const val TAG = "ViewerLinkServer"
        private const val WORKERS = 4
        private const val IDLE_TIMEOUT_MS = 15_000
        private const val BACKLOG = 16
        private const val PORT_ATTEMPTS = 10
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: ExecutorService? = null

    /** Binds and starts accepting. Returns the port actually bound. */
    fun start(address: InetAddress, preferredPort: Int): Int {
        check(!running) { "Server already running" }

        val socket = bind(address, preferredPort)
        serverSocket = socket
        workers = Executors.newFixedThreadPool(WORKERS)
        running = true

        acceptThread = Thread({ acceptLoop(socket) }, "viewer-link-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Viewer link listening on ${address.hostAddress}:${socket.localPort}")
        return socket.localPort
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing server socket", e)
        }
        serverSocket = null
        workers?.shutdownNow()
        workers = null
        acceptThread?.join(1_000)
        acceptThread = null
        Log.i(TAG, "Viewer link stopped")
    }

    private fun bind(address: InetAddress, preferredPort: Int): ServerSocket {
        for (offset in 0 until PORT_ATTEMPTS) {
            try {
                return ServerSocket(preferredPort + offset, BACKLOG, address)
            } catch (e: BindException) {
                Log.d(TAG, "Port ${preferredPort + offset} taken, trying the next one")
            }
        }
        // Everything in the preferred window is busy; take whatever the OS offers.
        return ServerSocket(0, BACKLOG, address)
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "Accept failed", e)
                break
            }
            try {
                workers?.execute { serve(client) } ?: closeQuietly(client)
            } catch (e: RejectedExecutionException) {
                closeQuietly(client)
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = IDLE_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), 8 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

            while (running && !socket.isClosed) {
                val request = HttpParser.readRequest(input) ?: break
                val keepAlive = wantsKeepAlive(request)

                val response = try {
                    routes.handle(request)
                } catch (e: Exception) {
                    Log.e(TAG, "Handler failed for ${request.target}", e)
                    HttpResponse.error(500, "Server error")
                }

                HttpWriter.write(
                    output,
                    response,
                    includeBody = request.method != "HEAD",
                    keepAlive = keepAlive
                )
                if (!keepAlive) break
            }
        } catch (e: MalformedRequestException) {
            Log.d(TAG, "Malformed request: ${e.message}")
        } catch (e: SocketTimeoutException) {
            // Idle keep-alive connection; closing it is the expected outcome.
        } catch (e: IOException) {
            // A browser abandoning a range request mid-seek lands here. Normal.
            Log.d(TAG, "Connection closed: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error serving connection", e)
        } finally {
            closeQuietly(socket)
        }
    }

    private fun wantsKeepAlive(request: HttpRequest): Boolean =
        !request.header("connection").equals("close", ignoreCase = true)

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: IOException) {
            Log.d(TAG, "Error closing client socket", e)
        }
    }
}
