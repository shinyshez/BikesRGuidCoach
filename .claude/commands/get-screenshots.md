# Get Screenshots

## Local (seconds, not minutes)

```bash
source scripts/android-env.sh
scripts/emulator.sh start
./gradlew assembleDebug
bash scripts/capture-screenshots.sh screenshots
```

Then open the PNGs in `./screenshots/` (use the Read tool on each) and check they show
the intended screen — the script only asserts that each tap target existed.

## CI

Download and review app screenshots captured by CI for visual verification.

## Find Screenshot Runs

```bash
# List recent screenshot workflow runs
gh run list --workflow=screenshot-tests.yml --limit 5
```

## Download Screenshots

```bash
# Get run ID from the list above, then download:
gh run download <run-id> -n app-screenshots

# Screenshots will be saved to ./app-screenshots/
```

## Trigger New Screenshot Capture

```bash
# Manually trigger screenshot capture
gh workflow run screenshot-tests.yml
```

## What Screenshots Are Captured

The workflow runs `scripts/capture-screenshots.sh` on an API 29 emulator. It
navigates by view `content-desc`/`text` (via `uiautomator dump`), not fixed
coordinates, and captures:

1. `01_main_screen.png` - Main recording screen
2. `02_gallery.png` - Gallery view
3. `03_settings.png` - Settings screen
4. `04_zoom.png` - Zoom test screen
5. `05_main_final.png` - Main screen after returning
6. `06_settings_developer.png` - Settings scrolled to the Developer section
7. `07_detection_tuning.png` - Detection Tuning screen

Alongside the PNGs: `ui_hierarchy.xml`, `settings_ui_hierarchy.xml`,
`logcat_errors.txt` and `logcat_activity.txt` for debugging. To add a screen,
edit the script and use `tap_by_attr content-desc "<label>"` or
`tap_by_attr text "<label>"`.

## Review Process

1. Download screenshots from the PR's workflow run
2. Compare with previous version to verify visual changes
3. Check for layout issues, missing elements, or UI regressions
