package com.mtbanalyzer.viewer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Lets ExoPlayer play a clip straight off the recorder's Viewer Link server.
 *
 * media3's own DefaultHttpDataSource sits on HttpURLConnection, which the network security
 * policy stops from making cleartext requests to anything but the hosts a config names --
 * and a hotspot gateway's address cannot be named in advance (see [ViewerHttpClient]).
 * This goes over a raw socket instead, so the shipped app keeps the strict default.
 *
 * Seeking is a new [open] at a new position, i.e. one Range request per seek; the server's
 * Range support ([HttpRange]) is what makes that cheap.
 */
@OptIn(markerClass = [UnstableApi::class])
class RemoteClipDataSource(
    private val client: ViewerHttpClient
) : BaseDataSource(/* isNetwork= */ true) {

    private var dataSpec: DataSpec? = null
    private var response: RemoteResponse? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)

        val remote = try {
            client.get(dataSpec.uri.toString(), dataSpec.position, dataSpec.length)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        response = remote

        try {
            when (remote.status) {
                206 -> {
                    // A 206 that starts somewhere else would silently play the wrong bytes.
                    val start = ContentRange.start(remote.header("content-range"))
                    if (start != dataSpec.position) {
                        throw DataSourceException(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
                    }
                    bytesRemaining = remote.contentLength
                }
                200 -> {
                    // Server ignored the Range (RFC 7233 lets it); walk to the position.
                    skipFully(remote, dataSpec.position)
                    bytesRemaining = if (remote.contentLength >= 0) {
                        remote.contentLength - dataSpec.position
                    } else {
                        C.LENGTH_UNSET.toLong()
                    }
                }
                416 -> throw DataSourceException(
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                )
                else -> throw DataSourceException(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
            }
        } catch (e: IOException) {
            closeResponse()
            throw if (e is DataSourceException) e else DataSourceException(
                e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            )
        }

        // A bounded DataSpec wins even if the server said more.
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            bytesRemaining = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                minOf(bytesRemaining, dataSpec.length)
            }
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val body = response?.body ?: return C.RESULT_END_OF_INPUT

        val wanted = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        val read = try {
            body.read(buffer, offset, wanted)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        if (read == -1) {
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                // The connection dropped before the promised length arrived.
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            }
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        closeResponse()
        dataSpec = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun closeResponse() {
        response?.close()
        response = null
    }

    private fun skipFully(remote: RemoteResponse, count: Long) {
        var left = count
        val scratch = ByteArray(16 * 1024)
        while (left > 0) {
            val n = remote.body.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (n == -1) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            left -= n
        }
    }

    /** [listener] is for tests and diagnostics: it sees every open, i.e. every seek. */
    @OptIn(markerClass = [UnstableApi::class])
    class Factory(
        private val client: ViewerHttpClient = ViewerHttpClient(),
        private val listener: TransferListener? = null
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            RemoteClipDataSource(client).also { source ->
                listener?.let { source.addTransferListener(it) }
            }
    }
}
