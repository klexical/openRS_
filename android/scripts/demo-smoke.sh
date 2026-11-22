#!/usr/bin/env bash
# demo-smoke.sh <debug APK, or the folder holding exactly one> — the emulator smoke of a debug build in demo mode, on a
# booted emulator (start-emulator.sh), for .github/workflows/emulator-smoke.yml. Drives the app as a user does, through
# adb only: no screen file is touched and no adapter is needed (demo mode, .claude/rules/demo-mode.md).
#   1. The Play Store is disabled (fresh Play-services images kill the app, .claude/rules/build-release.md), the screen is
#      set to 1080x2400 at 420 dpi (about 411 x 914 dp), the APK is installed with its runtime permissions granted (-g: no
#      permission dialog covers the app), and the installed file's SHA-256 must equal the APK's.
#   2. The log is cleared BEFORE the cold start (`am start -S … --es demo driving`), so every log check covers the run.
#   3. Demo is paused (`--es demo at:2.5`) for the UI reads: `uiautomator dump` needs an idle screen, and live demo values
#      change many times a second. A What's New dialog is closed by its GOT IT button.
#   4. Each bottom-nav tab (DRIVE, PERF, THERMAL, TRIP, GARAGE) is tapped at the centre the UI dump gives for its node
#      ("<NAME> tab"), and the next dump must show that tab selected and the header's DEMO label.
#   5. On DRIVE, each demo scenario runs live for a while (idle, driving, warning, loss, cooldown, launch); on TRIP, each
#      sample state (`--es trippreview` history … export), then off.
#   6. After every step: the app's process is the one started (not gone, not restarted), and the log since the cold start
#      has no verifier rejection, fatal exception, ANR or native fatal signal of the app. The app's activity must be the
#      resumed one after the start and at the end.
# What it prints: step names, tab and scenario names, counts, coordinates, package names. Never a dump, a screenshot or
# the whole device log: a failure prints only the log's process and crash lines (the last 40), with any line naming a key
# left out and key-shaped values redacted, as launch-check.sh does. Nothing is written outside a temporary folder, which
# is removed. Every adb call runs under `timeout` with a step name. One line goes to $GITHUB_STEP_SUMMARY when set.
# It reads the UI text tree, not pixels: a node shown in a dump can still be covered (.claude/rules/ui-compose.md).
# SMOKE_ADB_LIMIT_S (60), SMOKE_SETTLE_S (10, after the cold start), SMOKE_STEP_S (3, after a tap or an intent) and
# SMOKE_FRAME_S (6, after a pause request) are for the tests (scripts/test-demo-smoke.sh).
set -euo pipefail
PKG=com.openrs.dash.debug; ACTIVITY=com.openrs.dash.ui.MainActivity; COMPONENT="$PKG/$ACTIVITY"
ADB_LIMIT_S="${SMOKE_ADB_LIMIT_S:-60}"; SETTLE_S="${SMOKE_SETTLE_S:-10}"; STEP_S="${SMOKE_STEP_S:-3}"
FRAME_S="${SMOKE_FRAME_S:-6}"   # after `at:2.5`: the replay runs from 0 in real time before it pauses
DUMP_TRIES=3
TABS="DRIVE PERF THERMAL TRIP GARAGE"
SCENARIOS="idle driving warning loss cooldown launch"
PREVIEWS="history selected recording paused summary compare replay export"
DUMP_PATH=/data/local/tmp/demo-smoke.xml
DIR="$(cd "$(dirname "$0")" && pwd)"
STEP="the argument checks"; PID1=""; CHECKS=0

summary() { [ -z "${GITHUB_STEP_SUMMARY-}" ] || echo "$*" >> "$GITHUB_STEP_SUMMARY"; }
# redact: the two key shapes an APK of this app can hold (launch-check.sh's rule): the Maps key, any appid= value, and a
# standalone run of exactly 32 hex characters. This job builds without keys; the filter stays for any build.
redact() {
  sed -E -e 's/AIza[0-9A-Za-z_-]{35,}/AIza<redacted>/g; s/appid=[^&[:space:]]+/appid=<redacted>/g' \
    -e ':a' -e 's/(^|[^0-9A-Fa-f])[0-9A-Fa-f]{32}([^0-9A-Fa-f]|$)/\1<redacted>\2/' -e 'ta'
}
crash_lines() {
  local out st=0
  echo "--- the device log's process and crash lines (the last 40)"
  out=$(timeout 10 adb logcat -d -b main,system,crash 2>/dev/null) || st=$?
  if [ "$st" -ne 0 ] && [ -z "$out" ]; then echo "(could not read: adb logcat, exit $st)"; return; fi
  out=$(grep -E 'AndroidRuntime|Fatal signal|Verifier rejected|ActivityManager: (Process|Killing|Start proc|ANR)|lowmemorykiller|lmkd' <<<"$out" \
    | grep -v -i 'key' | redact | tail -n 40 || true)
  if [ -z "$out" ]; then echo "(no matching lines)"; else printf '%s\n' "$out"; fi
  echo "--- end of the crash lines"
}
REPORTED=0
fail() {
  echo "::error::$*"; REPORTED=1
  crash_lines
  summary "Emulator smoke failed: $*"
  exit 1
}

[ $# -eq 1 ] || { echo "::error::usage: demo-smoke.sh <debug APK, or the folder holding exactly one>"; exit 1; }
APK="$1"
if [ -d "$APK" ]; then
  n=$(find "$APK" -maxdepth 1 -name '*.apk' | wc -l | tr -d ' ')
  [ "$n" = 1 ] || { echo "::error::$APK holds $n APK files, expected exactly one"; exit 1; }
  APK=$(find "$APK" -maxdepth 1 -name '*.apk')
fi
[ -f "$APK" ] || { echo "::error::no APK at $APK"; exit 1; }
command -v adb >/dev/null 2>&1 || { echo "::error::adb is not on PATH"; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "::error::python3 is not on PATH (the UI dump reader)"; exit 1; }
WORK=$(mktemp -d "${TMPDIR:-/tmp}/demo-smoke.XXXXXX")
on_exit() {
  local status=$?
  if [ "$status" -ne 0 ] && [ "$REPORTED" -eq 0 ]; then
    echo "::error::the smoke stopped during $STEP (exit $status) without a message of its own"
    crash_lines
    summary "Emulator smoke failed during $STEP (exit $status)"
  fi
  rm -rf "$WORK"
}
trap on_exit EXIT

# call [--private] <step> <adb args...>: adb under the time limit; its stdout (CR removed) is in OUT. A failed call or one
# that does not answer ends the smoke, named by its step; --private never prints what the call printed (the device log).
call() {
  local private=0 status=0 err args; if [ "$1" = --private ]; then private=1; shift; fi
  STEP="$1"; shift; args="$*"
  timeout "$ADB_LIMIT_S" adb "$@" > "$WORK/out" 2> "$WORK/err" || status=$?
  OUT=$(tr -d '\r' < "$WORK/out")
  [ "$status" -ne 0 ] || return 0
  [ -z "$OUT" ] || [ "$private" -eq 1 ] || echo "$OUT" | tail -n 20
  err=$(tr -d '\r' < "$WORK/err" | tr '\n' ' ')
  if [ "$status" -eq 124 ]; then fail "$STEP: adb ${args:0:160} did not answer within $ADB_LIMIT_S s"
  else fail "$STEP: adb ${args:0:160} failed (exit $status: ${err:0:300})"; fi
}
# fact <key>: a value from the last UI read (demo-smoke-ui.py's facts)
fact() { sed -n "s/^$1=//p" "$WORK/facts" | head -n 1 || true; }
# intent <extra> <value>: hand a debug extra to the running app (onNewIntent needs --activity-single-top, CLAUDE.md)
intent() {
  call "sending --es $1 $2" shell am start --activity-single-top -n "$COMPONENT" --es "$1" "$2"
}
pause() { STEP="the wait $1"; sleep "$2"; }
# alive <where>: the app's process is the one the cold start made. `|| true` on the device: pidof exits 1 when nothing
# matches, so adb's status is about the connection only (.claude/rules/build-release.md)
alive() {
  call "the process check $1" shell "pidof $PKG || true"
  [ -n "$OUT" ] || fail "$PKG is gone $1 (process $PID1 at the start)"
  [ "$OUT" = "$PID1" ] || fail "$PKG restarted $1: process $PID1 at the start, $OUT now"
}
# log_check <where>: the log since the cold start has no verifier rejection, fatal exception, ANR or native fatal signal
log_check() {
  local v f a s
  call --private "reading the log $1" logcat -d
  v=$(grep -c 'Verifier rejected' <<<"$OUT" || true)
  f=$(grep -c 'FATAL EXCEPTION' <<<"$OUT" || true)
  a=$(grep -c "ANR in $PKG" <<<"$OUT" || true)
  s=$(grep 'Fatal signal' <<<"$OUT" | grep -c "$PKG" || true)
  [ "$v$f$a$s" = 0000 ] || fail "$1: Verifier rejected $v, FATAL EXCEPTION $f, ANR in $PKG $a, Fatal signal ($PKG) $s"
  CHECKS=$((CHECKS + 1))
}
# resumed <where>: the app's activity is the resumed one (a dialog of another app, a permission request or the launcher
# on top fails); names the component found, and nothing else of the dump
resumed() {
  local found
  call "reading the resumed activity $1" shell "dumpsys activity activities | grep -E 'ResumedActivity' || true"
  if grep -q -F -e "$COMPONENT" -e "$PKG/.ui.MainActivity" <<<"$OUT"; then return 0; fi
  found=$(grep -o -E '[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+' <<<"$OUT" | sort -u | tr '\n' ' ' || true)
  fail "$1: the resumed activity is not $COMPONENT (found: ${found:-none})"
}
# ui <where>: one UI read into $WORK/facts. The dump file is removed first, so a dump that found no idle state ("could
# not get idle state", no file) reads as empty and is tried again, up to DUMP_TRIES times
ui() {
  local i st msg bytes
  for i in $(seq 1 "$DUMP_TRIES"); do
    # The first line is uiautomator's own message, cut to letters, spaces and path characters ("UI hierchary dumped to:
    # …", "ERROR: could not get idle state."); the dump follows it
    call --private "the UI read $1 (try $i of $DUMP_TRIES)" exec-out \
      "rm -f $DUMP_PATH; echo \"UIA:\$(uiautomator dump $DUMP_PATH 2>&1 | tr -cd 'A-Za-z :./_-' | cut -c1-120)\"; cat $DUMP_PATH 2>/dev/null; true"
    msg=$(sed -n '1s/^UIA://p' <<<"$OUT" | tr -cd 'A-Za-z :./_-')
    sed '1{/^UIA:/d;}' <<<"$OUT" > "$WORK/dump.xml"
    bytes=$(wc -c < "$WORK/dump.xml" | tr -d ' ')
    st=0; python3 -I "$DIR/demo-smoke-ui.py" "$WORK/dump.xml" "$PKG" > "$WORK/facts" 2> "$WORK/ui-err" || st=$?
    if [ "$st" -eq 0 ] && [ "$(fact ours)" != 0 ]; then return 0; fi
    echo "UI read $1, try $i of $DUMP_TRIES: uiautomator said '${msg:-nothing}'; $bytes bytes; $(tr -d '\n' < "$WORK/ui-err" | cut -c1-200)"
    [ "$i" -eq "$DUMP_TRIES" ] || pause "before UI read $((i + 1))" "$STEP_S"
  done
  if [ "$st" -ne 0 ]; then fail "$1: no readable UI dump in $DUMP_TRIES tries (uiautomator said '${msg:-nothing}'; $(tr -d '\n' < "$WORK/ui-err" | cut -c1-200))"; fi
  fail "$1: the UI dump holds no node of $PKG in $DUMP_TRIES tries (other packages: $(fact others))"
}
tap() {   # tap <where> "<x> <y>"
  local x=${2% *} y=${2#* }
  [[ "$x" =~ ^[0-9]+$ && "$y" =~ ^[0-9]+$ ]] || fail "$1: no position to tap ('$2')"
  call "the tap $1 ($x, $y)" shell input tap "$x" "$y"
}
need_demo() { [ "$(fact demo)" = 1 ] || fail "$1: no DEMO label in the UI dump (the header must say DEMO in demo mode)"; }

# 1. Set up, install, prove the installed file
STEP="setting up"
timeout "$ADB_LIMIT_S" adb shell pm disable-user --user 0 com.android.vending >/dev/null 2>&1 \
  || echo "Play Store not disabled (not on this image, or adb failed)"
call "setting the screen size" shell wm size 1080x2400
call "setting the screen density" shell wm density 420
call "installing the APK" install -r -g "$APK"; echo "$OUT" | tail -n 1
call "finding the installed APK" shell pm path "$PKG"
DEVICE_APK=$(sed -n 's/^package://p' <<<"$OUT" | head -n 1 || true)
[ -n "$DEVICE_APK" ] || fail "pm path names no installed APK for $PKG"
call "hashing the installed APK" shell sha256sum "$DEVICE_APK"
want=$(sha256sum "$APK" | cut -d' ' -f1); got=$(cut -d' ' -f1 <<<"$OUT")
[ "$got" = "$want" ] || fail "the installed APK's SHA-256 is ${got:-unknown}, the APK's is $want"
echo "Installed $(basename "$APK"), SHA-256 $want (the device's copy matches)"

# 2. Cold start into demo
call "clearing the log" logcat -c
call "the cold start" shell am start -S -W -n "$COMPONENT" --es demo driving
pause "after the cold start" "$SETTLE_S"
call "the process check after the start" shell "pidof $PKG || true"; PID1="$OUT"
[ -n "$PID1" ] || fail "$PKG is not running ${SETTLE_S} s after the cold start"
resumed "after the cold start"
log_check "after the cold start"

# 3. Pause for the UI reads; close What's New
intent demo at:2.5
pause "for the paused frame" "$FRAME_S"
ui "after the cold start"
if [ "$(fact whatsnew)" != none ]; then
  tap "on What's New's GOT IT" "$(fact whatsnew)"
  pause "after closing What's New" "$STEP_S"
  ui "after closing What's New"
  [ "$(fact whatsnew)" = none ] || fail "What's New is still shown after its GOT IT was tapped"
  echo "What's New closed"
fi
need_demo "after the cold start"

# 4. Every tab
for t in $TABS; do
  xy=$(fact "tab.$t"); [ -n "$xy" ] || fail "no $t tab in the UI dump (tabs found: $(sed -n 's/^tab\.\([A-Z]*\)=.*/\1/p' "$WORK/facts" | tr '\n' ' '))"
  tap "on the $t tab" "$xy"
  pause "on $t" "$STEP_S"
  ui "on $t"
  [ "$(fact selected)" = "$t" ] || fail "the $t tab is not selected after its tap (selected: $(fact selected))"
  need_demo "on $t"
  alive "on $t"; log_check "on $t"
  echo "Tab $t: selected, DEMO shown, process $PID1, log clean"
done
cp "$WORK/facts" "$WORK/tab-facts"

# 5. Scenarios on DRIVE, sample drives on TRIP (live: no UI reads here)
cp "$WORK/tab-facts" "$WORK/facts"; tap "on the DRIVE tab" "$(fact tab.DRIVE)"
for s in $SCENARIOS; do
  intent demo "$s"; pause "in demo $s" "$((STEP_S * 2))"
  alive "in demo $s"; log_check "in demo $s"
  echo "Demo $s: process $PID1, log clean"
done
cp "$WORK/tab-facts" "$WORK/facts"; tap "on the TRIP tab" "$(fact tab.TRIP)"
for p in $PREVIEWS; do
  intent trippreview "$p"; pause "in trippreview $p" "$((STEP_S * 2))"
  alive "in trippreview $p"; log_check "in trippreview $p"
  echo "Trip preview $p: process $PID1, log clean"
done
intent trippreview off

# 6. The end: paused again, read once more
intent demo at:2.5
pause "for the last paused frame" "$FRAME_S"
ui "at the end"
need_demo "at the end"
resumed "at the end"
alive "at the end"; log_check "at the end"
n_tabs=$(wc -w <<<"$TABS" | tr -d ' '); n_s=$(wc -w <<<"$SCENARIOS" | tr -d ' '); n_p=$(wc -w <<<"$PREVIEWS" | tr -d ' ')
echo "Emulator smoke OK: $n_tabs tabs, $n_s demo scenarios, $n_p trip previews; process $PID1 throughout; $CHECKS log checks clean"
summary "Emulator smoke passed: $n_tabs tabs, $n_s demo scenarios, $n_p trip previews, one process throughout, $CHECKS log checks with no verifier rejection, fatal exception, ANR or fatal signal (demo mode, emulator, no adapter)"
