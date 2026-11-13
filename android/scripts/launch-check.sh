#!/usr/bin/env bash
# launch-check.sh <apk> <wait seconds> — the nightly's launch check on a booted emulator (#363; SPEC "What a run does" 5).
# It guards launch only: the release APK installs, starts from cold and is still the same process after the wait, and
# the log since just before the start has no verifier rejection, fatal exception or ANR of the app. Uses adb only.
# The Play Store is disabled first: on a fresh Play-services image it updates GMS and the Maps loader kills the app
# (.claude/rules/build-release.md). The log is cleared before the start, or the check would not cover the launch.
set -euo pipefail
fail() { echo "::error::$*"; exit 1; }
[ $# -eq 2 ] || fail "usage: launch-check.sh <apk> <wait seconds>"
APK="$1"; WAIT="$2"
PKG=com.openrs.dash; ACTIVITY=com.openrs.dash.ui.MainActivity
[ -f "$APK" ] || fail "APK not found: $APK"
[[ "$WAIT" =~ ^[0-9]+$ ]] || fail "wait '$WAIT' is not a number of seconds"

adb shell pm disable-user --user 0 com.android.vending >/dev/null 2>&1 || echo "Play Store not present on this image"
adb install -r "$APK" || fail "the APK did not install"
adb logcat -c
adb shell am start -S -W -n "$PKG/$ACTIVITY" || fail "the app did not start"
PID1=$(adb shell pidof "$PKG" | tr -d '\r')
[ -n "$PID1" ] || fail "$PKG is not running after the start"
sleep "$WAIT"
PID2=$(adb shell pidof "$PKG" | tr -d '\r')
[ -n "$PID2" ] || fail "$PKG is gone ${WAIT} s after the start (process $PID1)"
[ "$PID2" = "$PID1" ] || fail "$PKG restarted: process $PID1 at the start, $PID2 after ${WAIT} s"

LOG=$(adb logcat -d)
V=$(grep -c 'Verifier rejected' <<<"$LOG" || true)
F=$(grep -c 'FATAL EXCEPTION' <<<"$LOG" || true)
A=$(grep -c "ANR in $PKG" <<<"$LOG" || true)
[ "$V$F$A" = 000 ] || fail "after the start: Verifier rejected $V, FATAL EXCEPTION $F, ANR in $PKG $A"
echo "Launch check OK: $PKG process $PID1 running ${WAIT} s after a cold start; Verifier rejected 0, FATAL EXCEPTION 0, ANR 0"
