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

- The app is locked to landscape orientation (AndroidManifest.xml:32)
- Minimum SDK is 24, target SDK is 34
- Videos are saved to Movies/MTBAnalyzer directory in external storage
- Recording automatically starts when a rider is detected and continues for 8 seconds or 2 seconds after the rider leaves frame
- The pose detection runs in STREAM_MODE for real-time processing