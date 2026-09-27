package com.mtbanalyzer.viewer

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.mtbanalyzer.R
import com.mtbanalyzer.VideoImporter
import com.mtbanalyzer.VideoPlaybackActivity
import com.mtbanalyzer.VideoPlayerView
import com.mtbanalyzer.clips.ClipRef
import com.mtbanalyzer.clips.LocalClipSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Phase 2 M1 end to end on one device: this phone as the recorder, serving itself as the
 * viewer over its own *non-loopback* address, where the debug build's loopback exemption does
 * not apply — so the app is under the policy a release build has on a hotspot
 * (RemoteClipPlaybackInstrumentedTest proves that premise). Pair, list, fetch a thumbnail,
 * download, and open the download in the real player.
 */
@RunWith(AndroidJUnit4::class)
class RemoteViewerInstrumentedTest {

    private companion object {
        const val TOKEN = "m1-token"
    }

    private lateinit var context: Context
    private lateinit var server: ViewerLinkServer
    private lateinit var address: InetAddress
    private lateinit var cacheRoot: File
    private var port = 0
    private var clipUri: Uri? = null
    private var clipId = -1L

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        address = NetworkAddress.best()
            ?: throw AssertionError("No non-loopback IPv4 interface; this test needs one")

        val source = File(context.cacheDir, "remote_viewer_source.mp4")
        InstrumentationRegistry.getInstrumentation().context.resources
            .openRawResource(com.mtbanalyzer.test.R.raw.test_clip)
            .use { input -> source.outputStream().use { input.copyTo(it) } }
        clipUri = VideoImporter(context).import(Uri.fromFile(source))
        assertNotNull("test clip should import", clipUri)
        clipId = ContentUris.parseId(clipUri!!)

        server = ViewerLinkServer(
            ViewerLinkRoutes(
                context = context,
                clips = LocalClipSource(context),
                thumbnails = ClipThumbnails(context),
                token = TOKEN,
                allowedHosts = setOf(address.hostAddress!!)
            )
        )
        port = server.start(address, 0)
        cacheRoot = File(context.cacheDir, "remote-viewer-test").apply { deleteRecursively() }
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
        clipUri?.let { context.contentResolver.delete(it, null, null) }
        if (::cacheRoot.isInitialized) cacheRoot.deleteRecursively()
    }

    private fun recorderAddress() = RecorderAddress.parse("http://${address.hostAddress}:$port/?t=$TOKEN")!!

    private fun pair() = RecorderClient().pair(recorderAddress())

    private fun localBytes(): ByteArray =
        context.contentResolver.openInputStream(clipUri!!)!!.use { it.readBytes() }

    @Test
    fun pairing_findsTheRecorderAndListsItsClip() {
        val recorder = pair()

        assertEquals(RecorderIdentity.id(context), recorder.id)
        val clip = RecorderClient().clips(recorder).single { it.info.id == clipId }
        assertTrue(clip.info.name, clip.info.name.startsWith("MTB_"))
        assertTrue(clip.info.durationMs > 0)
    }

    @Test
    fun cache_downloadsTheClipByteIdentical() {
        val file = RemoteClipCache(cacheRoot).fetch(pair(), clipId)
        assertArrayEquals(localBytes(), file.readBytes())
    }

    @Test
    fun thumbnail_loadsThroughGlideOnTheRawClient() {
        RemoteThumbLoader.register(context)
        val recorder = pair()
        val bitmap = Glide.with(context)
            .asBitmap()
            .load(RemoteThumb(recorder.address, recorder.id, clipId))
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .submit()
            .get(20, TimeUnit.SECONDS)
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
    }

    /**
     * M2 end to end: the playback screen opened on a recorder clip that is not cached plays
     * it over http:// at once, downloads it alongside, and a Pose tap made before the file
     * lands turns pose on by itself when it does.
     */
    @Test
    fun remoteClip_streamsAtOnceAndPoseFollowsTheDownload() {
        val recorder = pair()
        RecorderSession.pair(recorder)
        val cache = RecorderSession.cache(context)
        cache.forget(recorder.id)
        try {
            val intent = Intent(context, VideoPlaybackActivity::class.java)
                .putExtra(VideoPlaybackActivity.EXTRA_REMOTE_CLIP_KEY, ClipRef.Remote(recorder.id, clipId).key)
                .putExtra(VideoPlaybackActivity.EXTRA_VIDEO_NAME, "MTB_streamed.mp4")
            ActivityScenario.launch<VideoPlaybackActivity>(intent).use { scenario ->
                var tappedWhilePending = false
                scenario.onActivity { activity ->
                    val player = activity.findViewById<VideoPlayerView>(R.id.videoPlayerView)
                    assertEquals("playback streams from the recorder", "http", player.playbackUri?.scheme)
                    if (!player.hasFrames()) {
                        activity.findViewById<android.view.View>(R.id.poseToggleButton).performClick()
                        tappedWhilePending = true
                    }
                }

                var landed = false
                repeat(40) {
                    if (!landed) {
                        scenario.onActivity { activity ->
                            landed = activity.findViewById<VideoPlayerView>(R.id.videoPlayerView).hasFrames()
                        }
                        if (!landed) Thread.sleep(500)
                    }
                }
                assertTrue("the download never handed the player its frames", landed)
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                scenario.onActivity { activity ->
                    val player = activity.findViewById<VideoPlayerView>(R.id.videoPlayerView)
                    assertEquals("still streaming; the file only feeds pose", "http", player.playbackUri?.scheme)
                    if (tappedWhilePending) {
                        assertTrue("a Pose tap while pending turns pose on", player.isPoseDetectionEnabled())
                    }
                }
            }
            assertArrayEquals(localBytes(), cache.cached(recorder.id, clipId)!!.readBytes())
        } finally {
            RecorderSession.forget(context)
        }
    }

    @Test
    fun downloadedClip_givesPoseFramesAndOpensInThePlayer() {
        val file = RemoteClipCache(cacheRoot).fetch(pair(), clipId)

        // The pose overlay reads frames through MediaMetadataRetriever, which could not open
        // the http:// URL at all; from the cached file it gets one like any local clip.
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.fromFile(file))
            assertNotNull("no frame from the downloaded clip", retriever.getFrameAtTime(500_000))
        } finally {
            retriever.release()
        }

        val intent = Intent(context, VideoPlaybackActivity::class.java)
            .putExtra(VideoPlaybackActivity.EXTRA_VIDEO_URI, Uri.fromFile(file).toString())
            .putExtra(VideoPlaybackActivity.EXTRA_VIDEO_NAME, "MTB_remote_clip.mp4")
        ActivityScenario.launch<VideoPlaybackActivity>(intent).use { scenario ->
            Thread.sleep(2500) // let ExoPlayer reach READY
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                val title = activity.findViewById<android.widget.TextView>(R.id.titleText)
                assertEquals("remote_clip", title.text.toString())
            }
        }
    }
}
