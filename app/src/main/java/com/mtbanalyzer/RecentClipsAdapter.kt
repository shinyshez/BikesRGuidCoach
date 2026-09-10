package com.mtbanalyzer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions

/**
 * The strip of today's clips on the capture screen: newest first, tap to play. Clips
 * recorded since the screen opened carry a NEW tag so a coach can find the last run.
 */
class RecentClipsAdapter(
    private val onClipTapped: (VideoItem) -> Unit
) : RecyclerView.Adapter<RecentClipsAdapter.ClipHolder>() {

    private var clips: List<VideoItem> = emptyList()
    private var newIds: Set<Long> = emptySet()

    fun submit(clips: List<VideoItem>, newIds: Set<Long>) {
        this.clips = clips
        this.newIds = newIds
        notifyDataSetChanged()
    }

    override fun getItemCount() = clips.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClipHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recent_clip, parent, false)
        return ClipHolder(view)
    }

    override fun onBindViewHolder(holder: ClipHolder, position: Int) {
        val clip = clips[position]
        holder.duration.text = formatDuration(clip.duration)
        holder.newTag.visibility = if (clip.id in newIds) View.VISIBLE else View.GONE
        holder.itemView.contentDescription = "Clip ${clip.displayName.removePrefix("MTB_").removeSuffix(".mp4")}"
        Glide.with(holder.itemView.context)
            .load(clip.uri)
            .apply(RequestOptions().centerCrop())
            .into(holder.thumbnail)
        holder.itemView.setOnClickListener { onClipTapped(clip) }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        return String.format("%d:%02d", totalSec / 60, totalSec % 60)
    }

    class ClipHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.clipThumbnail)
        val newTag: TextView = view.findViewById(R.id.clipNewTag)
        val duration: TextView = view.findViewById(R.id.clipDuration)
    }
}
