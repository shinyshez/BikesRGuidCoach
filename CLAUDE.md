# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MTB Analyzer is an Android application that uses computer vision to automatically detect and record mountain bikers. The app uses ML Kit's pose detection to identify when a rider enters the camera frame and records an 8-second video clip.

## Architecture

### Core Components

1. **MainActivity** (app/src/main/java/com/mtbanalyzer/MainActivity.kt:29-414)
   - Main entry point that handles camera setup, permissions, and recording logic
   - Uses CameraX for camera operations and video recording
   - Implements pose detection using ML Kit
   - Manages recording state machine (IDLE → RECORDING → SAVING/ERROR)

2. **GraphicOverlay** (app/src/main/java/com/mtbanalyzer/GraphicOverlay.kt:11-122)
   - Custom view for drawing pose landmarks over camera preview
   - Handles coordinate transformations between camera image and screen display
   - Manages collection of graphics to draw

3. **PoseGraphic** (app/src/main/java/com/mtbanalyzer/PoseGraphic.kt:9-71)
   - Draws detected pose landmarks and connections on the overlay
   - Visualizes skeleton structure with joints and connecting lines

### Key Dependencies

- **CameraX**: Camera and video recording functionality
- **ML Kit Pose Detection**: Detecting human poses in camera frames
- **AndroidX**: Core Android components and UI

## Build Commands

```bash
# Build the project
./gradlew build

# Install debug build on device
./gradlew installDebug

# Run lint checks
./gradlew lint
./gradlew lintDebug

# Run unit tests
./gradlew test
./gradlew testDebugUnitTest

# Run instrumented tests on connected device
./gradlew connectedAndroidTest

# Clean build artifacts
./gradlew clean

# Check all code quality (lint + tests)
./gradlew check
```

## Development Workflow

### Running the App
1. Connect an Android device with API 24+ or start an emulator
2. Run `./gradlew installDebug` to install the app
3. Grant camera and audio recording permissions when prompted

### Testing a Single Test
```bash
# Run specific unit test class
./gradlew testDebugUnitTest --tests "com.mtbanalyzer.ExampleUnitTest"

# Run specific instrumented test
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mtbanalyzer.ExampleInstrumentedTest
```

### Important Technical Details

- The app supports both portrait and landscape orientation
- Minimum SDK is 24, target SDK is 34
- Videos are saved to Movies/MTBAnalyzer directory in external storage
- Recording automatically starts when a rider is detected and continues for 8 seconds or 2 seconds after the rider leaves frame
- The pose detection runs in STREAM_MODE for real-time processing

## User Interface

### Main Recording Screen

- **Auto-record Toggle**: Controls automatic rider detection and recording
- **Large Record Button**: Manual recording with visual feedback (circle with dot → circle with square when recording)
- **Status Display**: Shows monitoring status, detection confidence, and recording progress (hidden when auto-record is off)
- **Navigation**: Gallery and Settings buttons positioned on either side of the record button

### Video Gallery

The video gallery displays all recorded MTB videos with the following features:

#### Gesture Controls
- **Tap**: Play video with pose detection overlay
- **Long Press**: Enter compare mode to select videos for side-by-side comparison
- **Swipe Left/Right**: Delete video with confirmation dialog

#### Compare Mode
- Select up to 2 videos for side-by-side comparison
- Long press any video to enter compare mode
- Tap additional videos to select for comparison
- Use "Compare" button when 2 videos are selected

#### Delete Functionality
- Swipe any video thumbnail left or right to reveal delete action
- Visual feedback shows red background with trash icon during swipe
- Requires 30% swipe threshold to trigger confirmation dialog
- Confirmation dialog prevents accidental deletions
- Swipe gestures are disabled during compare mode

### Settings

- **Detector Type**: Choose between ML Kit Pose Detection, Motion Detection, Optical Flow, or Hybrid
- **Recording Duration**: Set video length (default 8 seconds)
- **Detection Sensitivity**: Adjust motion and pose detection thresholds
- **Remote Control**: Enable Bluetooth remote control via volume buttons
- **Performance Overlay**: Show detection performance metrics
- **Orientation Support**: Settings screen adapts to device orientation

### Remote Control (Bluetooth)

When enabled in settings:
- **Volume Up**: Start/stop manual recording
- **Volume Down**: Toggle auto-record on/off