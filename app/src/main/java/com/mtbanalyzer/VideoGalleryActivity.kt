package com.mtbanalyzer

import android.content.ContentUris
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import java.text.SimpleDateFormat
import java.util.*

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAdded: Long,
    val duration: Long,
    val size: Long
)

class VideoGalleryActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "VideoGalleryActivity"
    }
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: VideoAdapter
    private val videos = mutableListOf<VideoItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_gallery)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Video Gallery"
        
        setupUI()
        loadVideos()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
    
    private fun setupUI() {
        recyclerView = findViewById(R.id.recycler_view)
        emptyView = findViewById(R.id.empty_view)
        
        adapter = VideoAdapter(videos) { videoItem ->
            playVideo(videoItem.uri)
        }
        
        recyclerView.layoutManager = GridLayoutManager(this, 2)
        recyclerView.adapter = adapter
    }
    
    private fun loadVideos() {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )
        
        val selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("MTB_%")
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        
        try {
            val cursor: Cursor? = contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )
            
            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durationColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                
                videos.clear()
                
                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val name = it.getString(nameColumn)
                    val dateAdded = it.getLong(dateColumn)
                    val duration = it.getLong(durationColumn)
                    val size = it.getLong(sizeColumn)
                    
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    
                    videos.add(VideoItem(id, contentUri, name, dateAdded, duration, size))
                }
            }
            
            updateUI()
            Log.d(TAG, "Loaded ${videos.size} MTB videos")
            
        } catch (e: SecurityException) {
            Log.e(TAG, "No permission to access videos", e)
            Toast.makeText(this, "Permission needed to access videos", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading videos", e)
            Toast.makeText(this, "Error loading videos: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun updateUI() {
        if (videos.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            adapter.notifyDataSetChanged()
        }
    }
    
    private fun playVideo(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, "No video player found", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing video", e)
            Toast.makeText(this, "Error playing video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh the list in case videos were deleted/added
        loadVideos()
    }
}

class VideoAdapter(
    private val videos: List<VideoItem>,
    private val onVideoClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {
    
    class VideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.video_thumbnail)
        val title: TextView = view.findViewById(R.id.video_title)
        val duration: TextView = view.findViewById(R.id.video_duration)
        val date: TextView = view.findViewById(R.id.video_date)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video, parent, false)
        return VideoViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val video = videos[position]
        
        holder.title.text = video.displayName.replace("MTB_", "").replace(".mp4", "")
        holder.duration.text = formatDuration(video.duration)
        holder.date.text = formatDate(video.dateAdded)
        
        // Load video thumbnail using Glide
        Glide.with(holder.itemView.context)
            .load(video.uri)
            .apply(RequestOptions()
                .centerCrop()
                .placeholder(R.drawable.ic_launcher_foreground)
                .error(R.drawable.ic_launcher_foreground))
            .into(holder.thumbnail)
        
        holder.itemView.setOnClickListener {
            onVideoClick(video)
        }
    }
    
    override fun getItemCount() = videos.size
    
    private fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        return String.format("%d:%02d", minutes, seconds)
    }
    
    private fun formatDate(timestamp: Long): String {
        val date = Date(timestamp * 1000) // MediaStore timestamp is in seconds
        return SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(date)
    }
}