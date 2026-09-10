package com.mtbanalyzer

import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.util.*
import android.app.Activity
import android.app.RecoverableSecurityException
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

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
    private lateinit var adapter: GalleryAdapter
    private lateinit var compareButton: android.widget.TextView
    private lateinit var cancelCompareButton: android.widget.ImageButton
    private lateinit var compareHeader: View
    private lateinit var bottomControls: android.widget.LinearLayout
    private val videos = mutableListOf<VideoItem>()
    private val selectedVideos = mutableListOf<VideoItem>()
    private var isCompareMode = false
    private lateinit var itemTouchHelper: ItemTouchHelper
    private lateinit var importButton: android.widget.ImageButton
    private lateinit var importer: VideoImporter
    private var readPermissionDenied = false

    // Media read permission: without it MediaStore hides videos from a previous install
    private val requestReadPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        readPermissionDenied = !granted
        loadVideos()
    }

    // Photo Picker: no permission needed, works back to API 24 via the backport
    private val pickVideos = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris -> if (uris.isNotEmpty()) importVideos(uris) }

    // System consent for deleting a video this install doesn't own (API 29+)
    private val deleteConsent = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Video deleted", Toast.LENGTH_SHORT).show()
        }
        loadVideos()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_gallery)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Video Gallery"
        
        importer = VideoImporter(this)
        setupUI()
        ensureReadPermissionThenLoad()
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /** A clip shared to the app from Photos or a file manager is imported like a picked one. */
    private fun handleShareIntent(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        intent.action = null // don't import again on rotation
        importVideos(uris)
    }

    private fun ensureReadPermissionThenLoad() {
        if (MediaPermissions.hasReadVideoPermission(this)) {
            loadVideos()
        } else {
            requestReadPermission.launch(MediaPermissions.readVideoPermission())
        }
    }

    private fun importVideos(uris: List<Uri>) {
        val dialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Importing")
            .setMessage("Importing 1 of ${uris.size}…")
            .setCancelable(false)
            .create()
        dialog.show()
        lifecycleScope.launch {
            var imported = 0
            uris.forEachIndexed { index, uri ->
                dialog.setMessage("Importing ${index + 1} of ${uris.size}…")
                if (importer.import(uri) != null) imported++
            }
            dialog.dismiss()
            val failed = uris.size - imported
            Toast.makeText(
                this@VideoGalleryActivity,
                if (failed == 0) "Imported $imported video${if (imported == 1) "" else "s"}"
                else "Imported $imported, failed $failed",
                Toast.LENGTH_SHORT
            ).show()
            loadVideos()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
    
    override fun onBackPressed() {
        if (isCompareMode) {
            // Exit compare mode instead of closing activity
            exitCompareMode()
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
        layoutManager.spanSizeLookup = headerSpanLookup(newColumnCount)
        
        Log.d(TAG, "Orientation changed - using $newColumnCount columns")
    }
    
    private fun getColumnCount(): Int {
        return when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> 5  // Portrait tiles are narrow; five fit across
            Configuration.ORIENTATION_PORTRAIT -> 3
            else -> 3
        }
    }
    
    private fun headerSpanLookup(columns: Int) = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int) =
            if (adapter.getItemViewType(position) == GalleryAdapter.TYPE_HEADER) columns else 1
    }

    private fun setupUI() {
        recyclerView = findViewById(R.id.recycler_view)
        emptyView = findViewById(R.id.empty_view)
        compareButton = findViewById(R.id.compareButton)
        importButton = findViewById(R.id.importButton)
        compareHeader = findViewById(R.id.compareHeader)
        bottomControls = findViewById(R.id.bottomControls)
        cancelCompareButton = findViewById(R.id.cancelCompareButton)
        importButton.setOnClickListener {
            pickVideos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
        // The empty state doubles as the permission prompt when access was denied
        emptyView.setOnClickListener {
            if (readPermissionDenied) requestReadPermission.launch(MediaPermissions.readVideoPermission())
        }

        adapter = GalleryAdapter(selectedVideos,
            onVideoClick = { videoItem ->
                if (isCompareMode) {
                    handleVideoSelection(videoItem, !selectedVideos.contains(videoItem))
                } else {
                    playVideo(videoItem.uri)
                }
            },
            onVideoLongClick = { videoItem ->
                // Long-press starts compare mode with this clip as pick 1
                enterCompareMode()
                handleVideoSelection(videoItem, true)
            }
        )

        val columnCount = getColumnCount()
        recyclerView.layoutManager = GridLayoutManager(this, columnCount).apply {
            spanSizeLookup = headerSpanLookup(columnCount)
        }
        recyclerView.adapter = adapter

        // The pill starts compare mode, then launches the comparison once two are picked
        compareButton.setOnClickListener {
            if (!isCompareMode) enterCompareMode() else if (selectedVideos.size == 2) startVideoComparison()
        }
        cancelCompareButton.setOnClickListener { exitCompareMode() }

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
            emptyView.text = if (readPermissionDenied) {
                "MTB Analyzer can't see your videos without permission.\n\nTap here to allow access."
            } else {
                "No videos yet\n\nStart riding to capture some footage,\nor import a clip from your phone."
            }
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            adapter.submit(videos)
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
    
    private fun enterCompareMode() {
        isCompareMode = true
        selectedVideos.clear()
        compareHeader.visibility = View.VISIBLE
        importButton.visibility = View.GONE
        bottomControls.gravity = android.view.Gravity.CENTER
        adapter.updateCompareMode(true)
        updateComparePill()
    }

    private fun exitCompareMode() {
        isCompareMode = false
        selectedVideos.clear()
        compareHeader.visibility = View.GONE
        importButton.visibility = View.VISIBLE
        bottomControls.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END
        adapter.updateCompareMode(false)
        updateComparePill()
    }

    private fun handleVideoSelection(videoItem: VideoItem, isSelected: Boolean) {
        if (isSelected) {
            if (selectedVideos.size < 2 && !selectedVideos.contains(videoItem)) selectedVideos.add(videoItem)
        } else {
            selectedVideos.remove(videoItem)
        }
        updateComparePill()
        adapter.notifyDataSetChanged()
    }

    /** Outline "Compare" normally; "Compare · N of 2" while picking; white once two are picked. */
    private fun updateComparePill() {
        val white = 0xFFFFFFFF.toInt()
        when {
            !isCompareMode -> {
                compareButton.text = "Compare"
                compareButton.setBackgroundResource(R.drawable.mode_pill_background)
                compareButton.setTextColor(white)
                compareButton.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(white)
            }
            selectedVideos.size < 2 -> {
                compareButton.text = "Compare · ${selectedVideos.size} of 2"
                compareButton.setBackgroundResource(R.drawable.pill_disabled_background)
                compareButton.setTextColor(0xFF777777.toInt())
                compareButton.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(0xFF777777.toInt())
            }
            else -> {
                compareButton.text = "Compare"
                compareButton.setBackgroundResource(R.drawable.pill_primary_background)
                compareButton.setTextColor(0xFF000000.toInt())
                compareButton.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(0xFF000000.toInt())
            }
        }
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
            exitCompareMode()
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
                val video = if (position != RecyclerView.NO_POSITION) adapter.clipAt(position) else null
                if (video != null) {
                    // Show confirmation dialog and restore item position if cancelled
                    showDeleteConfirmation(video, position)
                } else if (position != RecyclerView.NO_POSITION) {
                    adapter.notifyItemChanged(position)
                }
            }

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                if (viewHolder is GalleryAdapter.HeaderHolder) return 0
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
                val inset = 4 * resources.displayMetrics.density
                val radius = 10 * resources.displayMetrics.density
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FF4444")
                }
                if (dX != 0f) {
                    c.drawRoundRect(
                        itemView.left + inset, itemView.top + inset,
                        itemView.right - inset, itemView.bottom - inset,
                        radius, radius, paint
                    )
                }
                
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
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Delete clip?")
            .setMessage("${video.displayName.removePrefix("MTB_").removeSuffix(".mp4")} will be removed from the phone.")
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
            // A video from a previous install or another app: ask the system for consent
            val intentSender = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    MediaStore.createDeleteRequest(contentResolver, listOf(video.uri)).intentSender
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException ->
                    e.userAction.actionIntent.intentSender
                else -> null
            }
            if (intentSender != null) {
                deleteConsent.launch(IntentSenderRequest.Builder(intentSender).build())
            } else {
                Log.e(TAG, "Permission denied to delete video", e)
                Toast.makeText(this, "Permission denied to delete video", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting video", e)
            Toast.makeText(this, "Error deleting video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
