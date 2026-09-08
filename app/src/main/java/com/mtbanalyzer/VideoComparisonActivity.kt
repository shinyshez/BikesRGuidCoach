package com.mtbanalyzer

import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Side-by-side comparison of two clips driven by one shared transport.
 *
 * Locked: the transport drives both clips, keeping clip 2 at [positionOffset] relative to
 * clip 1. Unlocked: tapping a clip makes it the active one and the transport drives only
 * that clip, which is how the offset is dialled in. Lock state and offset persist per pair.
 */
class VideoComparisonActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VideoComparison"
        const val EXTRA_VIDEO1_URI = "video1_uri"
        const val EXTRA_VIDEO1_NAME = "video1_name"
        const val EXTRA_VIDEO2_URI = "video2_uri"
        const val EXTRA_VIDEO2_NAME = "video2_name"

        private const val UI_UPDATE_MS = 100L
        private const val SYNC_CHECK_MS = 300L
        private const val SYNC_DRIFT_TOLERANCE_MS = 200
        private const val END_THRESHOLD_MS = 500
        private const val HOLD_INITIAL_DELAY_MS = 300L
        private const val HOLD_REPEAT_DELAY_MS = 50L
    }

    private lateinit var videoPlayer1: VideoPlayerView
    private lateinit var videoPlayer2: VideoPlayerView
    private lateinit var video1Section: FrameLayout
    private lateinit var video2Section: FrameLayout
    // Shared-transport ids are prefixed 'compare' so findViewById can't land on the players' own hidden controls
    private lateinit var sharedSeekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var lockButton: ImageButton
    private lateinit var playPauseButton: ImageButton
    private lateinit var frameBackwardButton: ImageButton
    private lateinit var frameForwardButton: ImageButton
    private lateinit var offsetChip: TextView

    private var video1Uri: Uri? = null
    private var video2Uri: Uri? = null
    private lateinit var syncStore: CompareSyncStore
    private lateinit var pairId1: String
    private lateinit var pairId2: String

    private var isLocked = true
    private var positionOffset = 0 // Video2 position - Video1 position, in ms
    private var activePlayer = 1
    private var loadedCount = 0
    private var initialSyncApplied = false
    private var isUserSeeking = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var syncRunnable: Runnable? = null
    private var uiRunnable: Runnable? = null
    private var holdRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_comparison)

        supportActionBar?.hide()
        syncStore = CompareSyncStore(this)

        initializeViews()
        setupVideoData()
        setupControls()
        startUiUpdater()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    private fun initializeViews() {
        videoPlayer1 = findViewById(R.id.videoPlayer1)
        videoPlayer2 = findViewById(R.id.videoPlayer2)
        video1Section = findViewById(R.id.video1Section)
        video2Section = findViewById(R.id.video2Section)
        sharedSeekBar = findViewById(R.id.sharedSeekBar)
        currentTimeText = findViewById(R.id.compareCurrentTimeText)
        totalTimeText = findViewById(R.id.compareTotalTimeText)
        lockButton = findViewById(R.id.lockButton)
        playPauseButton = findViewById(R.id.comparePlayPauseButton)
        frameBackwardButton = findViewById(R.id.compareFrameBackwardButton)
        frameForwardButton = findViewById(R.id.compareFrameForwardButton)
        offsetChip = findViewById(R.id.offsetChip)

        videoPlayer1.setEmbedded(true)
        videoPlayer2.setEmbedded(true)
    }

    private fun setupVideoData() {
        video1Uri = intent.getStringExtra(EXTRA_VIDEO1_URI)?.let { Uri.parse(it) }
        video2Uri = intent.getStringExtra(EXTRA_VIDEO2_URI)?.let { Uri.parse(it) }

        val uri1 = video1Uri
        val uri2 = video2Uri
        if (uri1 == null || uri2 == null) {
            Toast.makeText(this, "Error loading videos", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        pairId1 = uri1.lastPathSegment ?: uri1.toString()
        pairId2 = uri2.lastPathSegment ?: uri2.toString()
        val saved = syncStore.load(pairId1, pairId2)
        isLocked = saved.locked
        positionOffset = saved.offsetMs
        Log.d(TAG, "Restored sync for $pairId1|$pairId2: locked=$isLocked offset=${positionOffset}ms")

        setupVideoPlayers(uri1, uri2)
        updateLockUi()
        updateActivePanelUi()
    }

    private fun setupVideoPlayers(uri1: Uri, uri2: Uri) {
        try {
            videoPlayer1.setVideo(uri1)
            videoPlayer2.setVideo(uri2)

            videoPlayer1.setOnVideoLoadedListener { onPlayerLoaded() }
            videoPlayer2.setOnVideoLoadedListener { onPlayerLoaded() }

            videoPlayer1.setOnVideoErrorListener { what, extra ->
                Log.e(TAG, "Video1 error: what=$what, extra=$extra")
                Toast.makeText(this, "Error playing video 1", Toast.LENGTH_SHORT).show()
            }
            videoPlayer2.setOnVideoErrorListener { what, extra ->
                Log.e(TAG, "Video2 error: what=$what, extra=$extra")
                Toast.makeText(this, "Error playing video 2", Toast.LENGTH_SHORT).show()
            }

            // Tapping a clip selects it (meaningful while unlocked)
            videoPlayer1.setOnVideoTapListener { setActivePlayer(1) }
            videoPlayer2.setOnVideoTapListener { setActivePlayer(2) }

            // Hold-to-scrub inside a clip keeps the other clip in step while locked
            videoPlayer1.setOnScrubListener { position ->
                if (isLocked) seekWithinDuration(videoPlayer2, position + positionOffset)
            }
            videoPlayer2.setOnScrubListener { position ->
                if (isLocked) seekWithinDuration(videoPlayer1, position - positionOffset)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up videos", e)
            Toast.makeText(this, "Error setting up videos: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onPlayerLoaded() {
        // The loaded callback fires on every READY, including after each seek, so the
        // initial sync below must run exactly once or it would seek in a loop.
        loadedCount++
        if (loadedCount < 2 || initialSyncApplied) return
        initialSyncApplied = true
        sharedSeekBar.max = referencePlayer().getDuration()
        updateTransportUi()
        // Both clips ready: apply the restored offset so a locked pair opens in sync. Seek on
        // the next main-loop pass rather than inside the player's READY callback, so the
        // players' own BUFFERING -> READY handling (and their loading spinner) stays in step.
        if (isLocked && positionOffset != 0) {
            mainHandler.post {
                if (positionOffset > 0) {
                    videoPlayer1.seekTo(0)
                    seekWithinDuration(videoPlayer2, positionOffset)
                } else {
                    seekWithinDuration(videoPlayer1, -positionOffset)
                    videoPlayer2.seekTo(0)
                }
            }
        }
    }

    // ---- Shared transport ----------------------------------------------------------------

    private fun setupControls() {
        lockButton.setOnClickListener { toggleLock() }

        playPauseButton.setOnClickListener {
            if (anyPlaying()) pauseVideos() else playVideosFromStart()
        }

        setupHoldToRepeat(frameBackwardButton) { stepFrames(forward = false) }
        setupHoldToRepeat(frameForwardButton) { stepFrames(forward = true) }

        sharedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (isLocked) {
                    videoPlayer1.seekTo(progress)
                    seekWithinDuration(videoPlayer2, progress + positionOffset)
                } else {
                    activeVideoPlayer().seekTo(progress)
                }
                updateTransportUi()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
                pauseVideos()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
            }
        })
    }

    private fun setupHoldToRepeat(button: ImageButton, step: () -> Unit) {
        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    cancelHold()
                    step()
                    holdRunnable = object : Runnable {
                        override fun run() {
                            step()
                            mainHandler.postDelayed(this, HOLD_REPEAT_DELAY_MS)
                        }
                    }
                    mainHandler.postDelayed(holdRunnable!!, HOLD_INITIAL_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelHold()
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun cancelHold() {
        holdRunnable?.let { mainHandler.removeCallbacks(it) }
        holdRunnable = null
    }

    private fun stepFrames(forward: Boolean) {
        if (anyPlaying()) pauseVideos()
        if (isLocked) {
            videoPlayer1.stepFrameLocal(forward)
            videoPlayer2.stepFrameLocal(forward)
        } else {
            activeVideoPlayer().stepFrameLocal(forward)
        }
        updateTransportUi()
    }

    private fun playVideos() {
        if (isLocked) {
            videoPlayer1.play()
            videoPlayer2.play()
            startSyncRunnable()
        } else {
            activeVideoPlayer().play()
        }
    }

    private fun playVideosFromStart() {
        if (!isLocked) {
            // Independent clip: VideoPlayerView.play() already restarts from the end
            activeVideoPlayer().play()
            return
        }

        val video1AtEnd = videoPlayer1.getCurrentPosition() >= videoPlayer1.getDuration() - END_THRESHOLD_MS
        val video2AtEnd = videoPlayer2.getCurrentPosition() >= videoPlayer2.getDuration() - END_THRESHOLD_MS

        if (video1AtEnd || video2AtEnd) {
            // Reset to start positions accounting for offset
            if (positionOffset >= 0) {
                videoPlayer1.seekTo(0)
                seekWithinDuration(videoPlayer2, positionOffset)
            } else {
                seekWithinDuration(videoPlayer1, -positionOffset)
                videoPlayer2.seekTo(0)
            }
            // Small delay to ensure seek completes before playing
            mainHandler.postDelayed({ playVideos() }, 100)
        } else {
            playVideos()
        }
    }

    private fun pauseVideos() {
        videoPlayer1.pause()
        videoPlayer2.pause()
        stopSyncRunnable()
    }

    private fun anyPlaying() = videoPlayer1.isPlaying() || videoPlayer2.isPlaying()

    private fun seekWithinDuration(player: VideoPlayerView, position: Int) {
        if (position >= 0 && position <= player.getDuration()) {
            player.seekTo(position)
        }
    }

    // ---- Lock / offset -------------------------------------------------------------------

    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            // Freeze the current relationship between the clips
            positionOffset = videoPlayer2.getCurrentPosition() - videoPlayer1.getCurrentPosition()
            if (anyPlaying()) {
                videoPlayer1.play()
                videoPlayer2.play()
                startSyncRunnable()
            }
        } else {
            stopSyncRunnable()
        }
        persistSync()
        updateLockUi()
        updateActivePanelUi()
        updateTransportUi()
        Log.d(TAG, "Lock ${if (isLocked) "on" else "off"}, offset=${positionOffset}ms")
    }

    private fun persistSync() {
        syncStore.save(pairId1, pairId2, CompareSyncStore.SyncState(isLocked, positionOffset))
    }

    private fun currentOffsetMs(): Int =
        if (isLocked) positionOffset else videoPlayer2.getCurrentPosition() - videoPlayer1.getCurrentPosition()

    private fun startSyncRunnable() {
        stopSyncRunnable()
        syncRunnable = object : Runnable {
            override fun run() {
                if (isLocked && anyPlaying()) {
                    // Only resync on real drift, so playback doesn't stutter from constant seeking
                    val expectedPosition2 = videoPlayer1.getCurrentPosition() + positionOffset
                    val drift = kotlin.math.abs(expectedPosition2 - videoPlayer2.getCurrentPosition())
                    if (drift > SYNC_DRIFT_TOLERANCE_MS) {
                        Log.d(TAG, "Videos out of sync by ${drift}ms, resyncing")
                        seekWithinDuration(videoPlayer2, expectedPosition2)
                    }
                }
                mainHandler.postDelayed(this, SYNC_CHECK_MS)
            }
        }
        mainHandler.post(syncRunnable!!)
    }

    private fun stopSyncRunnable() {
        syncRunnable?.let { mainHandler.removeCallbacks(it) }
        syncRunnable = null
    }

    // ---- Active panel --------------------------------------------------------------------

    private fun setActivePlayer(index: Int) {
        if (isLocked || activePlayer == index) return
        activePlayer = index
        updateActivePanelUi()
        updateTransportUi()
    }

    private fun activeVideoPlayer() = if (activePlayer == 1) videoPlayer1 else videoPlayer2

    /** The clip the shared seekbar and time follow. */
    private fun referencePlayer() = if (isLocked) videoPlayer1 else activeVideoPlayer()

    private fun updateActivePanelUi() {
        if (isLocked) {
            video1Section.foreground = null
            video2Section.foreground = null
        } else {
            val outline = ContextCompat.getDrawable(this, R.drawable.panel_active_outline)
            val scrim = ContextCompat.getDrawable(this, R.drawable.panel_inactive_scrim)
            video1Section.foreground = if (activePlayer == 1) outline else scrim
            video2Section.foreground = if (activePlayer == 2) outline else scrim
        }
    }

    private fun updateLockUi() {
        lockButton.isSelected = isLocked
        lockButton.setImageResource(if (isLocked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        lockButton.contentDescription = if (isLocked) "Unlock clips" else "Lock clips together"
    }

    // ---- UI updates ----------------------------------------------------------------------

    private fun startUiUpdater() {
        uiRunnable = object : Runnable {
            override fun run() {
                updateTransportUi()
                mainHandler.postDelayed(this, UI_UPDATE_MS)
            }
        }
        mainHandler.post(uiRunnable!!)
    }

    private fun updateTransportUi() {
        val reference = referencePlayer()
        val duration = reference.getDuration()
        val position = reference.getCurrentPosition()
        if (sharedSeekBar.max != duration) sharedSeekBar.max = duration
        if (!isUserSeeking) sharedSeekBar.progress = position
        currentTimeText.text = formatTime(position)
        totalTimeText.text = formatTime(duration)

        playPauseButton.setImageResource(
            if (anyPlaying()) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        offsetChip.text = CompareSyncStore.formatOffsetFrames(currentOffsetMs(), videoPlayer1.getFrameDurationMs())
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        return String.format("%d:%02d", totalSec / 60, totalSec % 60)
    }

    // ---- Lifecycle -----------------------------------------------------------------------

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Clips stay side by side in both orientations; just let the players re-layout
        videoPlayer1.handleOrientationChange(newConfig)
        videoPlayer2.handleOrientationChange(newConfig)
    }

    override fun onPause() {
        super.onPause()
        pauseVideos()
        persistSync()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSyncRunnable()
        cancelHold()
        uiRunnable?.let { mainHandler.removeCallbacks(it) }
        try {
            videoPlayer1.release()
            videoPlayer2.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing videos", e)
        }
    }
}
