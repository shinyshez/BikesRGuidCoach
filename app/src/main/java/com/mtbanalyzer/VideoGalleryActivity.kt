package com.mtbanalyzer

import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
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
    private lateinit var compareButton: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var cancelCompareButton: android.widget.Button
    private val videos = mutableListOf<VideoItem>()
    private val selectedVideos = mutableListOf<VideoItem>()
    private var isCompareMode = false
    private lateinit var itemTouchHelper: ItemTouchHelper

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
    
    override fun onBackPressed() {
        if (isCompareMode) {
            // Exit compare mode instead of closing activity
            exitCompareMode(findViewById(R.id.compareControls))
        } else {
            super.onBackPressed()
        }
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        // Update grid layout when orientation changes
        val newColumnCount = getColumnCount()
        val layoutManager = recyclerView.layoutManager as GridLayoutManager
        layoutManager.spanCount = newColumnCount
        
        Log.d(TAG, "Orientation changed - using $newColumnCount columns")
    }
    
    private fun getColumnCount(): Int {
        return when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> 3  // More columns in landscape
            Configuration.ORIENTATION_PORTRAIT -> 2   // Standard columns in portrait
            else -> 2  // Default fallback
        }
    }
    
    private fun setupUI() {
        recyclerView = findViewById(R.id.recycler_view)
        emptyView = findViewById(R.id.empty_view)
        compareButton = findViewById(R.id.compareButton)
        cancelCompareButton = findViewById(R.id.cancelCompareButton)
        val startCompareButton = findViewById<android.widget.Button>(R.id.startCompareButton)
        val compareControls = findViewById<android.view.View>(R.id.compareControls)
        val compareInstructions = findViewById<android.widget.TextView>(R.id.compareInstructions)
        
        adapter = VideoAdapter(videos, isCompareMode, selectedVideos,
            onVideoClick = { videoItem, isSelected ->
                if (isCompareMode) {
                    handleVideoSelection(videoItem, isSelected, startCompareButton, compareInstructions)
                } else {
                    playVideo(videoItem.uri)
                }
            },
            onVideoLongClick = { videoItem ->
                if (!isCompareMode) {
                    // Enter compare mode and select the long-pressed video
                    enterCompareMode(compareControls)
                    handleVideoSelection(videoItem, true, startCompareButton, compareInstructions)
                    Toast.makeText(this, "Compare mode: Tap another video to compare", Toast.LENGTH_SHORT).show()
                }
            }
        )
        
        // Set column count based on orientation
        val columnCount = getColumnCount()
        recyclerView.layoutManager = GridLayoutManager(this, columnCount)
        recyclerView.adapter = adapter
        
        // Setup compare mode controls
        compareButton.setOnClickListener {
            enterCompareMode(compareControls)
        }
        
        cancelCompareButton.setOnClickListener {
            exitCompareMode(compareControls)
        }
        
        startCompareButton.setOnClickListener {
            startVideoComparison()
        }
        
        // Setup swipe to delete
        setupDragAndDrop()
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
            // First option: Use our custom video player with pose detection
            val customPlayerIntent = Intent(this, VideoPlaybackActivity::class.java).apply {
                putExtra(VideoPlaybackActivity.EXTRA_VIDEO_URI, uri.toString())
                putExtra(VideoPlaybackActivity.EXTRA_VIDEO_NAME, videos.find { it.uri == uri }?.displayName ?: "Video")
            }
            startActivity(customPlayerIntent)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error launching custom player, trying system player", e)
            
            // Fallback: Try system video player
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                val chooserIntent = Intent.createChooser(intent, "Play video with")
                if (chooserIntent.resolveActivity(packageManager) != null) {
                    startActivity(chooserIntent)
                } else {
                    Toast.makeText(this, "No video player app found", Toast.LENGTH_LONG).show()
                }
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Error playing video", fallbackError)
                Toast.makeText(this, "Error playing video: ${fallbackError.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun enterCompareMode(compareControls: android.view.View) {
        isCompareMode = true
        selectedVideos.clear()
        compareControls.visibility = android.view.View.VISIBLE
        compareButton.visibility = android.view.View.GONE
        adapter.updateCompareMode(true)
        supportActionBar?.title = "Select Videos to Compare"
    }
    
    private fun exitCompareMode(compareControls: android.view.View) {
        isCompareMode = false
        selectedVideos.clear()
        compareControls.visibility = android.view.View.GONE
        compareButton.visibility = android.view.View.VISIBLE
        adapter.updateCompareMode(false)
        supportActionBar?.title = "Video Gallery"
    }
    
    private fun handleVideoSelection(videoItem: VideoItem, isSelected: Boolean, startCompareButton: android.widget.Button, compareInstructions: android.widget.TextView) {
        if (isSelected) {
            if (selectedVideos.size < 2 && !selectedVideos.contains(videoItem)) {
                selectedVideos.add(videoItem)
            }
        } else {
            selectedVideos.remove(videoItem)
        }
        
        // Update UI based on selection count
        when (selectedVideos.size) {
            0 -> {
                compareInstructions.text = "Select 2 videos to compare"
                startCompareButton.isEnabled = false
            }
            1 -> {
                compareInstructions.text = "Select 1 more video"
                startCompareButton.isEnabled = false
            }
            2 -> {
                compareInstructions.text = "Ready to compare!"
                startCompareButton.isEnabled = true
            }
        }
        
        adapter.notifyDataSetChanged()
    }
    
    private fun startVideoComparison() {
        if (selectedVideos.size == 2) {
            val intent = Intent(this, VideoComparisonActivity::class.java).apply {
                putExtra(VideoComparisonActivity.EXTRA_VIDEO1_URI, selectedVideos[0].uri.toString())
                putExtra(VideoComparisonActivity.EXTRA_VIDEO1_NAME, selectedVideos[0].displayName)
                putExtra(VideoComparisonActivity.EXTRA_VIDEO2_URI, selectedVideos[1].uri.toString())
                putExtra(VideoComparisonActivity.EXTRA_VIDEO2_NAME, selectedVideos[1].displayName)
            }
            startActivity(intent)
            
            // Exit compare mode after starting comparison
            exitCompareMode(findViewById(R.id.compareControls))
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the list in case videos were deleted/added
        loadVideos()
    }
    
    private fun setupDragAndDrop() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0, // No drag directions
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT // Swipe directions
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position != RecyclerView.NO_POSITION && position < videos.size) {
                    val video = videos[position]
                    // Show confirmation dialog and restore item position if cancelled
                    showDeleteConfirmation(video, position)
                }
            }

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                // Only enable swipe when not in compare mode
                return if (!isCompareMode) {
                    makeMovementFlags(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
                } else {
                    makeMovementFlags(0, 0)
                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                val background = ColorDrawable()
                
                if (dX > 0) { // Swiping right
                    background.color = Color.parseColor("#FF4444")
                    background.setBounds(
                        itemView.left,
                        itemView.top,
                        itemView.left + dX.toInt(),
                        itemView.bottom
                    )
                } else if (dX < 0) { // Swiping left
                    background.color = Color.parseColor("#FF4444")
                    background.setBounds(
                        itemView.right + dX.toInt(),
                        itemView.top,
                        itemView.right,
                        itemView.bottom
                    )
                }
                
                background.draw(c)
                
                // Draw delete icon
                val deleteIcon = androidx.core.content.ContextCompat.getDrawable(
                    this@VideoGalleryActivity, 
                    R.drawable.ic_delete
                )
                deleteIcon?.let { icon ->
                    val iconMargin = (itemView.height - icon.intrinsicHeight) / 2
                    val iconTop = itemView.top + iconMargin
                    val iconBottom = iconTop + icon.intrinsicHeight
                    
                    if (dX > 0) { // Swiping right
                        val iconLeft = itemView.left + iconMargin
                        val iconRight = iconLeft + icon.intrinsicWidth
                        icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                    } else if (dX < 0) { // Swiping left
                        val iconRight = itemView.right - iconMargin
                        val iconLeft = iconRight - icon.intrinsicWidth
                        icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                    }
                    
                    icon.draw(c)
                }
                
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
                return 0.3f // Require 30% swipe to trigger delete
            }
        }

        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }
    
    private fun showDeleteConfirmation(video: VideoItem, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete Video")
            .setMessage("Are you sure you want to delete '${video.displayName}'?")
            .setPositiveButton("Delete") { _, _ ->
                deleteVideo(video)
            }
            .setNegativeButton("Cancel") { _, _ ->
                // Restore the item position
                adapter.notifyItemChanged(position)
            }
            .setOnCancelListener {
                // Restore the item position if dialog is cancelled
                adapter.notifyItemChanged(position)
            }
            .show()
    }
    
    private fun deleteVideo(video: VideoItem) {
        try {
            val rowsDeleted = contentResolver.delete(video.uri, null, null)
            if (rowsDeleted > 0) {
                Toast.makeText(this, "Video deleted", Toast.LENGTH_SHORT).show()
                loadVideos() // Refresh the list
            } else {
                Toast.makeText(this, "Failed to delete video", Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied to delete video", e)
            Toast.makeText(this, "Permission denied to delete video", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting video", e)
            Toast.makeText(this, "Error deleting video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

class VideoAdapter(
    private val videos: List<VideoItem>,
    private var isCompareMode: Boolean,
    private val selectedVideos: MutableList<VideoItem>,
    private val onVideoClick: (VideoItem, Boolean) -> Unit,
    private val onVideoLongClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {
    
    class VideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.video_thumbnail)
        val title: TextView = view.findViewById(R.id.video_title)
        val duration: TextView = view.findViewById(R.id.video_duration)
        val date: TextView = view.findViewById(R.id.video_date)
        val selectionOverlay: View = view.findViewById(R.id.selectionOverlay)
        val selectionCheckbox: android.widget.CheckBox = view.findViewById(R.id.selectionCheckbox)
    }
    
    fun updateCompareMode(compareMode: Boolean) {
        isCompareMode = compareMode
        notifyDataSetChanged()
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
                .fitCenter()
                .placeholder(R.mipmap.ic_launcher)
                .error(R.mipmap.ic_launcher))
            .into(holder.thumbnail)
        
        // Handle compare mode UI
        if (isCompareMode) {
            holder.selectionCheckbox.visibility = View.VISIBLE
            val isSelected = selectedVideos.contains(video)
            holder.selectionCheckbox.isChecked = isSelected
            holder.selectionOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            
            // Disable selection if 2 videos already selected and this isn't one of them
            val canSelect = selectedVideos.size < 2 || selectedVideos.contains(video)
            holder.selectionCheckbox.isEnabled = canSelect
            holder.itemView.alpha = if (canSelect) 1.0f else 0.5f
            
            holder.itemView.setOnClickListener {
                if (canSelect) {
                    val newSelectionState = !selectedVideos.contains(video)
                    onVideoClick(video, newSelectionState)
                }
            }
            
            holder.selectionCheckbox.setOnClickListener {
                if (canSelect) {
                    val newSelectionState = holder.selectionCheckbox.isChecked
                    onVideoClick(video, newSelectionState)
                }
            }
        } else {
            holder.selectionCheckbox.visibility = View.GONE
            holder.selectionOverlay.visibility = View.GONE
            holder.itemView.alpha = 1.0f
            
            holder.itemView.setOnClickListener {
                onVideoClick(video, false) // isSelected not used in normal mode
            }
            
            holder.itemView.setOnLongClickListener {
                onVideoLongClick(video)
                true // Consume the long click event
            }
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