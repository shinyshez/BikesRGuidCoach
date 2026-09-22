#!/usr/bin/env bash
# Wait until the emulator's framework is genuinely usable, then unlock and kill animations.
#
# sys.boot_completed flips to 1 before the system services publish, so the emulator-runner's
# own unlock step can fire "input keyevent 82" at a device whose input service does not exist
# yet and kill the job before anything of ours has run:
#
#   Emulator booted.
#   adb shell input keyevent 82
#     java.lang.IllegalStateException: ServiceNotFoundException: No service published for: input
#
# Waiting on the services we actually use closes that window. Set disable-animations: false on
# the emulator-runner step and call this first from its script so the unlock happens here.
set -uo pipefail

timeout_s="${EMULATOR_READY_TIMEOUT:-180}"
deadline=$(( SECONDS + timeout_s ))

adb wait-for-device

await_service() {
  local name="$1"
  while [ "$SECONDS" -lt "$deadline" ]; do
    if adb shell service check "$name" 2>/dev/null | tr -d '\r' | grep -q "Service ${name}: found"; then
      return 0
    fi
    sleep 2
  done
  echo "Timed out after ${timeout_s}s waiting for the '${name}' service to publish" >&2
  return 1
}

while [ "$SECONDS" -lt "$deadline" ]; do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done

await_service input
await_service package
await_service activity

# Now safe: the unlock the runner would otherwise have done too early.
adb shell input keyevent 82 || true
adb shell settings put global window_animation_scale 0.0 || true
adb shell settings put global transition_animation_scale 0.0 || true
adb shell settings put global animator_duration_scale 0.0 || true

echo "Emulator framework is up."
