# MTB Analyzer User Guide

## Quick Start

1. **Grant Permissions**: Allow camera and audio recording when prompted
2. **Position Camera**: Mount phone with clear view of trail
3. **Enable Detection**: Toggle the detection switch (on by default)
4. **Start Recording**: Automatic when riders pass, or use manual button

## Main Features

### Automatic Recording
The app automatically detects and records riders using computer vision:
- Green indicator = Detection active, waiting for riders
- Pulsing animation = Rider detected, confidence building
- Red overlay = Recording in progress
- Videos save automatically to Movies/MTBAnalyzer

### Manual Recording
- Tap the "Test Record" button to start/stop recording manually
- Useful for testing or when automatic detection isn't needed

### Video Playback
Access recorded videos through the gallery icon:
- **Single Video**: Tap any video to play
- **Frame Navigation**: Use < > buttons or hold for continuous stepping
- **Scrubbing**: Hold finger on video and drag left/right
- **Pose Analysis**: Toggle "Show Pose" to see skeleton overlay

### Video Comparison
Compare two videos side-by-side:
1. Long press any video in gallery to enter compare mode
2. First video is automatically selected
3. Tap a second video to select it for comparison
4. Tap "Start Comparison" when both videos are selected
5. Use "Lock" button to synchronize playback
6. When locked, controls affect both videos together

Alternative method:
- Tap the compare button (FAB) to manually enter compare mode
- Select two videos by tapping them
- Tap "Start Comparison"

### Settings

#### Detection Settings
- **Enable/Disable**: Turn automatic detection on/off
- **Detection Method**: Choose algorithm (Pose is most accurate)
- **Sensitivity**: Higher = more sensitive (may get false positives)

#### Recording Settings
- **Duration**: How long to record (default 8 seconds)
- **Post-Rider Delay**: Extra recording after rider leaves

#### Remote Control
- Enable Bluetooth remote to use volume buttons:
  - Volume Up: Start/stop recording
  - Volume Down: Toggle detection on/off

## Tips & Best Practices

### Camera Placement
- Mount phone securely with clear trail view
- Avoid direct sunlight on lens
- Keep lens clean for best detection
- Landscape orientation recommended

### Detection Optimization
- **Good Lighting**: Works best in daylight
- **Clear Background**: Avoid busy backgrounds
- **Proper Distance**: Riders should fill 1/3 to 2/3 of frame
- **Stable Mount**: Minimize camera shake

### Battery Conservation
- Disable detection when not needed
- Lower screen brightness
- Close other apps
- Consider external battery pack for long sessions

### Video Management
- Videos are saved with timestamp names
- Gallery shows today's recording count
- Long recording sessions can use significant storage
- Transfer videos regularly to free space

## Troubleshooting

### No Detection
- Check detection is enabled (toggle switch)
- Try adjusting sensitivity in settings
- Ensure good lighting conditions
- Try different detection method

### Poor Detection
- Clean camera lens
- Check for obstructions
- Adjust camera angle
- Increase detection sensitivity

### Recording Issues
- Ensure sufficient storage space
- Check app has all permissions
- Try manual recording to test
- Restart app if issues persist

### Playback Problems
- If pose overlay is misaligned, try toggling it off/on
- For comparison sync issues, unlock and re-lock videos
- Restart app if videos won't play

## Advanced Features

### Frame-by-Frame Analysis
1. Open video in player
2. Pause at desired moment
3. Use frame buttons for precise navigation
4. Enable pose overlay to analyze technique

### Bluetooth Remote Setup
1. Pair Bluetooth device that sends volume keys
2. Enable "Bluetooth Remote" in settings
3. Test with volume buttons
4. Mount phone and use remote to control

### Performance Monitoring
For developers/advanced users:
1. Enable "Performance Monitor" in settings
2. Shows FPS, processing time, detection latency
3. Useful for optimizing setup

## Storage & Files
- Videos saved to: `/Movies/MTBAnalyzer/`
- Format: `MTB_YYYYMMDD_HHMMSS.mp4`
- Each video includes audio (if permitted)
- No automatic cleanup - manage storage manually