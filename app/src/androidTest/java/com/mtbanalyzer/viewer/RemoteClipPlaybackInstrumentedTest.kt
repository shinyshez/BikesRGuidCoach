package com.mtbanalyzer.viewer

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.security.NetworkSecurityPolicy
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtbanalyzer.VideoImporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Phase 2 cleartext spike, as a test: can the app itself play a clip off the Viewer
 * Link server, seeking included, without loosening the network security policy?
 *
 * Everything runs against the device's own *non-loopback* address. The debug config only
 * exempts 127.0.0.1/localhost, so on this address the app is under exactly the policy a
 * release build has everywhere -- which is the situation a real viewer is in when it
 * talks to a hotspot gateway. Each positive test has a negative control proving the
 * policy really is in force on that address.
 */
@OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class RemoteClipPlaybackInstrumentedTest {

    private companion object {
        const val TOKEN = "spike-token"
        const val TIMEOUT_S = 20L
    }

    private lateinit var context: Context
    private lateinit var server: ViewerLinkServer
    private lateinit var address: InetAddress
    private var port = 0
    private var clipUri: Uri? = null
    private var clipId = -1L
    private var player: ExoPlayer? = null

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()

        // Not assumeTrue: a skipped proof proves nothing, so a device with no usable
        // interface should fail and say so.
        address = NetworkAddress.best()
            ?: throw AssertionError("No non-loopback IPv4 interface; this test needs one")

        val source = File(context.cacheDir, "remote_playback_source.mp4")
        InstrumentationRegistry.getInstrumentation().context.resources
            .openRawResource(com.mtbanalyzer.test.R.raw.test_clip)
            .use { input -> source.outputStream().use { input.copyTo(it) } }
        clipUri = VideoImporter(context).import(Uri.fromFile(source))
        assertNotNull("test clip should import", clipUri)
        clipId = ContentUris.parseId(clipUri!!)

        val routes = ViewerLinkRoutes(
            context = context,
            catalog = ClipCatalog(context),
            thumbnails = ClipThumbnails(context),
            token = TOKEN,
            allowedHosts = setOf(address.hostAddress!!)
        )
        server = ViewerLinkServer(routes)
        port = server.start(address, 0)
    }

    @After
    fun tearDown() {
        player?.let { p -> onMain { p.release() } }
        if (::server.isInitialized) server.stop()
        clipUri?.let { context.contentResolver.delete(it, null, null) }
    }

    // --- the premise ----------------------------------------------------------------

    @Test
    fun policy_refusesCleartextToThisAddress() {
        assertFalse(
            "debug config should not exempt ${address.hostAddress}",
            NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(address.hostAddress!!)
        )
        try {
            (URL("http://${address.hostAddress}:$port/api/health").openConnection() as HttpURLConnection)
                .apply { connectTimeout = 5_000; readTimeout = 5_000 }
                .responseCode
            fail("HttpURLConnection should be refused by the cleartext policy")
        } catch (e: IOException) {
            // UnknownServiceException: "CLEARTEXT communication to ... not permitted"
            assertTrue(e.toString(), e.message.orEmpty().contains("not permitted"))
        }
    }

    // --- the raw socket path --------------------------------------------------------

    @Test
    fun rawSocketClient_readsARangeOverTheSameAddress() {
        val local = localBytes()
        ViewerHttpClient().get(clipUrl(), position = 100, length = 200).use { response ->
            assertEquals(206, response.status)
            assertEquals(local.size.toLong(), response.totalLength)
            assertArrayEquals(local.copyOfRange(100, 300), response.body.readBytes())
        }
    }

    @Test
    fun dataSource_opensAtAPositionLikeASeekDoes() {
        val local = localBytes()
        val start = local.size / 2

        val source = RemoteClipDataSource(ViewerHttpClient())
        try {
            val length = source.open(
                DataSpec.Builder().setUri(Uri.parse(clipUrl())).setPosition(start.toLong()).build()
            )
            assertEquals((local.size - start).toLong(), length)
            assertArrayEquals(local.copyOfRange(start, local.size), readAll(source))
        } finally {
            source.close()
        }
    }

    // --- end to end through ExoPlayer ------------------------------------------------

    /**
     * The test clip keeps its `moov` box at the end of the file (as recordings often do),
     * so ExoPlayer cannot even prepare without a Range request into the tail. Reaching
     * READY therefore already proves ranged reads; the explicit seek then proves the
     * player comes back to READY at the new position without an error.
     */
    @Test
    fun exoPlayer_preparesAndSeeksThroughTheRawSocketDataSource() {
        val opens = Collections.synchronizedList(mutableListOf<Long>())
        val listener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                opens.add(dataSpec.position)
            }
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytes: Int) {}
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        }
        val watcher = PlayerWatcher()
        buildPlayer(RemoteClipDataSource.Factory(ViewerHttpClient(), listener), watcher)

        watcher.ready.awaitOrFail("player never became READY")
        assertNull("unexpected player error", watcher.error)
        assertTrue("expected a ranged open past byte 0, saw $opens", opens.any { it > 0L })

        // Seek to a position ExoPlayer has not decoded yet. seekTo() masks the state to
        // BUFFERING at once when it has to load, so polling for READY afterwards is safe.
        val target = localDurationMs() / 2
        onMain { player!!.seekTo(target) }
        awaitOnMain("seek never settled") {
            watcher.error != null || player!!.playbackState == Player.STATE_READY
        }
        assertNull("unexpected player error after seek", watcher.error)

        var position = -1L
        onMain { position = player!!.currentPosition }
        // Seeks snap to a sync sample; within half a second of an 8s clip is "landed".
        assertTrue("wanted ~$target ms, at $position ms", kotlin.math.abs(position - target) <= 500)
    }

    /** The negative control: media3's stock HTTP stack is refused, with the precise code. */
    @Test
    fun exoPlayer_withTheStockHttpDataSource_isRefusedAsCleartext() {
        val watcher = PlayerWatcher()
        buildPlayer(DefaultHttpDataSource.Factory(), watcher)

        watcher.failed.awaitOrFail("expected a playback error")
        assertEquals(
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            watcher.error?.errorCode
        )
    }

    // --- helpers --------------------------------------------------------------------

    private fun clipUrl() = "http://${address.hostAddress}:$port/api/clips/$clipId?t=$TOKEN"

    private fun buildPlayer(factory: DataSource.Factory, watcher: PlayerWatcher) = onMain {
        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .build()
            .apply {
                // The CI emulator runs with -noaudio; audio is irrelevant to the question.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                addListener(watcher)
                setMediaItem(MediaItem.fromUri(clipUrl()))
                prepare()
            }
    }

    private class PlayerWatcher : Player.Listener {
        val ready = CountDownLatch(1)
        val failed = CountDownLatch(1)
        @Volatile var error: PlaybackException? = null

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) ready.countDown()
        }

        override fun onPlayerError(error: PlaybackException) {
            this.error = error
            failed.countDown()
            ready.countDown()
        }
    }

    private fun awaitOnMain(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_S)
        while (System.currentTimeMillis() < deadline) {
            var met = false
            onMain { met = condition() }
            if (met) return
            Thread.sleep(50)
        }
        throw AssertionError(message)
    }

    private fun CountDownLatch.awaitOrFail(message: String) {
        if (!await(TIMEOUT_S, TimeUnit.SECONDS)) throw AssertionError(message)
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun localBytes(): ByteArray =
        context.contentResolver.openInputStream(clipUri!!)!!.use { it.readBytes() }

    private fun localDurationMs(): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, clipUri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        } finally {
            retriever.release()
        }
    }

    private fun readAll(source: DataSource): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
