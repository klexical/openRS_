#!/usr/bin/env bash
# launch-check.sh <apk> <wait seconds> — the nightly's launch check on a booted emulator (#363).
# It guards launch only: the release APK installs, starts from cold and is still the same process after the wait, and
# the log since just before the start has no verifier rejection, fatal exception or ANR of the app. Uses adb only.
# The Play Store is disabled first: on a fresh Play-services image it updates GMS and the Maps loader kills the app
# (.claude/rules/build-release.md). The log is cleared before the start, or the check would not cover the launch.
# Every adb call runs under `timeout 60` and has a step name: a call that fails or does not answer ends the check with
# that name and whether adb still sees the emulator. Every failure, the check's own or not, prints `adb devices`, the
# emulator processes and the last 60 lines of start-emulator.sh's emulator.log (the working directory both steps share).
set -euo pipefail
PKG=com.openrs.dash; ACTIVITY=com.openrs.dash.ui.MainActivity
ADB_LIMIT_S=60
EMULATOR_LOG="$PWD/emulator.log"
STEP="the argument checks"; REPORTED=0; TMP=""

diagnostics() {
  echo "--- adb devices"
  timeout 10 adb devices 2>&1 || echo "(adb devices failed)"
  echo "--- emulator processes"
  pgrep -a -f -- 'qemu-system|emulator/emulator' || echo "(no emulator process)"
  echo "--- the last 60 lines of $EMULATOR_LOG"
  if [ -f "$EMULATOR_LOG" ]; then tail -n 60 "$EMULATOR_LOG"; else echo "(no emulator.log in $PWD)"; fi
}
fail() { echo "::error::$*"; REPORTED=1; diagnostics; exit 1; }
finish() {
  local status=$?
  if [ "$status" -ne 0 ] && [ "$REPORTED" -eq 0 ]; then
    echo "::error::the launch check stopped during $STEP (exit $status) without a message of its own"
    diagnostics
  fi
  [ -z "$TMP" ] || rm -rf "$TMP"
}
trap finish EXIT

# call <step> <adb args...>: adb under the time limit. Its stdout (CR removed) is in OUT; a failed call or one that does
# not answer fails the check, named by its step.
call() {
  STEP="$1"; shift
  local status=0 detail err state
  timeout "$ADB_LIMIT_S" adb "$@" > "$TMP/out" 2> "$TMP/err" || status=$?
  OUT=$(tr -d '\r' < "$TMP/out")
  [ "$status" -ne 0 ] || return 0
  [ -z "$OUT" ] || echo "$OUT"
  err=$(tr -d '\r' < "$TMP/err" | tr '\n' ' ')
  if [ "$status" -eq 124 ]; then detail="did not answer within $ADB_LIMIT_S s"
  else detail="failed (exit $status: ${err:0:300})"; fi
  state=$(timeout 10 adb get-state 2>&1 | tr -d '\r' | tr '\n' ' ' || true)
  if [ "${state% }" = device ]; then fail "$STEP: adb $* $detail; adb still sees the emulator"
  else fail "$STEP: adb $* $detail; the emulator is no longer connected (adb get-state: ${state% })"; fi
}

[ $# -eq 2 ] || fail "usage: launch-check.sh <apk> <wait seconds>"
APK="$1"; WAIT="$2"
[ -f "$APK" ] || fail "APK not found: $APK"
[[ "$WAIT" =~ ^[0-9]+$ ]] || fail "wait '$WAIT' is not a number of seconds"
STEP="setting up"; TMP=$(mktemp -d "${TMPDIR:-/tmp}/launch-check.XXXXXX")

timeout "$ADB_LIMIT_S" adb shell pm disable-user --user 0 com.android.vending >/dev/null 2>&1 \
  || echo "Play Store not present on this image"
call "installing the APK" install -r "$APK"; echo "$OUT"
call "clearing the log" logcat -c
call "the cold start" shell am start -S -W -n "$PKG/$ACTIVITY"; echo "$OUT"
TOP=$(sed -n 's/^Activity: //p' <<<"$OUT")
case "$TOP" in
  ""|"$PKG"/*) ;;
  *) echo "Note: on top after the start: $TOP (the app's activity is behind it; a fresh install asks for its permissions)" ;;
esac
# `|| true` on the device: pidof exits 1 when nothing matches, so adb's status is then about the connection only
call "the process check after the start" shell "pidof $PKG || true"; PID1="$OUT"
[ -n "$PID1" ] || fail "$PKG is not running after the start"
STEP="the ${WAIT} s wait"; sleep "$WAIT"
call "the process check after the wait" shell "pidof $PKG || true"; PID2="$OUT"
[ -n "$PID2" ] || fail "$PKG is gone ${WAIT} s after the start (process $PID1)"
[ "$PID2" = "$PID1" ] || fail "$PKG restarted: process $PID1 at the start, $PID2 after ${WAIT} s"

call "reading the log" logcat -d; LOG="$OUT"
V=$(grep -c 'Verifier rejected' <<<"$LOG" || true)
F=$(grep -c 'FATAL EXCEPTION' <<<"$LOG" || true)
A=$(grep -c "ANR in $PKG" <<<"$LOG" || true)
[ "$V$F$A" = 000 ] || fail "after the start: Verifier rejected $V, FATAL EXCEPTION $F, ANR in $PKG $A"
echo "Launch check OK: $PKG process $PID1 running ${WAIT} s after a cold start; Verifier rejected 0, FATAL EXCEPTION 0, ANR 0"
