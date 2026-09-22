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
# Always exits 0 — the caller has already captured the test exit code.
set -uo pipefail

adb emu kill >/dev/null 2>&1 || true

for _ in $(seq 1 15); do
  if ! pgrep -f 'qemu-system' >/dev/null 2>&1; then
    echo "Emulator exited cleanly."
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
exit 0
