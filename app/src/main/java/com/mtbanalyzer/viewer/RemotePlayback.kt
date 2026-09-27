package com.mtbanalyzer.viewer

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.mtbanalyzer.PlayableClip
import com.mtbanalyzer.VideoPlayerView
import com.mtbanalyzer.clips.ClipRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Puts a recorder clip into a [VideoPlayerView] the M2 way (Phase 2 spec §7): playback streams
 * at once through [RemoteClipDataSource], while [RemoteClipCache] downloads the whole file in
 * parallel for the pose overlay and exact frame metadata. Until the file lands the frame badge
 * uses the recorder's /meta, and a Pose tap shows a spinner and turns on by itself.
 *
 * A clip already in the cache just plays from the file, exactly like a local one.
 */
object RemotePlayback {

    private const val TAG = "RemotePlayback"

    /**
     * @return false when [ref]'s recorder is not the one paired in this process (for example
     *   after the process was restarted), in which case nothing is attached.
     */
    fun attach(activity: AppCompatActivity, player: VideoPlayerView, ref: ClipRef.Remote): Boolean {
        val recorder = RecorderSession.recorder?.takeIf { it.id == ref.recorderId } ?: return false
        val cache = RecorderSession.cache(activity)

        cache.cached(recorder.id, ref.id)?.let { file ->
            player.setClip(PlayableClip.local(Uri.fromFile(file)))
            return true
        }

        player.setClip(
            PlayableClip(
                playbackUri = Uri.parse(recorder.address.clipUrl(ref.id)),
                dataSourceFactory = RemoteClipDataSource.Factory(),
                framesUri = null
            )
        )

        // Leaving the screen abandons the download; a partial file never lands (.part).
        val cancelled = AtomicBoolean(false)
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = cancelled.set(true)
        })

        activity.lifecycleScope.launch {
            val meta = withContext(Dispatchers.IO) {
                runCatching { RecorderClient().meta(recorder, ref.id) }
            }
            meta.onSuccess { player.setFrameInfo(it.effectiveFrameRate, it.frameCount) }
                .onFailure { Log.w(TAG, "No /meta for ${ref.key}; the badge estimates at 30fps", it) }
        }

        val downloading = AtomicBoolean(false)
        fun download() {
            if (!downloading.compareAndSet(false, true)) return
            activity.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { cache.fetch(recorder, ref.id, isCancelled = { cancelled.get() }) }
                }
                downloading.set(false)
                result.onSuccess { player.setFramesUri(Uri.fromFile(it)) }
                    .onFailure { e ->
                        if (cancelled.get()) return@onFailure
                        Log.w(TAG, "Download for frames failed: ${ref.key}", e)
                        player.setFramesUnavailable()
                        Toast.makeText(
                            activity,
                            "Pose needs the whole clip and it didn't download. Tap Pose to try again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
        }
        player.setOnFramesNeededListener { if (!player.hasFrames()) download() }
        download()
        return true
    }
}
