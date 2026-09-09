package com.mtbanalyzer

import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen playback of a single clip. All playback chrome (progress line, title,
 * Pose / Draw toggles, transport, auto-hide) lives in [VideoPlayerView].
 */
class VideoPlaybackActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VideoPlaybackActivity"
        const val EXTRA_VIDEO_URI = "video_uri"
        const val EXTRA_VIDEO_NAME = "video_name"
    }

    private lateinit var videoPlayerView: VideoPlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableFullScreenMode()
        setContentView(R.layout.activity_video_playback)
        supportActionBar?.hide()

        videoPlayerView = findViewById(R.id.videoPlayerView)
        setupVideoData()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back leaves draw mode before it leaves the screen
        if (videoPlayerView.isDrawingMode()) {
            videoPlayerView.exitDrawingMode()
            return
        }
        super.onBackPressed()
    }

    private fun setupVideoData() {
        val videoUri = intent.getStringExtra(EXTRA_VIDEO_URI)?.let { Uri.parse(it) }
        val videoName = intent.getStringExtra(EXTRA_VIDEO_NAME) ?: "Video"

        if (videoUri == null) {
            Toast.makeText(this, "Error loading video", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        videoPlayerView.setTitle(videoName.replace("MTB_", "").replace(".mp4", ""))
        videoPlayerView.setVideo(videoUri)

        videoPlayerView.setOnVideoLoadedListener { duration ->
            Log.d(TAG, "Video loaded with duration: $duration ms")
        }
        videoPlayerView.setOnVideoErrorListener { _, _ ->
            Toast.makeText(this, "Error playing video", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enableFullScreenMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    override fun onResume() {
        super.onResume()
        enableFullScreenMode()
    }

    override fun onPause() {
        super.onPause()
        videoPlayerView.pause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableFullScreenMode()
        videoPlayerView.handleOrientationChange(newConfig)
        // Show controls briefly after orientation change
        videoPlayerView.showControls()
    }

    override fun onDestroy() {
        super.onDestroy()
        videoPlayerView.release()
    }
}
