#!/usr/bin/env bash
# Manage the local headless test emulator.
#   scripts/emulator.sh create   # one-off: create the AVD
#   scripts/emulator.sh start    # boot headless and wait until ready
#   scripts/emulator.sh stop
#   scripts/emulator.sh status
#   scripts/emulator.sh install  # installDebug + grant runtime permissions (connected tests uninstall the app)
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=android-env.sh
source scripts/android-env.sh

AVD=${AVD:-mtb-test}
IMAGE=${IMAGE:-"system-images;android-34;google_apis;arm64-v8a"}

case "${1:-status}" in
  create)
    echo no | avdmanager create avd --force -n "$AVD" --package "$IMAGE" --device pixel_4
    printf 'hw.keyboard=yes\nhw.cpu.ncore=2\nhw.ramSize=2048\n' >> "$HOME/.android/avd/$AVD.avd/config.ini"
    echo "Created AVD $AVD"
    ;;
  start)
    if adb devices | grep -q "emulator-.*device$"; then echo "Emulator already running"; exit 0; fi
    nohup emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
      -camera-back none > /tmp/mtb-emulator.log 2>&1 &
    echo "Booting $AVD (log: /tmp/mtb-emulator.log)..."
    adb wait-for-device
    until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
    adb shell settings put global window_animation_scale 0
    adb shell settings put global transition_animation_scale 0
    adb shell settings put global animator_duration_scale 0
    echo "Emulator ready"
    ;;
  stop)
    adb emu kill 2>/dev/null || true
    echo "Stopped"
    ;;
  status)
    adb devices
    ;;
  install)
    ./gradlew installDebug --console=plain -q
    for perm in CAMERA RECORD_AUDIO READ_MEDIA_VIDEO; do
      adb shell pm grant com.mtbanalyzer "android.permission.$perm" 2>/dev/null || true
    done
    echo "Installed and granted permissions"
    ;;
  *) echo "usage: $0 {create|start|stop|status|install}" >&2; exit 2 ;;
esac
