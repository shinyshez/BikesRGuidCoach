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

## Local Toolchain (fast loop)

The Mac has JDK 17 (`brew install openjdk@17`) and the Android SDK
(`brew install --cask android-commandlinetools`) at `/opt/homebrew/share/android-commandlinetools`,
pointed to by the gitignored `local.properties`. Every shell needs the env first:

```bash
source scripts/android-env.sh          # JAVA_HOME, ANDROID_HOME, adb/emulator on PATH
scripts/emulator.sh start              # boot headless AVD "mtb-test" (API 34 arm64), ~30s cold
./gradlew assembleDebug                 # ~2 min first time, seconds after
./gradlew connectedDebugAndroidTest     # Espresso tests on the emulator, ~30s
scripts/emulator.sh install             # reinstall + grant permissions (tests uninstall the app)
bash scripts/capture-screenshots.sh     # drive every screen, PNGs + logcat in ./screenshots/
scripts/emulator.sh stop
```

Gotchas when driving the app by hand: `connectedDebugAndroidTest` uninstalls the app
afterwards (and with it runtime permissions and SharedPreferences), so run
`scripts/emulator.sh install` before any manual capture. `uiautomator dump` fails with
"could not get idle state" on screens with two video surfaces (comparison); use
`adb shell dumpsys activity top` for the view hierarchy there and tap by coordinates.
A pushed video needs `adb shell content call --uri content://media/external_primary/file
--method scan_file --arg <path>` before the gallery can see it (`is_pending` is cleared).

`scripts/emulator.sh create` (one-off) builds the AVD. The screenshot script is the
same one CI runs; it finds views by `content-desc`/`text` via `uiautomator dump`
(`tap_by_attr`, `scroll_to`) so it works at any screen size. Always open the PNGs to
check them — a green exit only means the taps landed on *something*. Use CI (below)
as the final check on a PR; the local loop is for iteration.

## Development Workflow

### Running the App
1. Connect an Android device with API 24+ or run `scripts/emulator.sh start`
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

### Video Playback

Playback is deliberately chrome-free while playing: only a 2dp progress line along the
bottom edge. All of it lives in `VideoPlayerView` (`view_video_player.xml`).

- **Tap** the video to reveal/hide the controls; they auto-hide 3s after play starts
- **Revealed**: title (top-left), Pose and Draw icon toggles (top-right, filled when on),
  seekbar with `0:04 … 0:08` times, and prev-frame / play / next-frame
- **Paused**: a `Frame 237/240` badge appears top-left (hidden while playing)
- **Draw**: tools move to a rail on the right edge (pen, arrow, colour, undo, clear); the
  title and toggles hide and the transport reads prev / Done / next
- **Hold** on the video to scrub; pinch to zoom while paused (unchanged)

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

#### Comparison Screen
- Clips sit **side by side in both orientations** (portrait clips fit two across)
- One **shared transport**: seekbar, prev / play / next, a Lock toggle and a `Δ +5f` chip
  showing clip 2's offset from clip 1 in frames
- **Locked** (default for a new pair): the transport drives both clips at the offset
- **Unlocked**: tap a clip to make it active (white outline; the other dims); the transport
  drives only that clip — step it to dial in the offset, then lock
- Lock state and offset are **remembered per pair of clips** (`CompareSyncStore`, keyed on
  the two MediaStore ids); each clip keeps its own Pose / Draw toggles and `f 5` frame badge

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

## CI/CD and Verification

This project uses GitHub Actions for continuous integration. When working via Claude Code (web), you can verify your changes through the CI pipeline.

### GitHub Actions Workflows

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| `android-build.yml` | PR, push to main | Build APKs, run lint |
| `unit-tests.yml` | PR | Run unit tests |
| `instrumented-tests.yml` | PR | Run tests on emulator |
| `screenshot-tests.yml` | PR | Capture app screenshots |
| `distribute-apk.yml` | Push to main | Create release with QR code |

### Verifying Your Changes

After creating a PR, check the CI status:

```bash
# Check all PR checks
gh pr checks

# Watch until all checks complete
gh pr checks --watch

# View specific workflow run
gh run list --workflow=android-build.yml
gh run view <run-id> --log
```

### Downloading Artifacts

```bash
# List recent workflow runs
gh run list

# Download APK from a run
gh run download <run-id> -n app-debug

# Download screenshots
gh run download <run-id> -n app-screenshots

# Download test results
gh run download <run-id> -n unit-test-results
```

### Triggering Workflows Manually

```bash
# Trigger a new distribution build
gh workflow run distribute-apk.yml

# Trigger screenshot capture
gh workflow run screenshot-tests.yml
```

### Reading Test Failures

When tests fail:
1. Check `gh pr checks` to see which workflow failed
2. Use `gh run view <run-id> --log` to see detailed output
3. Download test reports: `gh run download <run-id> -n unit-test-results`

### Human Testing

When changes are merged to main:
1. The `distribute-apk.yml` workflow creates a GitHub Release
2. Download the QR code artifact from the workflow run
3. Scan the QR code on an Android device to download and install the APK