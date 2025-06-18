# MTB Analyzer - Features & Behaviors Documentation

## Overview
MTB Analyzer is an Android application that uses computer vision to automatically detect and record mountain bikers. The app employs ML Kit's pose detection and other detection methods to identify when a rider enters the camera frame and records video clips automatically.

## Core Features

### 1. Automatic Rider Detection & Recording
- **Multiple Detection Methods**: 
  - ML Kit Pose Detection (default)
  - Motion Detection
  - Optical Flow Detection
  - Hybrid Detection
- **Automatic Recording**: Starts recording when a rider is detected
- **Configurable Duration**: Default 8-second clips (adjustable 3-30 seconds)
- **Post-Rider Recording**: Continues recording for 2 seconds after rider leaves frame (adjustable)
- **Manual Recording**: Test button for manual recording start/stop

### 2. Video Playback & Analysis

#### Single Video Playback
- **Frame-Accurate Seeking**: Using Media3 ExoPlayer for precise frame navigation
- **Frame Stepping**: Forward/backward buttons with hold-to-repeat functionality
- **Pose Detection Overlay**: Toggle to show/hide skeleton overlay on videos
- **Scrub Gesture**: Hold and drag on video to scrub through frames
- **Smart Restart**: Tap play button when at end to restart from beginning
- **Dynamic Play Button**: Shows reload icon when video reaches end
- **Orientation Support**: Videos adapt to portrait/landscape orientation

#### Video Comparison Mode
- **Side-by-Side Comparison**: Compare two videos simultaneously
- **Synchronized Playback**: Lock mode keeps videos in sync with offset
- **Independent Controls**: When unlocked, control videos separately
- **Portrait/Landscape Layouts**: 
  - Landscape: Videos side-by-side
  - Portrait: Videos stacked vertically
- **Unified Controls in Lock Mode**: All controls affect both videos when locked

### 3. Video Gallery
- **Grid Layout**: Responsive grid that adapts to orientation
- **Aspect Ratio Preservation**: Thumbnails maintain video aspect ratio
- **Chronological Sorting**: Most recent videos first
- **Video Selection**: Tap to play, long press for comparison mode

### 4. Settings & Configuration

#### Detection Settings
- **Enable/Disable Detection**: Toggle automatic rider detection
- **Detection Method**: Choose between pose, motion, optical flow, or hybrid
- **Detection Sensitivity**: Adjustable threshold (30-100%)
- **Motion Detection Settings**: Threshold and minimum area configuration

#### Recording Settings
- **Recording Duration**: 3-30 seconds per clip
- **Post-Rider Delay**: 0-10 seconds continued recording after rider exits

#### Feedback Options
- **Haptic Feedback**: Vibration on recording events
- **Sound Feedback**: Audio cues for recording start/stop
- **Pose Overlay**: Show detected skeleton during recording

#### Remote Control
- **Bluetooth Remote**: Use volume buttons as remote control
  - Volume Up: Start/stop manual recording
  - Volume Down: Toggle rider detection on/off
- **Visual Feedback**: Toast notifications for remote actions

#### Developer Options
- **Performance Monitor**: FPS, processing time, and latency metrics overlay

### 5. User Interface

#### Main Recording Screen
- **Status Indicators**: 
  - Detection state (enabled/disabled)
  - Recording status
  - Confidence level
  - Video count for the day
- **Quick Controls**:
  - Detection toggle switch
  - Settings button
  - Gallery button
  - Manual record button
- **Visual Feedback**:
  - Pulsing ring when detecting
  - Red overlay when recording
  - Progress bar during recording

#### Video Player Controls
- **Playback Controls**: Play/pause, frame forward/backward
- **Seek Bar**: Frame-accurate seeking with time display
- **Pose Toggle**: Show/hide pose detection overlay
- **Scrub Overlay**: Visual feedback during scrub gesture

## Behaviors

### Recording Behavior
1. **Automatic Detection Flow**:
   - Camera continuously analyzes frames
   - When rider detected → Start recording
   - Recording continues for configured duration OR
   - Stops 2 seconds after rider leaves frame
   - Video saved to Movies/MTBAnalyzer directory

2. **Manual Recording Flow**:
   - Press record button → Start/stop recording
   - No detection required in manual mode
   - Same duration limits apply

### Screen Management
- **Keep Screen On**: When detection is enabled, screen stays on
- **Power Management**: Partial wake lock ensures detection continues
- **Auto-Brightness**: Recording screen maintains visibility

### Video Storage
- **Location**: External storage in Movies/MTBAnalyzer
- **Naming**: MTB_YYYYMMDD_HHMMSS format
- **Permissions**: Requires camera, audio, and storage permissions

### Gesture Controls
1. **Video Playback**:
   - Tap: Play/pause
   - Hold & drag: Scrub through video
   - Frame buttons: Single tap or hold for repeat

2. **Video Gallery**:
   - Tap: Open video in player
   - Long press: Select for comparison
   - Swipe: Scroll through videos

### Synchronization (Comparison Mode)
- **Lock Mode**: Maintains time offset between videos
- **Sync Behavior**: 
  - Seeking one video seeks both
  - Playing one plays both
  - Frame stepping affects both
- **Unlock Mode**: Independent control of each video

## Technical Details

### Detection Algorithms
1. **Pose Detection**: Uses ML Kit to detect human pose landmarks
2. **Motion Detection**: Analyzes frame differences for movement
3. **Optical Flow**: Tracks feature points between frames
4. **Hybrid**: Combines multiple detection methods

### Video Processing
- **Codec**: Media3 ExoPlayer for playback
- **Frame Rate**: Typically 30fps (detected from metadata)
- **Seeking**: SEEK_PRECISE mode for frame accuracy
- **Extraction**: MediaMetadataRetriever for pose analysis

### Performance Optimizations
- **Caching**: 15-minute cache for pose detection results
- **Threading**: Background processing for detection
- **Efficient Layouts**: ConstraintLayout for responsive UI
- **Memory Management**: Proper cleanup of video resources

## Known Limitations
- Minimum Android API 24 (Android 7.0)
- Landscape orientation locked for recording
- Detection accuracy depends on lighting and camera angle
- Bluetooth remote requires volume button simulation