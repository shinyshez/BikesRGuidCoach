#!/usr/bin/env bash
# Drive the app on a connected emulator/device and capture screenshots of each
# screen into ./screenshots. Used by .github/workflows/screenshot-tests.yml.
#
# Requires: adb on PATH, a booted device, app/build/outputs/apk/debug/app-debug.apk.
# Usage:    scripts/capture-screenshots.sh [output-dir]
set -euo pipefail

PKG=com.mtbanalyzer
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=${1:-screenshots}
mkdir -p "$OUT"

# tap_by_attr <attr> <value>: dump the UI hierarchy, find the node whose <attr>
# equals <value> (e.g. text="Detection Tuning" or content-desc="Gallery") and tap
# its centre. Returns non-zero if the node is not on screen.
tap_by_attr() {
  local attr="$1" value="$2"
  adb shell uiautomator dump /sdcard/ui_dump.xml > /dev/null
  adb pull /sdcard/ui_dump.xml /tmp/ui_dump.xml > /dev/null
  local bounds
  bounds=$(grep -o "<node[^>]*${attr}=\"${value}\"[^>]*>" /tmp/ui_dump.xml \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | grep -oE '[0-9]+' | tr '\n' ' ')
  if [ -z "$bounds" ]; then
    echo "ERROR: no node with ${attr}=\"${value}\" found on screen" >&2
    return 1
  fi
  # shellcheck disable=SC2086
  set -- $bounds
  local cx=$(( ($1 + $3) / 2 )) cy=$(( ($2 + $4) / 2 ))
  echo "Tapping ${attr}=\"${value}\" at ($cx, $cy)"
  adb shell input tap "$cx" "$cy"
}

# dump_ui <name>: save the current UI hierarchy alongside the screenshots for debugging.
dump_ui() {
  adb shell uiautomator dump /sdcard/ui_dump.xml > /dev/null || true
  adb pull /sdcard/ui_dump.xml "$OUT/$1.xml" > /dev/null || true
}

shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "Captured $1"; }
back() { adb shell input keyevent KEYCODE_BACK; sleep 2; }

# --- Install and launch ---------------------------------------------------------
adb uninstall "$PKG" > /dev/null 2>&1 || true
adb install "$APK"
for perm in CAMERA RECORD_AUDIO WRITE_EXTERNAL_STORAGE READ_EXTERNAL_STORAGE; do
  adb shell pm grant "$PKG" "android.permission.$perm" > /dev/null 2>&1 || true
done
sleep 2

adb logcat -c
adb shell am start -n "$PKG/.MainActivity"
sleep 8

# --- Main screen ----------------------------------------------------------------
shot 01_main_screen
dump_ui ui_hierarchy

# --- Gallery ----------------------------------------------------------------------
tap_by_attr content-desc Gallery
sleep 3
shot 02_gallery
back

# --- Settings ---------------------------------------------------------------------
tap_by_attr content-desc Settings
sleep 3
shot 03_settings
back

# --- Zoom test --------------------------------------------------------------------
tap_by_attr content-desc "Zoom Test"
sleep 3
shot 04_zoom
back
shot 05_main_final

# --- Settings -> Developer -> Detection Tuning -----------------------------------
tap_by_attr content-desc Settings
sleep 3
for _ in 1 2 3 4; do adb shell input swipe 160 500 160 200 300; sleep 1; done
dump_ui settings_ui_hierarchy
shot 06_settings_developer

tap_by_attr text "Detection Tuning"
sleep 5
shot 07_detection_tuning

# --- Logs ---------------------------------------------------------------------------
adb logcat -d -t 200 '*:E' > "$OUT/logcat_errors.txt" || true
adb logcat -d -t 100 DetectionTuning:* ActivityManager:* AndroidRuntime:* > "$OUT/logcat_activity.txt" || true

echo "Captured screenshots:"
ls -la "$OUT"
