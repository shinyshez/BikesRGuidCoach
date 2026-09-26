package com.mtbanalyzer

import android.content.Intent
import android.content.res.Configuration
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
import com.mtbanalyzer.clips.ClipInfo
import com.mtbanalyzer.clips.ClipRef
import com.mtbanalyzer.clips.LocalClipSource
import com.mtbanalyzer.viewer.RecorderAddress
import com.mtbanalyzer.viewer.RecorderClient
import com.mtbanalyzer.viewer.RecorderPairingUi
import com.mtbanalyzer.viewer.RecorderSession
import com.mtbanalyzer.viewer.RemoteClip
import com.mtbanalyzer.viewer.RemoteThumb
import com.mtbanalyzer.viewer.RemoteThumbLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAdded: Long,
    val duration: Long,
    val size: Long,
    /** Which clip this is, for anything persisted against it (compare sync, caches). */
    val ref: ClipRef = ClipRef.Local(id),
    /** What Glide loads for the tile: the MediaStore Uri, or a [RemoteThumb]. */
    val thumbnail: Any = uri
) {
    val isRemote: Boolean get() = ref is ClipRef.Remote

    companion object {
        fun from(clip: ClipInfo) = VideoItem(
            id = clip.id,
            uri = LocalClipSource.uriFor(clip.id),
            displayName = clip.name,
            dateAdded = clip.dateAdded,
            duration = clip.durationMs,
            size = clip.sizeBytes
        )

        fun from(clip: RemoteClip, address: RecorderAddress) = VideoItem(
            id = clip.info.id,
            uri = Uri.parse(address.clipUrl(clip.info.id)),
            displayName = clip.info.name,
            dateAdded = clip.info.dateAdded,
            duration = clip.info.durationMs,
            size = clip.info.sizeBytes,
            ref = clip.ref,
            thumbnail = RemoteThumb(address, clip.recorderId, clip.info.id)
        )
    }
}

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
    private lateinit var clipSource: LocalClipSource
    private var readPermissionDenied = false

    // Viewer Link, viewer side: the paired recorder's clips as a second source
    private lateinit var sourceBar: View
    private lateinit var sourceToggle: View
    private lateinit var sourceLocal: TextView
    private lateinit var sourceRemote: TextView
    private lateinit var pairingUi: RecorderPairingUi
    private val recorderClient = RecorderClient()
    private var showingRemote = false
    private var remoteError: String? = null
    private var remoteLoading = false
    private var remoteLoad: Job? = null

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
        clipSource = LocalClipSource(this)
        pairingUi = RecorderPairingUi(this) { onPairingChanged() }
        RemoteThumbLoader.register(this)
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
        sourceBar = findViewById(R.id.sourceBar)
        sourceToggle = findViewById(R.id.sourceToggle)
        sourceLocal = findViewById(R.id.sourceLocal)
        sourceRemote = findViewById(R.id.sourceRemote)
        findViewById<View>(R.id.connectButton).setOnClickListener { pairingUi.showMenu() }
        sourceLocal.setOnClickListener { showSource(remote = false) }
        sourceRemote.setOnClickListener { showSource(remote = true) }
        importButton.setOnClickListener {
            pickVideos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
        // The empty state doubles as the permission prompt when access was denied
        emptyView.setOnClickListener {
            when {
                showingRemote -> loadVideos() // retry
                readPermissionDenied -> requestReadPermission.launch(MediaPermissions.readVideoPermission())
            }
        }

        adapter = GalleryAdapter(selectedVideos,
            onVideoClick = { videoItem ->
                when {
                    isCompareMode -> handleVideoSelection(videoItem, !selectedVideos.contains(videoItem))
                    videoItem.isRemote -> playRemote(videoItem)
                    else -> playVideo(videoItem.uri)
                }
            },
            onVideoLongClick = { videoItem ->
                if (videoItem.isRemote) {
                    // Compare across sources is M3; until then a long-press keeps a copy
                    offerSaveToPhone(videoItem)
                } else {
                    // Long-press starts compare mode with this clip as pick 1
                    enterCompareMode()
                    handleVideoSelection(videoItem, true)
                }
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
        applySourceUi()
        if (showingRemote) {
            loadRemoteVideos()
            return
        }
        remoteLoad?.cancel()
        try {
            val clips = clipSource.list()
            videos.clear()
            clips.mapTo(videos) { VideoItem.from(it) }

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
            emptyView.text = if (showingRemote && remoteLoading) {
                "Loading clips from ${RecorderSession.recorder?.device ?: "the recorder"}…"
            } else if (showingRemote) {
                remoteError?.let { "$it\n\nTap to try again." }
                    ?: "No clips on ${RecorderSession.recorder?.device ?: "the recorder"} yet.\n\nTap to refresh."
            } else if (readPermissionDenied) {
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
        sourceBar.visibility = View.GONE
        importButton.visibility = View.GONE
        bottomControls.gravity = android.view.Gravity.CENTER
        adapter.updateCompareMode(true)
        updateComparePill()
    }

    private fun exitCompareMode() {
        isCompareMode = false
        selectedVideos.clear()
        compareHeader.visibility = View.GONE
        sourceBar.visibility = View.VISIBLE
        importButton.visibility = if (showingRemote) View.GONE else View.VISIBLE
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
                putExtra(VideoComparisonActivity.EXTRA_VIDEO1_KEY, selectedVideos[0].ref.key)
                putExtra(VideoComparisonActivity.EXTRA_VIDEO2_URI, selectedVideos[1].uri.toString())
                putExtra(VideoComparisonActivity.EXTRA_VIDEO2_NAME, selectedVideos[1].displayName)
                putExtra(VideoComparisonActivity.EXTRA_VIDEO2_KEY, selectedVideos[1].ref.key)
            }
            startActivity(intent)
            
            // Exit compare mode after starting comparison
            exitCompareMode()
        }
    }

    // --- Viewer Link: the paired recorder as a second source ---------------------------

    private fun onPairingChanged() {
        showingRemote = RecorderSession.recorder != null
        loadVideos()
    }

    private fun showSource(remote: Boolean) {
        if (remote && RecorderSession.recorder == null) return
        // Tapping the selected segment again refreshes it: there is no live update until M4
        showingRemote = remote
        loadVideos()
    }

    /** The pill appears only while paired; remote clips get no Import and no Compare (M3). */
    private fun applySourceUi() {
        val recorder = RecorderSession.recorder
        if (recorder == null) showingRemote = false
        sourceToggle.visibility = if (recorder != null) View.VISIBLE else View.GONE
        sourceRemote.text = recorder?.device ?: "Recorder"
        styleSegment(sourceLocal, selected = !showingRemote)
        styleSegment(sourceRemote, selected = showingRemote)
        if (!isCompareMode) {
            importButton.visibility = if (showingRemote) View.GONE else View.VISIBLE
            compareButton.visibility = if (showingRemote) View.GONE else View.VISIBLE
        }
    }

    private fun styleSegment(segment: TextView, selected: Boolean) {
        segment.setBackgroundResource(if (selected) R.drawable.mode_segment_selected else 0)
        segment.setTextColor(if (selected) 0xFF000000.toInt() else 0xFFDDDDDD.toInt())
        segment.isSelected = selected
    }

    private fun loadRemoteVideos() {
        val recorder = RecorderSession.recorder ?: return
        remoteLoad?.cancel()
        // Never leave this phone's tiles (swipe-deletable) on screen under the Recorder label
        if (videos.any { !it.isRemote }) videos.clear()
        remoteLoading = true
        remoteError = null
        updateUI()
        remoteLoad = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { recorderClient.clips(recorder) } }
            if (!showingRemote) return@launch
            remoteLoading = false
            videos.clear()
            result.onSuccess { clips ->
                remoteError = null
                clips.mapTo(videos) { VideoItem.from(it, recorder.address) }
            }.onFailure { e ->
                Log.w(TAG, "Could not list the recorder's clips", e)
                remoteError = pairingUi.failureMessage(e)
            }
            updateUI()
        }
    }

    /** Download, then play (M1): the player sees a local file, so pose and drawing just work. */
    private fun playRemote(video: VideoItem) {
        val recorder = RecorderSession.recorder ?: return
        pairingUi.fetchClip(recorder, video.id, "Loading ${prettyName(video)}") { file ->
            startActivity(Intent(this, VideoPlaybackActivity::class.java).apply {
                putExtra(VideoPlaybackActivity.EXTRA_VIDEO_URI, Uri.fromFile(file).toString())
                putExtra(VideoPlaybackActivity.EXTRA_VIDEO_NAME, video.displayName)
            })
        }
    }

    /** A copy into this phone's own gallery. Nothing is written to the recorder. */
    private fun offerSaveToPhone(video: VideoItem) {
        val recorder = RecorderSession.recorder ?: return
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Save to this phone?")
            .setMessage("${prettyName(video)} will be copied into this phone's gallery, to keep or compare later. Nothing changes on ${recorder.device}.")
            .setPositiveButton("Save") { _, _ ->
                pairingUi.fetchClip(recorder, video.id, "Saving ${prettyName(video)}") { file ->
                    lifecycleScope.launch {
                        val saved = importer.import(Uri.fromFile(file), originalName = video.displayName)
                        Toast.makeText(
                            this@VideoGalleryActivity,
                            if (saved != null) "Saved to this phone" else "Could not save the clip",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun prettyName(video: VideoItem) = video.displayName.removePrefix("MTB_").removeSuffix(".mp4")

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
                // The link is read-only: a recorder's clips are deleted on the recorder
                if (adapter.clipAt(viewHolder.adapterPosition)?.isRemote == true) return 0
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
