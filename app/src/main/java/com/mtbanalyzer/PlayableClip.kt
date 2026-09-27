package com.mtbanalyzer

import android.net.Uri
import androidx.media3.datasource.DataSource

/**
 * What [VideoPlayerView] needs to show a clip, which is two different things.
 *
 * ExoPlayer plays [playbackUri], through [dataSourceFactory] when the default stack cannot
 * reach it (a recorder's clip over the raw-socket client). The pose overlay and frame
 * metadata read [framesUri] with MediaMetadataRetriever, which cannot open http:// and would
 * crawl if it could, so for a recorder clip that is the downloaded copy and arrives later
 * (Phase 2 spec §7: stream to play, download to analyse). For a clip on this phone the two
 * are the same Uri.
 */
data class PlayableClip(
    val playbackUri: Uri,
    /** Null for the player's default data sources. */
    val dataSourceFactory: DataSource.Factory? = null,
    /** Null until a local copy exists; see [VideoPlayerView.setFramesUri]. */
    val framesUri: Uri? = playbackUri
) {
    companion object {
        fun local(uri: Uri) = PlayableClip(uri)
    }
}
