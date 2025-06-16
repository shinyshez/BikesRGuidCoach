# VideoPlayerView Architecture Documentation

## Overview
VideoPlayerView is a custom Android component that provides frame-accurate video playback with pose detection capabilities for the MTB Analyzer app. It's built on top of MediaPlayer-Extended to achieve precise frame-level seeking that standard Android MediaPlayer cannot provide.

## Core Architecture

### Class Structure
- **Base**: Extends `ConstraintLayout` - custom view that inflates layout and manages child components
- **Primary Component**: Uses `net.protyposis.android.mediaplayer.VideoView` for actual video rendering
- **Layout**: Defined in `view_video_player.xml` with video wrapped in FrameLayout for centering

### Key Components

#### 1. Video Playback
- **VideoView**: MediaPlayer-Extended VideoView for frame-accurate seeking
- **MediaPlayer**: Direct access to underlying MediaPlayer for advanced controls
- **UriSource**: Media source wrapper for video URI handling
- **SeekMode**: EXACT mode by default for most accurate frame seeking

#### 2. UI Controls
- **PlayPauseButton**: Standard play/pause toggle
- **FrameForwardButton**: Step forward by one frame with hold-to-repeat
- **FrameBackwardButton**: Step backward by one frame with hold-to-repeat  
- **SeekBar**: Scrub through video with frame-accurate positioning
- **TimeDisplay**: Shows current time, total time, and frame numbers
- **PoseToggleButton**: Enable/disable pose detection overlay
- **LoadingIndicator**: Shows during video loading

#### 3. Pose Detection Integration
- **GraphicOverlay**: Custom overlay for drawing pose landmarks
- **PoseDetector**: ML Kit pose detection with SINGLE_IMAGE_MODE
- **PoseGraphic**: Renders skeleton with joints and connections
- **Frame Processing**: Extracts frames using MediaMetadataRetriever for pose analysis

## Key Features

### 1. Frame-Accurate Seeking
```kotlin
// Time-based frame stepping (more reliable than frame-based)
val stepSize = frameDurationMs.toInt() // ~33ms for 30fps
val newPosition = currentPos + stepSize
videoView.seekTo(newPosition) // Uses EXACT mode by default
```

### 2. Video Metadata Extraction
```kotlin
// Extracts frame rate, total frames, duration
val frameRate = retriever.extractMetadata(METADATA_KEY_CAPTURE_FRAMERATE)?.toDouble() ?: 30.0
val totalFrames = retriever.extractMetadata(METADATA_KEY_VIDEO_FRAME_COUNT)?.toLong()
val frameDurationMs = 1000.0 / frameRate
```

### 3. Hold-to-Repeat Frame Navigation
```kotlin
// Coroutine-based hold-to-repeat with initial delay and repeat interval
frameButtonHoldJob = coroutineScope.launch {
    stepFrame(forward = true) // Initial step
    delay(frameButtonInitialDelay) // 300ms initial delay
    while (isActive) {
        stepFrame(forward = true) // Repeated steps
        delay(frameButtonRepeatDelay) // 50ms repeat interval
    }
}
```

### 4. Portrait Video Centering
```xml
<!-- FrameLayout wrapper allows layout_gravity="center" to work -->
<FrameLayout>
    <net.protyposis.android.mediaplayer.VideoView
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:layout_gravity="center" />
</FrameLayout>
```

### 5. Orientation Change Support
```kotlin
fun handleOrientationChange(newConfig: Configuration) {
    post { centerVideoView() } // Re-center video for new orientation
}
```

### 6. Real-time Pose Detection
```kotlin
// Continuous pose processing during playback
private fun startPoseProcessing() {
    processingJob = coroutineScope.launch {
        while (isActive && isPoseDetectionEnabled && isPlaying) {
            processCurrentFrameForPose()
            delay(100) // Process every 100ms during playback
        }
    }
}
```

## State Management

### Video State
- **isPlaying**: Boolean tracking playback state
- **videoDuration**: Total video duration in milliseconds  
- **currentFrameIndex**: Current frame number for display
- **videoUri**: Source video URI

### Frame Tracking
- **frameRate**: Video frame rate (extracted from metadata or default 30fps)
- **totalFrames**: Total number of frames in video
- **frameDurationMs**: Milliseconds per frame (1000/frameRate)

### Pose Detection State  
- **isPoseDetectionEnabled**: Boolean toggle for pose overlay
- **poseDetector**: ML Kit PoseDetector instance
- **processingJob**: Coroutine job for continuous pose processing

## Event Handling

### Video Events
```kotlin
setOnPreparedListener { mp -> /* Video loaded and ready */ }
setOnCompletionListener { /* Video finished playing */ }
setOnErrorListener { what, extra -> /* Video error occurred */ }
```

### User Interaction
```kotlin
// Touch events for hold-to-repeat frame buttons
setOnTouchListener { _, event ->
    when (event.action) {
        ACTION_DOWN -> startHoldToRepeat()
        ACTION_UP, ACTION_CANCEL -> stopHoldToRepeat()
    }
    false // Allow click events to also be processed
}
```

### SeekBar Integration
```kotlin
setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        if (fromUser) {
            currentFrameIndex = ((progress * frameRate) / 1000.0).toLong()
            videoView.seekTo(progress) // Frame-accurate seeking
        }
    }
})
```

## Performance Optimizations

### 1. Efficient Frame Processing
- Uses MediaMetadataRetriever for frame extraction (more efficient than video surface capture)
- Processes pose detection on background threads
- Limits pose processing to 100ms intervals during playback

### 2. Memory Management
- Releases MediaMetadataRetriever after each use
- Cancels coroutine jobs on component destruction
- Properly releases MediaPlayer and PoseDetector resources

### 3. UI Responsiveness
- Updates UI immediately on frame steps for perceived responsiveness
- Uses Handler.postDelayed for seek completion callbacks
- Processes pose detection asynchronously

## Integration Points

### 1. VideoPlaybackActivity
- Single video playback with full-screen mode
- Orientation change handling
- Pose detection toggle

### 2. VideoComparisonActivity  
- Dual video players with synchronization
- Lock/unlock for synchronized playback
- Offset calculation and maintenance

## Dependencies

### Required Libraries
```kotlin
// MediaPlayer-Extended for frame-accurate seeking
implementation("net.protyposis.android.mediaplayer:mediaplayer:4.5.0")

// ML Kit for pose detection
implementation("com.google.mlkit:pose-detection:18.0.0-beta3")

// Coroutines for async operations
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
```

### Android Components
- ConstraintLayout for responsive layout
- MediaMetadataRetriever for video metadata and frame extraction
- Handler/Looper for main thread operations
- ImageButton, SeekBar, TextView for UI controls

## Known Limitations

1. **MediaPlayer-Extended Dependency**: External library with specific version requirements
2. **Frame Rate Detection**: Falls back to 30fps if metadata extraction fails
3. **Pose Detection Accuracy**: Limited by ML Kit capabilities and frame quality
4. **Memory Usage**: Frame extraction for pose detection can be memory intensive
5. **Seek Precision**: Even with EXACT mode, seeking may not be perfectly frame-accurate for all video formats

## Future Considerations

1. **Migration to Media3**: AndroidX Media3 provides similar frame-accurate seeking capabilities
2. **Hardware Acceleration**: Could improve pose detection performance
3. **Custom Pose Models**: More accurate detection for specific use cases
4. **Video Format Optimization**: Specific encoding for better seeking accuracy