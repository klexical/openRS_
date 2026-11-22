#!/usr/bin/env bash
# test-demo-smoke.sh — checks demo-smoke.sh, the label-started emulator smoke (.github/workflows/emulator-smoke.yml): the
# Play Store is disabled first, the APK installs with its permissions granted and the device's copy must hash like the
# APK, the log is cleared BEFORE the cold start into demo, demo is paused before each UI read, What's New is closed by
# its GOT IT, each tab is tapped at the centre its dump node gives and must then read selected with the DEMO label, every
# demo scenario and trip preview is sent, and the run fails on a restarted or missing process, a fatal exception in the
# log, another app's resumed activity, a missing DEMO label, a tab that does not select, a dump that never reads, a hash
# mismatch or an adb call that does not answer, each with a message naming the step. A failure's crash lines drop any
# line naming a key and redact key-shaped values. A fake adb answers from generated dumps (system bar nodes included, as
# `uiautomator dump` gives every window) and records the order of calls. Also checks demo-smoke-ui.py on its own.
# Run from anywhere: bash android/scripts/test-demo-smoke.sh
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$DIR/demo-smoke.sh"; UI="$DIR/demo-smoke-ui.py"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/demo-smoke-test.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
failures=0; ran=0
mkdir -p "$WORK/bin" "$WORK/apk" "$WORK/two"
APK="$WORK/apk/openRS_v2.2.7-dev+0000000-staging-debug.apk"; printf 'not really an apk\n' > "$APK"
# Key-shaped values are built here, so no key-shaped literal is in this file (as test-launch-check.sh does)
MAPS_VALUE="AIza$(printf 'Q%.0s' $(seq 1 35))"; HEX_VALUE="$(printf '0f%.0s' $(seq 1 16))"
printf 'a\n' > "$WORK/two/a.apk"; printf 'b\n' > "$WORK/two/b.apk"

# ── Fake adb. State in $WORK/state: the selected tab, whether What's New shows, counters per call kind. Knobs:
#    FAKE_WHATSNEW=1 shows the dialog until GOT IT is tapped; FAKE_NO_SELECT=<TAB> ignores taps on that tab;
#    FAKE_RESTART_AT=<n> makes pidof answer another process from its n-th call; FAKE_GONE_AT=<n> answers nothing from then;
#    FAKE_FATAL_AT=<n> puts a fatal exception in the log from the n-th `logcat -d`; FAKE_FOREIGN=1 puts another app's
#    activity on top; FAKE_IDLE_FAILS=<n> makes the first n dumps find no idle state; FAKE_NO_DEMO=1 drops the DEMO
#    label; FAKE_DEMO_LABEL=<text> replaces it; FAKE_BAD_HASH=1 answers another SHA-256; FAKE_HANG=<kind> never answers
#    that call kind (install, start, logcat-d, dump). ──
cat > "$WORK/bin/adb" <<'STUB'
#!/usr/bin/env bash
S="$FAKE_DIR/state"
echo "$*" >> "$FAKE_DIR/calls"
count() { local n; n=$(cat "$S/$1" 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" > "$S/$1"; echo "$n"; }
hang() { [ "${FAKE_HANG-}" = "$1" ] && exec sleep 30; return 0; }
TABS=(DRIVE PERF THERMAL TRIP GARAGE)
dump() {
  local tab demo i l r sel
  tab=$(cat "$S/tab"); demo=${FAKE_DEMO_LABEL-DEMO}
  echo "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
  echo "<node index=\"0\" text=\"\" package=\"com.android.systemui\" content-desc=\"\" selected=\"false\" bounds=\"[0,0][1080,63]\"><node index=\"0\" text=\"12:00\" package=\"com.android.systemui\" content-desc=\"\" selected=\"false\" bounds=\"[40,0][160,63]\" /></node>"
  echo "<node index=\"1\" text=\"\" package=\"com.openrs.dash.debug\" content-desc=\"\" selected=\"false\" bounds=\"[0,0][1080,2400]\">"
  [ -n "${FAKE_NO_DEMO-}" ] || echo "<node index=\"0\" text=\"$demo\" package=\"com.openrs.dash.debug\" content-desc=\"\" selected=\"false\" bounds=\"[800,70][960,170]\" />"
  for i in 0 1 2 3 4; do
    l=$((i * 216)); r=$((l + 216)); sel=false; [ "${TABS[$i]}" = "$tab" ] && sel=true
    echo "<node index=\"$((i + 1))\" text=\"\" package=\"com.openrs.dash.debug\" content-desc=\"${TABS[$i]} tab\" selected=\"$sel\" bounds=\"[$l,2252][$r,2348]\" />"
  done
  echo "</node>"
  if [ -f "$S/whatsnew" ]; then
    echo "<node index=\"2\" text=\"\" package=\"com.openrs.dash.debug\" content-desc=\"\" selected=\"false\" bounds=\"[86,600][994,1800]\"><node index=\"0\" text=\"GOT IT\" package=\"com.openrs.dash.debug\" content-desc=\"\" selected=\"false\" bounds=\"[400,1500][680,1600]\" /></node>"
  fi
  echo "</hierarchy>"
}
case "$*" in
  "shell pm disable-user"*)   : ;;
  "shell wm "*)               : ;;
  "install -r -g "*)          hang install; echo "Performing Streamed Install"; echo "Success" ;;
  "shell pm path com.openrs.dash.debug") echo "package:/data/app/~~fake/com.openrs.dash.debug-1/base.apk" ;;
  "shell sha256sum "*)        if [ -n "${FAKE_BAD_HASH-}" ]; then h=0000000000000000000000000000000000000000000000000000000000000000
                              else h=$(sha256sum "$FAKE_APK" | cut -d' ' -f1); fi
                              printf '%s  %s\r\n' "$h" "${*#shell sha256sum }" ;;
  "logcat -c")                : > "$S/cleared" ;;
  "logcat -d")                hang logcat-d; n=$(count logcat); [ -f "$S/cleared" ] || echo "E AndroidRuntime: FATAL EXCEPTION: before the clear"
                              echo "I ActivityManager: Start proc 4242:com.openrs.dash.debug/u0a190 for top-activity"
                              if [ -n "${FAKE_FATAL_AT-}" ] && [ "$n" -ge "$FAKE_FATAL_AT" ]; then
                                echo "E AndroidRuntime: FATAL EXCEPTION: main"; echo "E AndroidRuntime: Process: com.openrs.dash.debug, PID: 4242"
                              fi ;;
  "logcat -d -b main,system,crash")
                              echo "I ActivityManager: Start proc 4242:com.openrs.dash.debug/u0a190 for top-activity"
                              echo "E AndroidRuntime: FATAL EXCEPTION: main"
                              echo "E AndroidRuntime: java.lang.IllegalStateException: maps key $FAKE_MAPS_VALUE"
                              echo "E AndroidRuntime: Caused by: denied for $FAKE_MAPS_VALUE"
                              echo "I ActivityManager: Process com.openrs.dash.debug (pid 4242) has died: id $FAKE_HEX_VALUE"
                              echo "I Zygote: unrelated" ;;
  "shell am start -S -W "*)   hang start; [ -f "$S/cleared" ] || : > "$S/started-before-clear"
                              [ -z "${FAKE_WHATSNEW-}" ] || : > "$S/whatsnew"
                              printf 'Status: ok\r\nActivity: com.openrs.dash.debug/com.openrs.dash.ui.MainActivity\r\nComplete\r\n' ;;
  "shell am start --activity-single-top "*) echo "${@: -1}" >> "$S/extras"
                              echo "Warning: Activity not started, intent has been delivered to currently running top-most instance." ;;
  "shell pidof com.openrs.dash.debug || true")
                              n=$(count pidof)
                              if [ -n "${FAKE_GONE_AT-}" ] && [ "$n" -ge "$FAKE_GONE_AT" ]; then :
                              elif [ -n "${FAKE_RESTART_AT-}" ] && [ "$n" -ge "$FAKE_RESTART_AT" ]; then printf '5151\r\n'
                              else printf '4242\r\n'; fi ;;
  "shell dumpsys activity activities | grep -E 'ResumedActivity' || true")
                              if [ -n "${FAKE_FOREIGN-}" ]; then
                                printf '  topResumedActivity=ActivityRecord{1a2b u0 com.google.android.permissioncontroller/com.android.permissioncontroller.permission.ui.GrantPermissionsActivity t9}\r\n'
                              else printf '  topResumedActivity=ActivityRecord{1a2b u0 com.openrs.dash.debug/com.openrs.dash.ui.MainActivity t9}\r\n'; fi ;;
  "exec-out rm -f /data/local/tmp/demo-smoke.xml; uiautomator dump"*)
                              hang dump; n=$(count dump)
                              [ "$n" -gt "${FAKE_IDLE_FAILS:-0}" ] || exit 0      # no idle state: no file, nothing to cat
                              dump ;;
  "shell input tap "*)        set -- $*; x=$4; y=$5; echo "$x $y" >> "$S/taps"
                              if [ "$y" -ge 2252 ] && [ "$y" -le 2348 ]; then
                                t=${TABS[$((x / 216))]}; [ "${FAKE_NO_SELECT-}" = "$t" ] || echo "$t" > "$S/tab"
                              elif [ -f "$S/whatsnew" ] && [ "$x" -ge 400 ] && [ "$x" -le 680 ] && [ "$y" -ge 1500 ] && [ "$y" -le 1600 ]; then
                                rm -f "$S/whatsnew"
                              fi ;;
  *)                          echo "fake adb: unexpected call: $*" >&2; exit 99 ;;
esac
STUB
chmod +x "$WORK/bin/adb"

# run_case <name> <expected exit> <apk-or-folder|-> [VAR=value ...]: runs demo-smoke.sh against the fake; output in
# $WORK/out, calls in $WORK/calls, the step summary in $WORK/summary
run_case() {
  local name="$1" want="$2" arg="$3"; shift 3
  rm -rf "$WORK/state" "$WORK/calls" "$WORK/summary" "$WORK/tmp"; mkdir -p "$WORK/state" "$WORK/tmp"
  echo DRIVE > "$WORK/state/tab"; : > "$WORK/calls"
  local args=(); [ "$arg" = - ] || args=("$arg")
  env -i PATH="$WORK/bin:$PATH" HOME="$HOME" FAKE_DIR="$WORK" TMPDIR="$WORK/tmp" FAKE_APK="$APK" GITHUB_STEP_SUMMARY="$WORK/summary" \
    FAKE_MAPS_VALUE="$MAPS_VALUE" FAKE_HEX_VALUE="$HEX_VALUE" \
    SMOKE_SETTLE_S=0 SMOKE_STEP_S=0 SMOKE_FRAME_S=0 SMOKE_ADB_LIMIT_S=5 "$@" bash "$SCRIPT" "${args[@]}" > "$WORK/out" 2>&1
  GOT=$?
  ran=$((ran + 1)); CASE="$name"
  if [ "$GOT" -ne "$want" ]; then
    echo "FAIL $name — exit $GOT, expected $want"; sed 's/^/    /' "$WORK/out" | tail -n 15; failures=$((failures + 1)); return 1
  fi
  return 0
}
pass() { echo "PASS $CASE"; }
bad() { echo "FAIL $CASE — $*"; sed 's/^/    /' "$WORK/out" | tail -n 15; failures=$((failures + 1)); }
has() { grep -q -F -- "$1" "$WORK/out"; }
# expect <substring>...: every substring is in the output
expect() { local s; for s in "$@"; do has "$s" || { bad "no '$s' in the output"; return 1; }; done; pass; }
line_of() { grep -n -F -x -- "$1" "$WORK/calls" | head -n 1 | cut -d: -f1 || true; }
first_of() { grep -n -F -- "$1" "$WORK/calls" | head -n 1 | cut -d: -f1 || true; }

# ── The whole run ──
if run_case "a clean run passes: every tab, scenario and trip preview, one process, clean logs" 0 "$WORK/apk"; then
  why=""
  has "Emulator smoke OK: 5 tabs, 6 demo scenarios, 8 trip previews; process 4242 throughout" || why+="no OK line; "
  for t in DRIVE PERF THERMAL TRIP GARAGE; do has "Tab $t: selected, DEMO shown, process 4242, log clean" || why+="no $t line; "; done
  [ "$(first_of 'shell pm disable-user')" = 1 ] || why+="the Play Store is not disabled first; "
  c=$(line_of "logcat -c"); s=$(first_of "shell am start -S -W"); i=$(first_of "install -r -g")
  [ -n "$c" ] && [ -n "$s" ] && [ -n "$i" ] && [ "$i" -lt "$c" ] && [ "$c" -lt "$s" ] || why+="install ($i), log clear ($c), cold start ($s) out of order; "
  grep -q -F -- "--es demo driving" "$WORK/calls" || why+="the cold start does not enter demo; "
  [ ! -f "$WORK/state/started-before-clear" ] || why+="started before the log clear; "
  want="at:2.5 idle driving warning loss cooldown launch history selected recording paused summary compare replay export off at:2.5"
  [ "$(tr '\n' ' ' < "$WORK/state/extras" | sed 's/ $//')" = "$want" ] || why+="extras sent: $(tr '\n' ' ' < "$WORK/state/extras"); "
  # the five tab centres, then DRIVE and TRIP again from the same dump
  [ "$(tr '\n' ' ' < "$WORK/state/taps" | sed 's/ $//')" = "108 2300 324 2300 540 2300 756 2300 972 2300 108 2300 756 2300" ] \
    || why+="taps: $(tr '\n' ' ' < "$WORK/state/taps"); "
  # every UI read is taken while demo is paused: no dump between a live scenario or trip preview and the next at:2.5
  live=$(awk '/--es demo at:/ { live = 0; next } /--es (demo|trippreview) / { live = 1; next } live && /uiautomator dump/ { n++ } END { print n + 0 }' "$WORK/calls")
  [ "$live" = 0 ] || why+="$live UI reads while demo runs live; "
  grep -q "Emulator smoke passed: 5 tabs, 6 demo scenarios, 8 trip previews" "$WORK/summary" || why+="no summary line; "
  [ -z "$(ls -A "$WORK/tmp")" ] || why+="the temporary folder is left behind; "
  ! has "<node" || why+="the output carries dump text; "
  [ -z "$why" ] && pass || bad "$why"
fi

if run_case "the first UI read is taken after the pause request" 0 "$APK"; then
  p=$(first_of "--es demo at:2.5"); d=$(first_of "uiautomator dump")
  [ -n "$p" ] && [ -n "$d" ] && [ "$p" -lt "$d" ] && pass || bad "pause at line $p, first dump at line $d"
fi

if run_case "What's New is closed by its GOT IT" 0 "$APK" FAKE_WHATSNEW=1; then
  if head -n 1 "$WORK/state/taps" | grep -q -x "540 1550" && has "What's New closed"; then pass; else bad "taps: $(tr '\n' ' ' < "$WORK/state/taps")"; fi
fi

run_case "the DEMO label with a link state (DEMO · RCN) counts" 0 "$APK" "FAKE_DEMO_LABEL=DEMO · RCN" && pass
run_case "two dumps with no idle state, then a readable one" 0 "$APK" FAKE_IDLE_FAILS=2 && expect "Emulator smoke OK"

# ── Failures, each named ──
run_case "a tab that does not select after its tap fails" 1 "$APK" FAKE_NO_SELECT=THERMAL \
  && expect "::error::the THERMAL tab is not selected after its tap (selected: PERF)"
run_case "a restarted process fails" 1 "$APK" FAKE_RESTART_AT=3 \
  && expect "::error::com.openrs.dash.debug restarted on PERF: process 4242 at the start, 5151 now"
run_case "a process gone after the start fails" 1 "$APK" FAKE_GONE_AT=1 \
  && expect "::error::com.openrs.dash.debug is not running 0 s after the cold start"
run_case "a process gone during the scenarios fails" 1 "$APK" FAKE_GONE_AT=8 \
  && expect "::error::com.openrs.dash.debug is gone in demo driving (process 4242 at the start)"
if run_case "a fatal exception in the log fails, with crash lines filtered and redacted" 1 "$APK" FAKE_FATAL_AT=7; then
  why=""
  has "::error::in demo idle: Verifier rejected 0, FATAL EXCEPTION 1, ANR in com.openrs.dash.debug 0" || why+="no counts; "
  has "--- the device log's process and crash lines (the last 40)" || why+="no crash lines; "
  has "E AndroidRuntime: FATAL EXCEPTION: main" || why+="the crash line is missing; "
  ! has "maps key" || why+="a line naming a key reached the output; "
  ! has "$MAPS_VALUE" || why+="a Maps-key-shaped value reached the output; "
  has "denied for AIza<redacted>" || why+="the Maps-shaped value was not redacted; "
  ! has "$HEX_VALUE" || why+="a 32-hex value was not redacted; "
  has "has died: id <redacted>" || why+="no redaction mark; "
  ! has "I Zygote" || why+="an unrelated line was shown; "
  grep -q "Emulator smoke failed: in demo idle" "$WORK/summary" || why+="no failure summary; "
  [ -z "$why" ] && pass || bad "$why"
fi
run_case "another app's activity on top fails, naming it" 1 "$APK" FAKE_FOREIGN=1 \
  && expect "::error::after the cold start: the resumed activity is not com.openrs.dash.debug/com.openrs.dash.ui.MainActivity (found: com.google.android.permissioncontroller/com.android.permissioncontroller.permission.ui.GrantPermissionsActivity )"
run_case "no DEMO label fails" 1 "$APK" FAKE_NO_DEMO=1 \
  && expect "::error::after the cold start: no DEMO label in the UI dump"
run_case "a dump that never reads fails after three tries" 1 "$APK" FAKE_IDLE_FAILS=99 \
  && expect "::error::after the cold start: no readable UI dump in 3 tries (unreadable dump (ParseError))"
run_case "an installed APK that hashes differently fails" 1 "$APK" FAKE_BAD_HASH=1 \
  && expect "::error::the installed APK's SHA-256 is 0000000000000000000000000000000000000000000000000000000000000000"
run_case "an install that does not answer fails, naming the step" 1 "$APK" FAKE_HANG=install SMOKE_ADB_LIMIT_S=1 \
  && expect "::error::installing the APK: adb install -r -g $APK did not answer within 1 s"
run_case "a dump that does not answer fails, naming the step" 1 "$APK" FAKE_HANG=dump SMOKE_ADB_LIMIT_S=1 \
  && expect "::error::the UI read after the cold start (try 1 of 3): adb exec-out"

# ── Arguments ──
run_case "no argument is refused" 1 - && expect "::error::usage: demo-smoke.sh"
run_case "a folder with two APKs is refused" 1 "$WORK/two" && expect "holds 2 APK files, expected exactly one"
run_case "a missing APK is refused" 1 "$WORK/none.apk" && expect "::error::no APK at $WORK/none.apk"

# ── demo-smoke-ui.py on its own ──
ui_case() {   # ui_case <name> <xml> <expected facts, one per line>
  ran=$((ran + 1)); printf '%s' "$2" > "$WORK/ui.xml"
  local got; got=$(python3 -I "$UI" "$WORK/ui.xml" com.openrs.dash.debug 2>&1)
  if [ "$got" = "$3" ]; then echo "PASS $1"; else echo "FAIL $1 — got:"; sed 's/^/    /' <<<"$got"; failures=$((failures + 1)); fi
}
ui_case "ui: a tab with an alert badge is found; another app's text never reaches the facts" \
'<hierarchy><node package="com.android.systemui" text="DEMO" bounds="[0,0][10,10]"/><node package="com.openrs.dash.debug" content-desc="PERF tab, alert" selected="true" text="" bounds="[216,2252][432,2348]"/></hierarchy>' \
'windows=2
ours=1
others=com.android.systemui
demo=1
whatsnew=none
tab.PERF=324 2300
selected=PERF'
ui_case "ui: a label that only starts with DEMO, and a tab with empty bounds, do not count" \
'<hierarchy><node package="com.openrs.dash.debug" text="DEMO MODE ON" bounds="[0,0][10,10]"/><node package="com.openrs.dash.debug" content-desc="DRIVE tab" selected="false" bounds="[0,0][0,0]"/></hierarchy>' \
'windows=2
ours=2
others=none
demo=0
whatsnew=none
selected=none'
ui_case "ui: an odd package name is withheld" \
'<hierarchy><node package="not a package" text="x" bounds="[0,0][10,10]"/></hierarchy>' \
'windows=1
ours=0
others=<other>
demo=0
whatsnew=none
selected=none'
ui_case "ui: a dump that is not XML says only the error class" 'ERROR: could not get idle state.' 'unreadable dump (ParseError)'

[ "$ran" -gt 0 ] || { echo "demo smoke checks: no case ran"; exit 1; }
if [ "$failures" -eq 0 ]; then echo "demo smoke checks: all $ran passed"; else echo "demo smoke checks: $failures of $ran failed"; exit 1; fi
