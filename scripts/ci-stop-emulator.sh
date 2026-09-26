#!/usr/bin/env bash
# Put the emulator down ourselves, before the runner's own teardown tries to.
#
# The runner issues "adb emu kill", waits ANDROID_EMULATOR_WAIT_TIME_BEFORE_KILL seconds,
# then kills the process. Twice it has instead sat there for 26 minutes until the 30-minute
# job timeout cancelled the job — minutes after the tests had already passed:
#
#   11:50:15  BUILD SUCCESSFUL in 1m 56s
#   11:50:16  Wait for emulator (pid 2539) 5 seconds to shutdown gracefully before kill
#   12:16:11  ##[error]The operation was canceled.
#
# Shortening that wait from 20s to 5s changed the log line and nothing else, so the hang is
# after the wait rather than in it. Leaving its teardown nothing to wait on is the next
# lever: make sure the process is genuinely gone first.
#
# Killing qemu is not always enough. After the AVD snapshot step the emulator exited
# cleanly and the runner still hung until the job was cancelled; its orphan cleanup then
# named what was left: crashpad_handler and adb. Both are children the emulator spawned
# (it starts the adb server itself when none is running, which it isn't in that step),
# and both inherit its stdout/stderr, which the runner waits on to close before the step
# can finish. So once qemu is gone, those go too.
#
# Always exits 0 — the caller has already captured the test exit code.
set -uo pipefail

release_output_pipes() {
  adb kill-server >/dev/null 2>&1 || true
  pkill -f 'crashpad_handler' 2>/dev/null || true
  sleep 1
  pkill -9 -f 'crashpad_handler' 2>/dev/null || true
}

adb emu kill >/dev/null 2>&1 || true

for _ in $(seq 1 15); do
  if ! pgrep -f 'qemu-system' >/dev/null 2>&1; then
    echo "Emulator exited cleanly."
    release_output_pipes
    exit 0
  fi
  sleep 1
done

echo "Emulator still alive 15s after 'emu kill'; forcing it down." >&2
pkill -9 -f 'qemu-system' 2>/dev/null || true
sleep 2
if pgrep -f 'qemu-system' >/dev/null 2>&1; then
  echo "Warning: a qemu-system process survived SIGKILL." >&2
fi
release_output_pipes
exit 0
