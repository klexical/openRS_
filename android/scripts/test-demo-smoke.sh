#!/usr/bin/env bash
# test-demo-smoke.sh — checks demo-smoke.sh, the label-started emulator smoke (.github/workflows/emulator-smoke.yml): the
# Play Store is disabled first, the APK installs with its permissions granted and the device's copy must hash like the
# APK, the log is cleared BEFORE the cold start into demo, demo is paused before each UI read, What's New is closed by
# its GOT IT, each tab is tapped at the centre its dump node gives and must then read selected with the DEMO label, every
# demo scenario and trip preview is sent, and the run fails on a restarted or missing process, a fatal exception in the
# log, another app's resumed activity, a missing DEMO label, a tab that does not select, a dump that never reads, a hash
# mismatch or an adb call that does not answer, each with a message naming the step. A failure's crash lines drop any
# line naming a key and redact key-shaped values. A fake adb answers from generated dumps (system bar nodes included, as
# `uiautomator dump` gives every window) and records the order of calls; each of the four log checks fails alone, a
# failed log or UI read prints none of what it read, and the end's resumed-activity check and a What's New that stays
# are caught. Also checks demo-smoke-ui.py on its own, and the workflow's shape (label-only start, read-only token, no
# secret, no upload, no credentials kept, its steps in order).
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
#    that call kind (install, start, logcat-d, dump); FAKE_FAIL=<kind> makes that call print raw text and fail (logcat-d,
#    dump); FAKE_BAD_LINE=<line> is the line FAKE_FATAL_AT adds instead of the fatal exception; FAKE_FOREIGN_AT=<n> puts
#    another app on top from the n-th resumed-activity read; FAKE_WHATSNEW_STICKY=1 keeps the dialog after GOT IT;
#    FAKE_SELECT_BY=clickable marks no tab selected, only the selected one not clickable. ──
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
  # the shape of `uiautomator dump --windows`: displays, windows (one per app or system window), each with its hierarchy
  echo "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><displays><display id=\"0\">"
  echo "<window index=\"0\" title=\"Status bar\" type=\"TYPE_SYSTEM\"><hierarchy rotation=\"0\">"
  echo "<node index=\"0\" text=\"\" package=\"com.android.systemui\" content-desc=\"\" selected=\"false\" bounds=\"[0,0][1080,63]\"><node index=\"0\" text=\"12:00\" package=\"com.android.systemui\" content-desc=\"\" selected=\"false\" bounds=\"[40,0][160,63]\" /></node>"
  echo "</hierarchy></window><window index=\"1\" title=\"openRS_\" type=\"TYPE_APPLICATION\"><hierarchy rotation=\"0\">"
  echo "<node index=\"0\" text=\"\" package=\"com.openrs.dash.debug\" content-desc=\"\" selected=\"false\" bounds=\"[0,0][1080,2400]\">"
  [ -n "${FAKE_NO_DEMO-}" ] || echo "<node index=\"0\" text=\"$demo\" package=\"com.openrs.dash.debug\" content-desc=\"\" selected=\"false\" bounds=\"[800,70][960,170]\" />"
  for i in 0 1 2 3 4; do
    l=$((i * 216)); r=$((l + 216)); sel=false; clk=true; [ "${TABS[$i]}" = "$tab" ] && { sel=true; clk=false; }
    # FAKE_SELECT_BY=clickable: no tab node says selected (as on the API 36 image); the selected one is not clickable
    [ "${FAKE_SELECT_BY-}" = clickable ] && sel=false
    # Compose's shape: the tab's node holds its state; its description is on a fake first child with the same bounds
    echo "<node index=\"$((i + 1))\" text=\"\" package=\"com.openrs.dash.debug\" content-desc=\"\" selected=\"$sel\" clickable=\"$clk\" bounds=\"[$l,2252][$r,2348]\">"
    echo "<node index=\"0\" text=\"\" package=\"com.openrs.dash.debug\" content-desc=\"${TABS[$i]} tab\" selected=\"false\" clickable=\"false\" bounds=\"[$l,2252][$r,2348]\" /></node>"
  done
  echo "</node></hierarchy></window>"
  if [ -f "$S/whatsnew" ]; then
    echo "<window index=\"2\" title=\"openRS_\" type=\"TYPE_APPLICATION\"><hierarchy rotation=\"0\"><node index=\"0\" text=\"\" package=\"com.openrs.dash.debug\" content-desc=\"\" selected=\"false\" bounds=\"[86,600][994,1800]\"><node index=\"0\" text=\"GOT IT\" package=\"com.openrs.dash.debug\" content-desc=\"\" selected=\"false\" bounds=\"[400,1500][680,1600]\" /></node></hierarchy></window>"
  fi
  echo "</display></displays>"
}
case "$*" in
  "shell pm disable-user"*)   : ;;
  "shell wm "*)               : ;;
  "shell input keyevent "*)   : ;;
  "shell svc power "*)        : ;;
  "shell dumpsys power | grep"*) echo "mWakefulness=Awake" ;;
  "shell dumpsys window | grep"*) printf '  mCurrentFocus=Window{4f2a1b u0 com.openrs.dash.debug/com.openrs.dash.ui.MainActivity}\r\n' ;;
  "install -r -g "*)          hang install; echo "Performing Streamed Install"; echo "Success" ;;
  "shell pm path com.openrs.dash.debug") echo "package:/data/app/~~fake/com.openrs.dash.debug-1/base.apk" ;;
  "shell sha256sum "*)        if [ -n "${FAKE_BAD_HASH-}" ]; then h=0000000000000000000000000000000000000000000000000000000000000000
                              else h=$(sha256sum "$FAKE_APK" | cut -d' ' -f1); fi
                              printf '%s  %s\r\n' "$h" "${*#shell sha256sum }" ;;
  "logcat -c")                : > "$S/cleared" ;;
  "logcat -d")                hang logcat-d; n=$(count logcat)
                              [ "${FAKE_FAIL-}" = logcat-d ] && { echo "I RAW-LOG-TEXT the whole device log"; exit 1; }
                              [ -f "$S/cleared" ] || echo "E AndroidRuntime: FATAL EXCEPTION: before the clear"
                              echo "I ActivityManager: Start proc 4242:com.openrs.dash.debug/u0a190 for top-activity"
                              if [ -n "${FAKE_FATAL_AT-}" ] && [ "$n" -ge "$FAKE_FATAL_AT" ]; then
                                if [ -n "${FAKE_BAD_LINE-}" ]; then echo "$FAKE_BAD_LINE"
                                else echo "E AndroidRuntime: FATAL EXCEPTION: main"; echo "E AndroidRuntime: Process: com.openrs.dash.debug, PID: 4242"; fi
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
                              n=$(count resumed)
                              if [ -n "${FAKE_FOREIGN-}" ] || { [ -n "${FAKE_FOREIGN_AT-}" ] && [ "$n" -ge "$FAKE_FOREIGN_AT" ]; }; then
                                printf '  topResumedActivity=ActivityRecord{1a2b u0 com.google.android.permissioncontroller/com.android.permissioncontroller.permission.ui.GrantPermissionsActivity t9}\r\n'
                              else printf '  topResumedActivity=ActivityRecord{1a2b u0 com.openrs.dash.debug/com.openrs.dash.ui.MainActivity t9}\r\n'; fi ;;
  "exec-out rm -f /data/local/tmp/demo-smoke.xml; echo"*)
                              hang dump; n=$(count dump)
                              [ "${FAKE_FAIL-}" = dump ] && { echo '<node text="RAW-DUMP-TEXT" />'; exit 1; }
                              # no idle state: uiautomator says so, writes no file, nothing to cat
                              [ "$n" -gt "${FAKE_IDLE_FAILS:-0}" ] || { echo "UIA:ERROR: could not get idle state."; exit 0; }
                              echo "UIA:UI hierchary dumped to: /data/local/tmp/demo-smoke.xml"
                              # FAKE_CTRL_CHAR=1: a node's text carries a control character, as a Compose text can
                              if [ -n "${FAKE_CTRL_CHAR-}" ]; then dump | sed "s/text=\"12:00\"/text=\"12$(printf '\001')00\"/"; else dump; fi ;;
  "shell input tap "*)        set -- $*; x=$4; y=$5; echo "$x $y" >> "$S/taps"
                              if [ "$y" -ge 2252 ] && [ "$y" -le 2348 ]; then
                                t=${TABS[$((x / 216))]}; [ "${FAKE_NO_SELECT-}" = "$t" ] || echo "$t" > "$S/tab"
                              elif [ -f "$S/whatsnew" ] && [ "$x" -ge 400 ] && [ "$x" -le 680 ] && [ "$y" -ge 1500 ] && [ "$y" -le 1600 ]; then
                                [ -n "${FAKE_WHATSNEW_STICKY-}" ] || rm -f "$S/whatsnew"
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
    SMOKE_SETTLE_S=0 SMOKE_STEP_S=0 SMOKE_FRAME_S=0 SMOKE_RETRY_S=0 SMOKE_ADB_LIMIT_S=5 "$@" bash "$SCRIPT" "${args[@]}" > "$WORK/out" 2>&1
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
  for t in DRIVE PERF THERMAL TRIP GARAGE; do has "Tab $t: selected (its selected flag), DEMO shown, process 4242, log clean" || why+="no $t line; "; done
  [ "$(first_of 'shell pm disable-user')" = 1 ] || why+="the Play Store is not disabled first; "
  grep -q -F "uiautomator dump --windows" "$WORK/calls" || why+="the UI read is not of every window; "
  for w in "shell input keyevent KEYCODE_WAKEUP" "shell svc power stayon true" "shell wm dismiss-keyguard"; do
    n=$(first_of "$w"); s0=$(first_of "shell am start -S -W")
    [ -n "$n" ] && [ "$n" -lt "$s0" ] || why+="'$w' not sent before the cold start; "
  done
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
run_case "two dumps with no idle state, then a readable one" 0 "$APK" FAKE_IDLE_FAILS=2 \
  && expect "Emulator smoke OK" "UI read after the cold start, try 1 of 6: uiautomator said 'ERROR: could not get idle state.'; 0 bytes; unreadable dump (no XML in 0 characters); screen Awake, focus com.openrs.dash.debug/com.openrs.dash.ui.MainActivity"
run_case "a control character in a node's text does not stop the reader" 0 "$APK" FAKE_CTRL_CHAR=1 && expect "Emulator smoke OK"
run_case "with no tab node marked selected, the only tab not clickable is the selected one" 0 "$APK" FAKE_SELECT_BY=clickable \
  && expect "Emulator smoke OK" "Tab GARAGE: selected (the only tab not clickable), DEMO shown, process 4242, log clean"

# ── Failures, each named ──
run_case "a tab that does not select after its tap fails" 1 "$APK" FAKE_NO_SELECT=THERMAL \
  && expect "::error::the THERMAL tab is not selected after its tap (selected: PERF; tab flags: DRIVE:0---1:p:1 PERF:1---0:p:1 THERMAL:0---1:p:1 TRIP:0---1:p:1 GARAGE:0---1:p:1; tab tree: DRIVE:0---0/0/0/1/same>0---1/1/1/0/same>0----/0/6/0/in PERF:0---0/0/0/1/same>1---0/2/1/0/same>"
run_case "a tab that does not select fails when only the clickable flags tell" 1 "$APK" FAKE_SELECT_BY=clickable FAKE_NO_SELECT=TRIP \
  && expect "::error::the TRIP tab is not selected after its tap (selected: THERMAL; tab flags: DRIVE:0---1:p:1 PERF:0---1:p:1 THERMAL:0---0:p:1 TRIP:0---1:p:1 GARAGE:0---1:p:1; tab tree: "
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
# Each of the other three log checks, alone
run_case "a verifier rejection in the log fails" 1 "$APK" FAKE_FATAL_AT=2 \
    "FAKE_BAD_LINE=W ziparchive: Verifier rejected class com.openrs.dash.ui.DrivePageKt" \
  && expect "::error::on DRIVE: Verifier rejected 1, FATAL EXCEPTION 0, ANR in com.openrs.dash.debug 0, Fatal signal (com.openrs.dash.debug) 0"
run_case "an ANR of the app in the log fails" 1 "$APK" FAKE_FATAL_AT=2 "FAKE_BAD_LINE=E ActivityManager: ANR in com.openrs.dash.debug" \
  && expect "::error::on DRIVE: Verifier rejected 0, FATAL EXCEPTION 0, ANR in com.openrs.dash.debug 1, Fatal signal (com.openrs.dash.debug) 0"
run_case "a native fatal signal of the app in the log fails" 1 "$APK" FAKE_FATAL_AT=2 \
    "FAKE_BAD_LINE=F libc: Fatal signal 11 (SIGSEGV), code 1 in tid 4300 (RenderThread), pid 4242 (com.openrs.dash.debug)" \
  && expect "::error::on DRIVE: Verifier rejected 0, FATAL EXCEPTION 0, ANR in com.openrs.dash.debug 0, Fatal signal (com.openrs.dash.debug) 1"
run_case "another app's fatal signal does not count" 0 "$APK" FAKE_FATAL_AT=2 \
    "FAKE_BAD_LINE=F libc: Fatal signal 6 (SIGABRT), code -1 in tid 900 (binder), pid 900 (com.android.phone)" && pass
# A failed call that printed raw output: the device log and the UI dump are never printed
if run_case "a failed log read prints none of the log" 1 "$APK" FAKE_FAIL=logcat-d; then
  has "::error::reading the log after the cold start: adb logcat -d failed (exit 1" && ! has "RAW-LOG-TEXT" && pass || bad "the log's text was printed, or no error"
fi
if run_case "a failed UI read prints none of the dump" 1 "$APK" FAKE_FAIL=dump; then
  has "::error::the UI read after the cold start (try 1 of 6): adb exec-out" && ! has "RAW-DUMP-TEXT" && pass || bad "the dump's text was printed, or no error"
fi
run_case "another app on top at the end fails" 1 "$APK" FAKE_FOREIGN_AT=2 \
  && expect "::error::at the end: the resumed activity is not com.openrs.dash.debug/com.openrs.dash.ui.MainActivity"
run_case "What's New still shown after GOT IT fails" 1 "$APK" FAKE_WHATSNEW=1 FAKE_WHATSNEW_STICKY=1 \
  && expect "::error::What's New is still shown after its GOT IT was tapped"
run_case "another app's activity on top fails, naming it" 1 "$APK" FAKE_FOREIGN=1 \
  && expect "::error::after the cold start: the resumed activity is not com.openrs.dash.debug/com.openrs.dash.ui.MainActivity (found: com.google.android.permissioncontroller/com.android.permissioncontroller.permission.ui.GrantPermissionsActivity )"
run_case "no DEMO label fails" 1 "$APK" FAKE_NO_DEMO=1 \
  && expect "::error::after the cold start: no DEMO label in the UI dump"
run_case "a dump that never reads fails after six tries" 1 "$APK" FAKE_IDLE_FAILS=99 \
  && expect "::error::after the cold start: no readable UI dump in 6 tries (uiautomator said 'ERROR: could not get idle state.'; unreadable dump (no XML in 0 characters))"
run_case "an installed APK that hashes differently fails" 1 "$APK" FAKE_BAD_HASH=1 \
  && expect "::error::the installed APK's SHA-256 is 0000000000000000000000000000000000000000000000000000000000000000"
run_case "an install that does not answer fails, naming the step" 1 "$APK" FAKE_HANG=install SMOKE_ADB_LIMIT_S=1 \
  && expect "::error::installing the APK: adb install -r -g $APK did not answer within 1 s"
run_case "a dump that does not answer fails, naming the step" 1 "$APK" FAKE_HANG=dump SMOKE_ADB_LIMIT_S=1 \
  && expect "::error::the UI read after the cold start (try 1 of 6): adb exec-out"

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
ui_case "ui: a tab with an alert badge is found; another app's text never reaches the facts, nor counts as the DEMO label" \
'<hierarchy><node package="com.android.systemui" text="DEMO" bounds="[0,0][10,10]"/><node package="com.openrs.dash.debug" content-desc="PERF tab, alert" selected="true" text="" bounds="[216,2252][432,2348]"/></hierarchy>' \
'windows=2
ours=1
others=com.android.systemui
demo=0
whatsnew=none
tab.PERF=324 2300
selected=PERF
selectedby=flag
tabflags=PERF:1----:s:1
tabtree=PERF:1----/-/0/1/same'
ui_case "ui: a label that only starts with DEMO, and a tab with empty bounds, do not count" \
'<hierarchy><node package="com.openrs.dash.debug" text="DEMO MODE ON" bounds="[0,0][10,10]"/><node package="com.openrs.dash.debug" content-desc="DRIVE tab" selected="false" bounds="[0,0][0,0]"/></hierarchy>' \
'windows=2
ours=2
others=none
demo=0
whatsnew=none
selected=none
selectedby=none
tabflags=DRIVE:0----:s:1
tabtree=DRIVE:0----/-/0/1/same'
ui_case "ui: two tabs not clickable select none" \
'<hierarchy><node index="0" package="com.openrs.dash.debug" content-desc="DRIVE tab" selected="false" clickable="false" bounds="[0,2252][216,2348]"/><node package="com.openrs.dash.debug" content-desc="PERF tab" selected="false" clickable="false" bounds="[216,2252][432,2348]"/><node package="com.openrs.dash.debug" content-desc="THERMAL tab" selected="false" clickable="true" bounds="[432,2252][648,2348]"/><node package="com.openrs.dash.debug" content-desc="TRIP tab" selected="false" clickable="true" bounds="[648,2252][864,2348]"/><node package="com.openrs.dash.debug" content-desc="GARAGE tab" selected="false" clickable="true" bounds="[864,2252][1080,2348]"/></hierarchy>' \
'windows=5
ours=5
others=none
demo=0
whatsnew=none
tab.DRIVE=108 2300
tab.PERF=324 2300
tab.THERMAL=540 2300
tab.TRIP=756 2300
tab.GARAGE=972 2300
selected=none
selectedby=none
tabflags=DRIVE:0---0:s:1 PERF:0---0:s:1 THERMAL:0---1:s:1 TRIP:0---1:s:1 GARAGE:0---1:s:1
tabtree=DRIVE:0---0/0/0/1/same PERF:0---0/-/0/1/same THERMAL:0---1/-/0/1/same TRIP:0---1/-/0/1/same GARAGE:0---1/-/0/1/same'
ui_case "ui: with a tab missing, the one not clickable is not taken as selected" \
'<hierarchy><node index="0" package="com.openrs.dash.debug" content-desc="DRIVE tab" selected="false" clickable="false" bounds="[0,2252][216,2348]"/><node package="com.openrs.dash.debug" content-desc="PERF tab" selected="false" clickable="true" bounds="[216,2252][432,2348]"/><node package="com.openrs.dash.debug" content-desc="THERMAL tab" selected="false" clickable="true" bounds="[432,2252][648,2348]"/><node package="com.openrs.dash.debug" content-desc="TRIP tab" selected="false" clickable="true" bounds="[648,2252][864,2348]"/></hierarchy>' \
'windows=4
ours=4
others=none
demo=0
whatsnew=none
tab.DRIVE=108 2300
tab.PERF=324 2300
tab.THERMAL=540 2300
tab.TRIP=756 2300
selected=none
selectedby=none
tabflags=DRIVE:0---0:s:1 PERF:0---1:s:1 THERMAL:0---1:s:1 TRIP:0---1:s:1
tabtree=DRIVE:0---0/0/0/1/same PERF:0---1/-/0/1/same THERMAL:0---1/-/0/1/same TRIP:0---1/-/0/1/same'
ui_case "ui: a dump of every window: windows counted, another app's GOT IT and tab ignored, a window with no tree read as none" \
'<?xml version="1.0" ?><displays><display id="0"><window index="0" title="w" type="TYPE_SYSTEM"><hierarchy rotation="0"><node package="com.android.systemui" text="GOT IT" content-desc="PERF tab" selected="true" bounds="[0,0][10,10]"/></hierarchy></window><window index="1" title="w" type="TYPE_APPLICATION"></window></display></displays>' \
'windows=2
ours=0
others=com.android.systemui
demo=0
whatsnew=none
selected=none
selectedby=none
tabflags=none
tabtree=none'
ui_case "ui: a tab's state is read from its node when the description is on Compose's fake child, never from a larger parent" \
'<hierarchy><node package="com.openrs.dash.debug" content-desc="" selected="true" clickable="false" bounds="[0,2252][216,2348]"><node index="0" package="com.openrs.dash.debug" content-desc="DRIVE tab" selected="false" clickable="false" bounds="[0,2252][216,2348]"/></node><node package="com.openrs.dash.debug" content-desc="" selected="true" bounds="[0,0][1080,2400]"><node package="com.openrs.dash.debug" content-desc="PERF tab" selected="false" clickable="true" bounds="[216,2252][432,2348]"/></node></hierarchy>' \
'windows=2
ours=4
others=none
demo=0
whatsnew=none
tab.DRIVE=108 2300
tab.PERF=324 2300
selected=DRIVE
selectedby=flag
tabflags=DRIVE:1---0:p:1 PERF:0---1:s:1
tabtree=DRIVE:0---0/0/0/1/same>1---0/-/1/0/same PERF:0---1/-/0/1/same>1----/-/1/0/in'
ui_case "ui: a tab node that holds its own state is read itself, even as the first child of the bar" \
'<hierarchy><node package="com.openrs.dash.debug" content-desc="" selected="false" clickable="false" bounds="[0,2252][1080,2348]"><node index="0" package="com.openrs.dash.debug" content-desc="DRIVE tab" selected="true" clickable="false" bounds="[0,2252][216,2348]"/></node></hierarchy>' \
'windows=1
ours=2
others=none
demo=0
whatsnew=none
tab.DRIVE=108 2300
selected=DRIVE
selectedby=flag
tabflags=DRIVE:1---0:s:1
tabtree=DRIVE:1---0/0/0/1/same>0---0/-/1/0/in'
ui_case "ui: a description node that is not the first child does not take its parent's state" \
'<hierarchy><node package="com.openrs.dash.debug" content-desc="" selected="true" clickable="false" bounds="[0,2252][216,2348]"><node index="1" package="com.openrs.dash.debug" content-desc="DRIVE tab" selected="false" clickable="false" bounds="[0,2252][216,2348]"/></node></hierarchy>' \
'windows=1
ours=2
others=none
demo=0
whatsnew=none
tab.DRIVE=108 2300
selected=none
selectedby=none
tabflags=DRIVE:0---0:s:1
tabtree=DRIVE:0---0/1/0/1/same>1---0/-/1/0/same'
ui_case "ui: an odd package name is withheld" \
'<hierarchy><node package="not a package" text="x" bounds="[0,0][10,10]"/></hierarchy>' \
'windows=1
ours=0
others=<other>
demo=0
whatsnew=none
selected=none
selectedby=none
tabflags=none
tabtree=none'
ui_case "ui: a dump that is not XML says so, with no text of it" 'ERROR: could not get idle state.' 'unreadable dump (no XML in 32 characters)'
ui_case "ui: a broken dump gives the parser's position only" '<hierarchy><node text="SECRET-TEXT" </hierarchy>' 'unreadable dump (ParseError at line 1 column 36 of 48 characters)'
ui_case "ui: text before the XML and a control character are dropped" \
"junk before<?xml version='1.0' ?><hierarchy><node package=\"com.openrs.dash.debug\" text=\"a$(printf '\002')b\" content-desc=\"TRIP tab\" selected=\"true\" bounds=\"[648,2252][864,2348]\"/></hierarchy>" \
'windows=1
ours=1
others=none
demo=0
whatsnew=none
tab.TRIP=756 2300
selected=TRIP
selectedby=flag
tabflags=TRIP:1----:s:1
tabtree=TRIP:1----/-/0/1/same'

# ── The workflow's own claims (.github/workflows/emulator-smoke.yml): only the label starts it, read-only token, no
#    secret, nothing uploaded, no credentials left in the checkout, the nightly's emulator start, then this smoke ──
WF="$DIR/../../.github/workflows/emulator-smoke.yml"
ran=$((ran + 1))
if ! command -v ruby >/dev/null 2>&1; then echo "FAIL the workflow's shape — ruby not found"; failures=$((failures + 1))
else
  why=$(ruby -ryaml -e '
    wf = YAML.safe_load(File.read(ARGV[0])); text = File.read(ARGV[0]); w = []
    on = wf["on"] || wf[true]
    w << "triggers #{on.keys.inspect}" unless on.keys == ["pull_request"]
    w << "types #{on["pull_request"]["types"].inspect}" unless on["pull_request"]["types"] == %w[labeled synchronize reopened]
    w << "workflow permissions #{wf["permissions"].inspect}" unless wf["permissions"] == { "contents" => "read" }
    w << "#{wf["jobs"].size} jobs" unless wf["jobs"].keys == ["smoke"]
    j = wf["jobs"]["smoke"] || {}
    w << "job permissions #{j["permissions"].inspect}" unless j["permissions"] == { "contents" => "read" }
    want_if = "(github.event.action == '\''labeled'\'' && github.event.label.name == '\''emulator-smoke'\'') || " \
              "(github.event.action != '\''labeled'\'' && contains(github.event.pull_request.labels.*.name, '\''emulator-smoke'\''))"
    w << "condition #{j["if"].inspect}" unless j["if"] == want_if
    w << "names a secret" if text.include?("secrets.")
    w << "declares an environment" if j.key?("environment")
    st = j["steps"] || []
    w << "uploads an artifact" if st.any? { |x| x["uses"].to_s.start_with?("actions/upload-artifact") }
    co = st.find { |x| x["uses"].to_s.start_with?("actions/checkout@") }
    w << "checkout keeps credentials" unless co && co.dig("with", "persist-credentials") == false
    runs = st.map { |x| x["run"].to_s.strip }
    order = ["bash android/scripts/test-demo-smoke.sh", "./gradlew assembleDebug --no-daemon",
             "bash android/scripts/start-emulator.sh \"system-images;android-36;google_apis;x86_64\" 420",
             "bash android/scripts/demo-smoke.sh android/app/build/outputs/apk/debug"]
    idx = order.map { |c| runs.index { |r| r.lines.map(&:strip).include?(c) } }
    w << "steps out of order or missing: #{order.zip(idx).inspect}" unless idx.none?(&:nil?) && idx == idx.sort
    w << "the smoke is not the last step" unless runs.last == order.last
    puts w.join("; ")' "$WF" 2>&1)
  if [ -z "$why" ]; then echo "PASS the workflow's shape: label-only start, read-only, no secret, no upload, its steps in order"
  else echo "FAIL the workflow's shape — $why"; failures=$((failures + 1)); fi
fi

[ "$ran" -gt 0 ] || { echo "demo smoke checks: no case ran"; exit 1; }
if [ "$failures" -eq 0 ]; then echo "demo smoke checks: all $ran passed"; else echo "demo smoke checks: $failures of $ran failed"; exit 1; fi
