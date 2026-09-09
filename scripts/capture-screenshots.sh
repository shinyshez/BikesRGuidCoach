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

SCREEN_SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
SCREEN_W=${SCREEN_SIZE%x*}
SCREEN_H=${SCREEN_SIZE#*x}
echo "Screen size: ${SCREEN_W}x${SCREEN_H}"

# find_bounds <attr> <value>: print "x1 y1 x2 y2" of the first node in the current
# UI hierarchy whose <attr> equals <value>; prints nothing if not on screen.
find_bounds() {
  local attr="$1" value="$2"
  adb shell uiautomator dump /sdcard/ui_dump.xml > /dev/null
  adb pull /sdcard/ui_dump.xml /tmp/ui_dump.xml > /dev/null
  grep -o "<node[^>]*${attr}=\"${value}\"[^>]*>" /tmp/ui_dump.xml \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | grep -oE '[0-9]+' | tr '\n' ' ' || true
}

# swipe_up: scroll the current screen down by half its height, resolution-independent.
swipe_up() {
  adb shell input swipe $((SCREEN_W / 2)) $((SCREEN_H * 3 / 4)) $((SCREEN_W / 2)) $((SCREEN_H / 4)) 300
  sleep 1
}

# scroll_to <attr> <value> [max_scrolls]: scroll down until a node whose <attr>
# equals <value> is on screen (up to max_scrolls swipes). Prints its bounds
# "x1 y1 x2 y2"; returns 1 if it never appears.
scroll_to() {
  local attr="$1" value="$2" max_scrolls="${3:-0}" bounds i=0
  bounds=$(find_bounds "$attr" "$value")
  while [ -z "$bounds" ] && [ "$i" -lt "$max_scrolls" ]; do
    swipe_up; i=$((i + 1))
    bounds=$(find_bounds "$attr" "$value")
  done
  if [ -z "$bounds" ]; then
    echo "ERROR: no node with ${attr}=\"${value}\" found on screen after $i scrolls" >&2
    return 1
  fi
  # If the node has only just scrolled into view at the bottom edge, nudge once more so
  # the tap can't land on the navigation bar.
  # shellcheck disable=SC2086
  set -- $bounds
  if [ "$max_scrolls" -gt 0 ] && [ $(( ($2 + $4) / 2 )) -gt $(( SCREEN_H * 88 / 100 )) ]; then
    swipe_up
    bounds=$(find_bounds "$attr" "$value")
  fi
  echo "$bounds"
}

# tap_by_attr <attr> <value> [max_scrolls]: scroll_to the node (e.g.
# text="Detection Tuning" or content-desc="Gallery") and tap its centre.
tap_by_attr() {
  local attr="$1" value="$2" bounds
  bounds=$(scroll_to "$@")
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

# Always leave logs behind, even when a step fails (the workflow uploads $OUT on failure
# too), plus a screenshot of whatever was on screen at the time.
dump_logs() {
  local status=$?
  if [ $status -ne 0 ]; then
    echo "Capture failed with status $status; saving diagnostics"
    adb exec-out screencap -p > "$OUT/99_failure.png" 2>/dev/null || true
  fi
  adb logcat -d -t 200 '*:E' > "$OUT/logcat_errors.txt" 2>/dev/null || true
  adb logcat -d -t 100 DetectionTuning:* ActivityManager:* AndroidRuntime:* > "$OUT/logcat_activity.txt" 2>/dev/null || true
  # A dead system_server ("No service published for: input") shows up here
  adb logcat -d -b system -t 300 > "$OUT/logcat_system.txt" 2>/dev/null || true
  adb logcat -d -b crash > "$OUT/logcat_crash.txt" 2>/dev/null || true
}
trap dump_logs EXIT

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
# Scroll until the Developer section is visible, capture it, then open Detection Tuning
scroll_to text "Detection Tuning" 8 > /dev/null
dump_ui settings_ui_hierarchy
shot 06_settings_developer
tap_by_attr text "Detection Tuning"
sleep 5
shot 07_detection_tuning

echo "Captured screenshots:"
ls -la "$OUT"
