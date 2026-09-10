package com.mtbanalyzer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The gallery grid: portrait tiles grouped under a header per day, newest first.
 *
 * Tap plays; long-press starts compare mode with that clip as pick 1. In compare mode every
 * tile shows an empty ring, picks show their number (1 is the left clip in the comparison),
 * and once two are picked the rest dim.
 */
class GalleryAdapter(
    private val selectedVideos: List<VideoItem>,
    private val onVideoClick: (VideoItem) -> Unit,
    private val onVideoLongClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val label: String, val count: Int) : Row()
        data class Clip(val video: VideoItem) : Row()
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_CLIP = 1
        /** Recordings are named MTB_yyyy-MM-dd-HH-mm-ss-SSS.mp4; anything else came in via import. */
        private val RECORDING_NAME = Regex("""^MTB_\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2}-\d{3}\.mp4$""")

        fun dayLabel(dateAddedSeconds: Long, now: Calendar = Calendar.getInstance()): String {
            val then = Calendar.getInstance().apply { timeInMillis = dateAddedSeconds * 1000 }
            val sameDay = { a: Calendar, b: Calendar ->
                a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            }
            if (sameDay(then, now)) return "Today"
            val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            if (sameDay(then, yesterday)) return "Yesterday"
            val pattern = if (then.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "EEE d MMM" else "EEE d MMM yyyy"
            return SimpleDateFormat(pattern, Locale.getDefault()).format(then.time)
        }

        fun isImport(displayName: String) = !RECORDING_NAME.matches(displayName)

        /** Groups a newest-first list into day headers and clips. */
        fun buildRows(videos: List<VideoItem>): List<Row> {
            val rows = mutableListOf<Row>()
            var currentLabel: String? = null
            var headerIndex = -1
            var count = 0
            for (video in videos) {
                val label = dayLabel(video.dateAdded)
                if (label != currentLabel) {
                    if (headerIndex >= 0) rows[headerIndex] = Row.Header(currentLabel!!, count)
                    currentLabel = label
                    headerIndex = rows.size
                    count = 0
                    rows.add(Row.Header(label, 0))
                }
                rows.add(Row.Clip(video))
                count++
            }
            if (headerIndex >= 0) rows[headerIndex] = Row.Header(currentLabel!!, count)
            return rows
        }
    }

    private var rows: List<Row> = emptyList()
    private var isCompareMode = false

    fun submit(videos: List<VideoItem>) {
        rows = buildRows(videos)
        notifyDataSetChanged()
    }

    fun updateCompareMode(compareMode: Boolean) {
        isCompareMode = compareMode
        notifyDataSetChanged()
    }

    /** The clip at an adapter position, or null for a header. */
    fun clipAt(position: Int): VideoItem? = (rows.getOrNull(position) as? Row.Clip)?.video

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_CLIP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_gallery_header, parent, false))
        } else {
            ClipHolder(inflater.inflate(R.layout.item_video, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row)
            is Row.Clip -> (holder as ClipHolder).bind(row.video)
        }
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.headerLabel)
        private val count: TextView = view.findViewById(R.id.headerCount)
        fun bind(row: Row.Header) {
            label.text = row.label
            count.text = if (row.count == 1) "1 clip" else "${row.count} clips"
        }
    }

    inner class ClipHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tile: View = view.findViewById(R.id.tile)
        private val thumbnail: ImageView = view.findViewById(R.id.video_thumbnail)
        private val scrim: View = view.findViewById(R.id.tileScrim)
        private val outline: View = view.findViewById(R.id.selectedOutline)
        private val importTag: TextView = view.findViewById(R.id.importTag)
        private val badge: TextView = view.findViewById(R.id.selectBadge)
        private val time: TextView = view.findViewById(R.id.video_time)
        private val duration: TextView = view.findViewById(R.id.video_duration)

        fun bind(video: VideoItem) {
            time.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(video.dateAdded * 1000))
            duration.text = formatDuration(video.duration)
            importTag.visibility = if (isImport(video.displayName)) View.VISIBLE else View.GONE
            tile.contentDescription = video.displayName.removePrefix("MTB_").removeSuffix(".mp4")

            Glide.with(itemView.context)
                .load(video.uri)
                .apply(RequestOptions().centerCrop())
                .into(thumbnail)

            val pick = selectedVideos.indexOf(video)
            if (isCompareMode) {
                badge.visibility = View.VISIBLE
                if (pick >= 0) {
                    badge.setBackgroundResource(R.drawable.select_number)
                    badge.text = (pick + 1).toString()
                } else {
                    badge.setBackgroundResource(R.drawable.select_ring)
                    badge.text = ""
                }
                outline.visibility = if (pick >= 0) View.VISIBLE else View.GONE
                scrim.visibility = if (pick < 0 && selectedVideos.size >= 2) View.VISIBLE else View.GONE
            } else {
                badge.visibility = View.GONE
                outline.visibility = View.GONE
                scrim.visibility = View.GONE
            }

            tile.setOnClickListener { onVideoClick(video) }
            tile.setOnLongClickListener {
                if (!isCompareMode) onVideoLongClick(video)
                !isCompareMode
            }
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}
