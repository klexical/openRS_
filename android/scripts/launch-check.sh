#!/usr/bin/env bash
# launch-check.sh <apk> <wait seconds> — the nightly's launch check on a booted emulator (#363).
# It guards launch only: the release APK installs, starts from cold and is still the same process after the wait, and
# the log since just before the start has no verifier rejection, fatal exception or ANR of the app. Uses adb only.
# The Play Store is disabled first: on a fresh Play-services image it updates GMS and the Maps loader kills the app
# (.claude/rules/build-release.md). The log is cleared before the start, or the check would not cover the launch.
# Every adb call runs under `timeout 60` and has a step name: a call that fails or does not answer ends the attempt with
# that name and whether adb still sees the emulator. Every failure prints what a failed night needs to show its cause:
# `adb devices`, the emulator and its helper processes, whether start-emulator.sh's emulator.pid process is alive and
# named by the kernel's kill lines, its exit status (emulator.exit), the device log's process and crash lines and its
# events buffer's kills and deaths, the host's memory, load and kernel kill / out-of-memory lines, its fault and
# fatal-signal lines (fixed fields only), the crash database's files and the newest dump's four facts (crash-facts.py),
# the liveness trace, `df -h`, the emulator's own exit messages from the whole emulator.log and its last 200 lines
# (emulator.* files are in the working directory both steps share), then `--- end of diagnostics`.
# The one retry (#363, the owner's decision): the check runs again once, in the same step, only for a vanished emulator:
# the failure was a lost adb connection, the emulator.pid process is gone, no emulator process is left, and the app's
# process id was unchanged at the last answered sample (the liveness trace; fail closed without one), with enough of the
# job's time left for a second boot. The emulator is started again by start-emulator.sh (no sdkmanager). Each attempt is
# headed, and any end but a plain pass writes one line to the run's summary. Never when the app crashed, restarted or
# failed its own check, or when the emulator is still alive.
set -euo pipefail
PKG=com.openrs.dash; ACTIVITY=com.openrs.dash.ui.MainActivity
ADB_LIMIT_S=60
DIR="$(cd "$(dirname "$0")" && pwd)"
EMULATOR_LOG="$PWD/emulator.log"; EMULATOR_PID_FILE="$PWD/emulator.pid"; EMULATOR_EXIT_FILE="$PWD/emulator.exit"
STARTED_FILE="$PWD/emulator.started"; ARGS_FILE="$PWD/emulator.args"
# The launch job's limit: nightly.yml's `timeout-minutes: 15` for `launch` (scripts/test-release-workflows.rb checks they
# agree). A retry starts only with MIN_BOOT_S of boot time left after a margin, the restart and the check's own share.
JOB_LIMIT_S=900
RETRY_MARGIN_S=60; RESTART_S=30; CHECK_S=150; MIN_BOOT_S=120; MAX_BOOT_S=420
PROC_ROOT="${LAUNCH_PROC_ROOT:-/proc}"                             # the trace's CPU source (a fake one in the tests)
TRACE_S="${LAUNCH_TRACE_S:-2}"                                     # the liveness trace's interval
CRASH_DIR="${LAUNCH_CRASH_DIR-}"     # the emulator's crash database: by default the one its own log names (crash_dir)
HANDLER_WAIT_S="${LAUNCH_HANDLER_WAIT_S:-20}"                      # for crashpad_handler to finish a dump
# The emulator's own helpers, by their place in the SDK (emulator/crashpad_handler, emulator/netsimd): another program's
# crash handler (a browser's, on a developer's Mac) is neither listed nor waited for (QA's N1). Not by parent: the decode
# waits after the emulator is gone, when its children belong to init.
EMULATOR_HELPERS='/emulator/(crashpad_handler|netsimd)( |$)'
EMULATOR_HANDLER='/emulator/crashpad_handler( |$)'
START_EMULATOR="${LAUNCH_START_EMULATOR:-$DIR/start-emulator.sh}"
STEP="the argument checks"; REPORTED=0; TMP=""; A=""; TRACER=""

# redact: the APK's two key values by their shape: the Maps key (AIza + 35 characters or more), any appid= value, and
# any standalone run of exactly 32 hex characters (the weather key printed bare; longer hex runs such as 40-hex build ids
# stay). The last one loops, so two values one character apart are both replaced.
redact() {
  sed -E -e 's/AIza[0-9A-Za-z_-]{35,}/AIza<redacted>/g; s/appid=[^&[:space:]]+/appid=<redacted>/g' \
    -e ':a' -e 's/(^|[^0-9A-Fa-f])[0-9A-Fa-f]{32}([^0-9A-Fa-f]|$)/\1<redacted>\2/' -e 'ta'
}
# show <status> <lines> <what> [<raw>]: a section's body. A read that failed with nothing read says "(could not read …)";
# one cut short after reading something says "(read cut short …)" and still shows what matched (a timed-out read on a
# wedged device is when those lines matter); one that matched nothing says "(no matching lines)". An empty section never
# reads the same as a failed one (#363).
show() {
  if [ "$1" -ne 0 ] && [ -z "${4-}" ]; then echo "(could not read: $3, exit $1)"; return; fi
  [ "$1" -eq 0 ] || echo "(read cut short: $3, exit $1)"
  if [ -z "$2" ]; then echo "(no matching lines)"; else printf '%s\n' "$2"; fi
}
# The emulator's own exit messages, from the whole log (the last 200 lines can miss them): the hang detector, a crash
# handler's fatal exit, KVM failing under the guest, the renderer, and disk space
SIGNATURES='detected a hanging thread|Failed hang detection predicate|crashhandler_die|exiting because of the internal error|kvm run failed|KVM internal error|KVM: unknown exit|Failed to initialize (GL emulation|FrameBuffer|[Gg]fxstream)|[Nn]ot enough disk space|No space left on device'
# fault_lines: the kernel's fault and fatal-signal lines, fixed fields only (the owner's rule, QA's V2 and its reduced
# form): the kind (a trap's from a fixed list), the thread's name ([A-Za-z0-9._+ -], at most 15, else withheld), and for
# a fatal-signal line its signal number (1-64). Never an address, ip, sp, an error code, an object name, a register line
# or the Code: bytes: a register or a wild address can hold data.
fault_lines() {
  perl -ne '
    sub thr { my $t = shift; return ($t =~ /^[A-Za-z0-9._+ -]{1,15}$/) ? $t : "<name withheld>" }
    my %traps = map { $_ => 1 } ("general protection fault", "general protection", "invalid opcode", "divide error", "int3", "stack segment");
    if (/\]\s*(.+?)\[\d+\]: segfault at \S+ ip \S+ sp \S+ error \d+/) { print "segfault: thread ", thr($1), "\n"; next }
    if (/traps:\s*(.+?)\[\d+\]\s+(?:trap\s+)?(.+?)\s+ip[:\s]/) { my $k = $traps{$2} ? $2 : "other"; print "trap ($k): thread ", thr($1), "\n"; next }
    if (/\]\s*\S+?: (.+?): potentially unexpected fatal signal (\d+)\./ && $2 >= 1 && $2 <= 64) { print "fatal signal $2: thread ", thr($1), "\n"; next }'
}
# The kernel's own kill and out-of-memory phrases, anchored: a register line that happens to hold "oom" is not one
OOM_LINES='Out of memory|[Kk]illed process [0-9]+|oom-kill:|oom_reaper|invoked oom-killer'
# crash_dir: the emulator's crash database, as its log names it ("Storing crashdata in: <folder>, …"); never the whole
# temp folder, which also holds the emulator's disk overlays
crash_dir() {
  if [ -n "$CRASH_DIR" ]; then echo "$CRASH_DIR"; return; fi
  sed -n 's/.*Storing crashdata in: \(.*\), detection is .*/\1/p' "$EMULATOR_LOG" 2>/dev/null | tail -n 1 || true
}
diagnostics() {
  local st out kern kst pid ex t0
  echo "--- adb devices"
  timeout 10 adb devices 2>&1 || echo "(adb devices failed)"
  echo "--- emulator processes"
  st=0; out=$(pgrep -a -f -- 'qemu-system|emulator/emulator') || st=$?
  case $st in 0) printf '%s\n' "$out" ;; 1) echo "(no emulator process)" ;; *) echo "(could not read: pgrep, exit $st)" ;; esac
  echo "--- emulator helper processes (the emulator's own crashpad_handler, netsimd): process id and name only"
  # Never pgrep's -l / -a: BSD pgrep prints a process's whole command line with them, and a crash handler's arguments
  # carry paths and annotations (QA's P1; seen on macOS). ps's comm, cut to its file name, is the name.
  st=0; out=$(pgrep -f -- "$EMULATOR_HELPERS") || st=$?
  case $st in
    0) for p in $out; do n=$(timeout 5 ps -o comm= -p "$p" 2>/dev/null | sed 's#.*/##' || true); echo "$p ${n:-?}"; done ;;
    1) echo "(none)" ;; *) echo "(could not read: pgrep, exit $st)" ;; esac
  kst=0; kern=$(sudo -n dmesg 2>/dev/null) || kst=$?
  echo "--- the emulator's process (emulator.pid)"
  pid=$(cat "$EMULATOR_PID_FILE" 2>/dev/null | tr -dc '0-9' || true)
  if [ ! -f "$EMULATOR_PID_FILE" ]; then echo "(could not read: no emulator.pid in $PWD)"
  elif [ -z "$pid" ]; then echo "(could not read: emulator.pid holds no process id)"
  else
    if kill -0 "$pid" 2>/dev/null; then echo "pid $pid: alive ($(timeout 5 ps -o comm= -p "$pid" 2>/dev/null | sed 's#.*/##' || echo '?'))"
    else echo "pid $pid: gone"; fi
    if [ "$kst" -ne 0 ]; then echo "the kernel's lines could not be read (sudo -n dmesg, exit $kst)"
    # Only within the kill and out-of-memory lines, and pid= as a whole field: not an audit line's pid=, not ppid=
    elif grep -E "$OOM_LINES" <<<"$kern" \
        | grep -q -E "[Kk]illed process $pid([^0-9]|\$)|(^|[^[:alnum:]_])pid=$pid([^0-9]|\$)"; then
      echo "the kernel's kill lines name pid $pid: yes"
    else echo "the kernel's kill lines name pid $pid: no"; fi
  fi
  echo "--- the emulator's exit status (emulator.exit)"
  ex=$(sed -n 's/^exit=\([0-9]*\)$/\1/p' "$EMULATOR_EXIT_FILE" 2>/dev/null || true)
  if [ -z "$ex" ]; then echo "(no emulator.exit: the emulator has not ended, or its wrapper wrote nothing)"
  elif [ "$ex" -gt 128 ]; then echo "exit=$ex (signal $((ex - 128)): $(kill -l $((ex - 128)) 2>/dev/null || echo '?'))"
  else echo "exit=$ex"; fi
  # Process and crash lines only, never the whole log: the Maps SDK's authorization block prints the API key
  # (.claude/rules/build-release.md). A line naming a key is left out, and the two key values the APK holds are
  # redacted by their shape wherever they appear (redact above): the Maps key and the weather key, with or without appid=.
  # Bounded: with the device gone, logcat waits.
  echo "--- the device log's process and crash lines (the last 40)"
  st=0; out=$(timeout 10 adb logcat -d -b main,system,crash 2>/dev/null) || st=$?
  show "$st" "$(grep -E 'AndroidRuntime|Fatal signal|ActivityManager: (Process|Killing|Start proc|ANR)|lowmemorykiller|lmkd' <<<"$out" \
    | grep -v -i 'key' | redact | tail -n 40 || true)" "adb logcat" "$out"
  echo "--- the device's events: process kills and deaths (the last 20)"
  st=0; out=$(timeout 10 adb logcat -d -b events 2>/dev/null) || st=$?
  show "$st" "$(grep -E 'am_kill|am_proc_died|killinfo' <<<"$out" | grep -v -i 'key' | redact | tail -n 20 || true)" "adb logcat -b events" "$out"
  echo "--- host memory"
  timeout 10 free -m 2>/dev/null || echo "(free not available)"
  echo "--- host load (uptime)"
  timeout 5 uptime 2>/dev/null || echo "(uptime not available)"
  echo "--- host kernel: killed processes and out-of-memory lines (the last 20)"
  show "$kst" "$(grep -E "$OOM_LINES" <<<"$kern" | tail -n 20 || true)" "sudo -n dmesg" "$kern"
  echo "--- host kernel: fault and fatal-signal lines (the last 10, fixed fields only)"
  if [ "$kst" -ne 0 ]; then echo "(could not read: sudo -n dmesg, exit $kst)"
  else
    out=$(fault_lines <<<"$kern" | redact | tail -n 10 || true)
    # With the emulator's crash handler armed (its log says "detection is enabled"), the kernel prints no fault line for
    # a crash or for the hang detector's own abort: none here decides nothing (#363)
    if [ -z "$out" ]; then echo "(no matching lines: with the emulator's crash handler armed none is expected, so this decides nothing)"
    else printf '%s\n' "$out"; fi
  fi
  t0=$(cat "${A:-/nonexistent}/cold-start" 2>/dev/null || date +%s)
  local cdir; cdir=$(crash_dir)
  echo "--- the crash database (a count; per file its kind, size and seconds after the cold start; no names)"
  if ! command -v python3 >/dev/null 2>&1; then echo "(could not read: python3 not found)"
  elif [ -z "$cdir" ]; then echo "(could not read: the emulator's log names no crash database)"
  else timeout 10 python3 -I "$DIR/crash-facts.py" list "$cdir" "$t0" 2>&1 | redact || echo "(could not read: crash-facts.py list)"; fi
  echo "--- the newest crash dump of this attempt: its age, signal, crashing thread, where, fault address class"
  if ! command -v python3 >/dev/null 2>&1; then echo "(could not read: python3 not found)"
  elif [ -z "$cdir" ]; then echo "(could not read: the emulator's log names no crash database)"
  else
    for _ in $(seq 1 "$HANDLER_WAIT_S"); do pgrep -f -- "$EMULATOR_HANDLER" >/dev/null 2>&1 || break; sleep 1; done
    timeout 20 python3 -I "$DIR/crash-facts.py" decode "$cdir" "$t0" 2>&1 | redact || echo "(could not read: crash-facts.py decode)"
  fi
  echo "--- the liveness trace (the last 60 samples: seconds after the cold start, emulator, its CPU % over the interval and resident kB, app's process id, load)"
  if [ -n "$A" ] && [ -s "$A/trace" ]; then tail -n 60 "$A/trace"; else echo "(no trace: the check stopped before the cold start)"; fi
  echo "--- disk (df -h)"
  st=0; out=$(timeout 10 df -h "$PWD" "${HOME:-}/.android" 2>&1) || st=$?
  printf '%s\n' "$out"; [ "$st" -eq 0 ] || echo "(df exit $st)"
  echo "--- emulator.log: exit signatures (whole file, the last 20)"
  if [ -f "$EMULATOR_LOG" ]; then show 0 "$(grep -n -E "$SIGNATURES" "$EMULATOR_LOG" | redact | tail -n 20 || true)" "emulator.log"
  else echo "(could not read: no emulator.log in $PWD)"; fi
  echo "--- the last 200 lines of $EMULATOR_LOG"
  if [ -f "$EMULATOR_LOG" ]; then tail -n 200 "$EMULATOR_LOG"; else echo "(no emulator.log in $PWD)"; fi
  echo "--- end of diagnostics"
}
fail() { echo "::error::$*"; REPORTED=1; diagnostics; exit 1; }

# call [--private] <step> <adb args...>: adb under the time limit. Its stdout (CR removed) is in OUT; a failed call or
# one that does not answer fails the attempt, named by its step, and shows what it printed, unless --private: the device
# log is never printed whole (the Maps SDK's authorization block prints the API key, .claude/rules/build-release.md).
# A failure with the emulator no longer connected is marked (lost), the first condition of the retry.
call() {
  local private=0; if [ "$1" = --private ]; then private=1; shift; fi
  STEP="$1"; shift
  local status=0 detail err state
  timeout "$ADB_LIMIT_S" adb "$@" > "$A/out" 2> "$A/err" || status=$?
  OUT=$(tr -d '\r' < "$A/out")
  [ "$status" -ne 0 ] || return 0
  [ -z "$OUT" ] || [ "$private" -eq 1 ] || echo "$OUT"
  err=$(tr -d '\r' < "$A/err" | tr '\n' ' ')
  if [ "$status" -eq 124 ]; then detail="did not answer within $ADB_LIMIT_S s"
  else detail="failed (exit $status: ${err:0:300})"; fi
  state=$(timeout 10 adb get-state 2>&1 | tr -d '\r' | tr '\n' ' ' || true)
  if [ "${state% }" = device ]; then fail "$STEP: adb $* $detail; adb still sees the emulator"
  else printf '%s\n' "${state% }" > "$A/lost"; fail "$STEP: adb $* $detail; the emulator is no longer connected (adb get-state: ${state% })"; fi
}

# trace: the liveness trace, in the background from just before the cold start (#363): every TRACE_S, the seconds since
# the cold start, whether the emulator.pid process is alive, its CPU % over the interval (/proc) and resident memory in
# kB (ps; "-" when gone, unreadable or for the first sample), the app's process id as the device answers it (or none /
# fail / timeout), the host's 1-minute load, and whether the process check after the start had recorded its id (after=1).
trace() {
  set +e
  local t0 epid app r st load cpu rss alive hz stat up ticks f prev_ticks="" prev_up=""
  hz=$(getconf CLK_TCK 2>/dev/null); [[ "$hz" =~ ^[0-9]+$ ]] && [ "$hz" -gt 0 ] || hz=100
  t0=$(date +%s); epid=$(tr -dc '0-9' < "$EMULATOR_PID_FILE" 2>/dev/null)
  while :; do
    r=$(timeout 3 adb shell 'p=$(pidof com.openrs.dash); echo "pid:$p"' 2>/dev/null); st=$?; r=${r//$'\r'/}
    if [ "$st" -eq 124 ]; then app=timeout; elif [ "$st" -ne 0 ] || [[ "$r" != pid:* ]]; then app=fail
    else app=${r#pid:}; app=${app// /,}; [ -n "$app" ] || app=none; [[ "$app" =~ ^([0-9,]+|none)$ ]] || app=fail; fi
    load=$(cut -d' ' -f1 /proc/loadavg 2>/dev/null || echo -)
    # the emulator's CPU % over the last interval (utime + stime from /proc/<pid>/stat against /proc/uptime; "-" for the
    # first sample or without /proc) and its resident memory in kB (ps): numbers only, saturation or growth before a crash
    # (#363, QA's S4: ps's pcpu is a lifetime average)
    cpu=-; rss=-; alive=gone
    if [ -n "$epid" ] && kill -0 "$epid" 2>/dev/null; then
      alive=alive
      stat=$(cat "$PROC_ROOT/$epid/stat" 2>/dev/null); up=$(cut -d' ' -f1 "$PROC_ROOT/uptime" 2>/dev/null); up=${up/./}
      ticks=""
      if [[ "$stat" == *") "* ]] && [[ "$up" =~ ^[0-9]+$ ]]; then
        read -r -a f <<<"${stat##*) }"
        [[ "${f[11]-}" =~ ^[0-9]+$ ]] && [[ "${f[12]-}" =~ ^[0-9]+$ ]] && ticks=$(( f[11] + f[12] ))
      fi
      if [ -n "$ticks" ]; then
        [ -n "$prev_ticks" ] && [ "$up" -gt "$prev_up" ] && cpu=$(( (ticks - prev_ticks) * 10000 / (hz * (up - prev_up)) ))
        prev_ticks=$ticks; prev_up=$up
      fi
      rss=$(timeout 5 ps -o rss= -p "$epid" 2>/dev/null); rss=${rss// /}; [[ "$rss" =~ ^[0-9]+$ ]] || rss=-
    fi
    echo "+$(( $(date +%s) - t0 ))s emulator=$alive cpu=$cpu rss_kb=$rss app=$app load=${load:--} after=$( [ -s "$A/pid1" ] && echo 1 || echo 0)" >> "$A/trace"
    sleep "$TRACE_S"
  done
}

# attempt: one launch check, in a subshell of its own (its failure exits it); $A is its folder
attempt() {
  set -euo pipefail
  REPORTED=0
  attempt_end() {
    local status=$?
    # Every command here may fail without ending the trap (set -e applies inside it): pkill finds no child between
    # two samples, and a skipped kill would leave the trace running past the check
    if [ -n "$TRACER" ]; then
      pkill -P "$TRACER" 2>/dev/null || true
      kill "$TRACER" 2>/dev/null || true
      wait "$TRACER" 2>/dev/null || true
    fi
    if [ "$status" -ne 0 ] && [ "$REPORTED" -eq 0 ]; then
      echo "::error::the launch check stopped during $STEP (exit $status) without a message of its own"
      diagnostics
    fi
  }
  trap attempt_end EXIT
  STEP="setting up"
  timeout "$ADB_LIMIT_S" adb shell pm disable-user --user 0 com.android.vending >/dev/null 2>&1 \
    || echo "Play Store not disabled (not on this image, or adb failed)"
  call "installing the APK" install -r "$APK"; echo "$OUT"
  call "clearing the log" logcat -c
  # A baseline for the failure's own memory reading (#363)
  echo "--- host memory before the cold start"
  timeout 10 free -m 2>/dev/null || echo "(free not available)"
  date +%s > "$A/cold-start"
  trace 2>/dev/null & TRACER=$!                           # its stderr: the shell's "Terminated" when it is stopped
  call "the cold start" shell am start -S -W -n "$PKG/$ACTIVITY"; echo "$OUT"
  TOP=$(sed -n 's/^Activity: //p' <<<"$OUT")
  case "$TOP" in
    ""|"$PKG"/*) ;;
    *) echo "Note: on top after the start: $TOP (the app's activity is behind it; a fresh install asks for its permissions)" ;;
  esac
  # `|| true` on the device: pidof exits 1 when nothing matches, so adb's status is then about the connection only
  call "the process check after the start" shell "pidof $PKG || true"; PID1="$OUT"
  [ -n "$PID1" ] || fail "$PKG is not running after the start"
  printf '%s\n' "${PID1// /,}" > "$A/pid1"
  STEP="the ${WAIT} s wait"; sleep "$WAIT"
  call "the process check after the wait" shell "pidof $PKG || true"; PID2="$OUT"
  P2=${PID2// /,}; echo "+$(( $(date +%s) - $(cat "$A/cold-start") ))s check app=${P2:-none} after=1" >> "$A/trace"
  [ -n "$PID2" ] || fail "$PKG is gone ${WAIT} s after the start (process $PID1)"
  [ "$PID2" = "$PID1" ] || fail "$PKG restarted: process $PID1 at the start, $PID2 after ${WAIT} s"

  call --private "reading the log" logcat -d; LOG="$OUT"
  V=$(grep -c 'Verifier rejected' <<<"$LOG" || true)
  F=$(grep -c 'FATAL EXCEPTION' <<<"$LOG" || true)
  N=$(grep -c "ANR in $PKG" <<<"$LOG" || true)
  [ "$V$F$N" = 000 ] || fail "after the start: Verifier rejected $V, FATAL EXCEPTION $F, ANR in $PKG $N"
  echo "Launch check OK: $PKG process $PID1 running ${WAIT} s after a cold start; Verifier rejected 0, FATAL EXCEPTION 0, ANR 0"
}

# retry_refusal <attempt folder>: why the failed attempt may not be repeated, or OK. Fail closed: anything not shown counts
# against a retry, and only the word OK, printed last, allows one (an early stop prints nothing and is no OK).
retry_refusal() {
  local a="$1" pid last started left now
  [ -f "$a/lost" ] || { echo "the failure was not a lost connection to the emulator"; return; }
  pid=$(tr -dc '0-9' < "$EMULATOR_PID_FILE" 2>/dev/null || true)
  [ -n "$pid" ] || { echo "no process id in emulator.pid"; return; }
  ! kill -0 "$pid" 2>/dev/null || { echo "the emulator's process $pid is still alive"; return; }
  ! pgrep -f -- 'qemu-system|emulator/emulator' >/dev/null 2>&1 || { echo "an emulator process is still running"; return; }
  [ -s "$a/trace" ] || { echo "no liveness trace"; return; }
  [ -s "$a/pid1" ] || { echo "no process id from the check after the start"; return; }
  last=$(grep ' after=1' "$a/trace" | grep -E ' app=([0-9,]+|none) ' | tail -n 1 | sed -E 's/.* app=([^ ]+) .*/\1/' || true)
  [ -n "$last" ] || { echo "no sample answered after the process check after the start"; return; }
  [ "$last" = "$(cat "$a/pid1")" ] || { echo "the app's process was $last at the last answer, not $(cat "$a/pid1")"; return; }
  started=$(tr -dc '0-9' < "$STARTED_FILE" 2>/dev/null || true)
  [ -n "$started" ] || { echo "no start time in emulator.started"; return; }
  now=$(date +%s 2>/dev/null || true)
  [[ "$now" =~ ^[0-9]+$ ]] || { echo "no clock reading"; return; }
  left=$(( JOB_LIMIT_S - (now - started) - RETRY_MARGIN_S - RESTART_S - CHECK_S ))
  [ "$left" -ge "$MIN_BOOT_S" ] || { echo "not enough time: ${left} s of boot time left, ${MIN_BOOT_S} s needed"; return; }
  [ -s "$ARGS_FILE" ] || { echo "no image in emulator.args"; return; }
  echo OK
}
boot_limit() {
  local started left
  started=$(tr -dc '0-9' < "$STARTED_FILE")
  left=$(( JOB_LIMIT_S - ($(date +%s) - started) - RETRY_MARGIN_S - RESTART_S - CHECK_S ))
  echo $(( left < MAX_BOOT_S ? left : MAX_BOOT_S ))
}
SUMMARY_DONE=0
summary() { SUMMARY_DONE=1; [ -z "${GITHUB_STEP_SUMMARY-}" ] || echo "$*" >> "$GITHUB_STEP_SUMMARY"; }

# Outside the attempts, a failure with no message of its own still ends with one and the diagnostics; an attempt reports
# its own (its subshell's trap), and after the attempts every exit is the script's own decision
ATTEMPTED=0
script_end() {
  local status=$?
  if [ "$status" -ne 0 ] && [ "$REPORTED" -eq 0 ] && [ "$ATTEMPTED" -eq 0 ]; then
    echo "::error::the launch check stopped during $STEP (exit $status) without a message of its own"
    diagnostics
  fi
  # Any end but a plain pass writes one summary line: an argument failure, or a stop between the attempts (spec review)
  [ "$status" -eq 0 ] || [ "$SUMMARY_DONE" -eq 1 ] || summary "Launch check failed during $STEP (exit $status)"
  [ -z "$TMP" ] || rm -rf "$TMP"
}
trap script_end EXIT
[ $# -eq 2 ] || fail "usage: launch-check.sh <apk> <wait seconds>"
APK="$1"; WAIT="$2"
[ -f "$APK" ] || fail "APK not found: $APK"
[[ "$WAIT" =~ ^[0-9]+$ ]] || fail "wait '$WAIT' is not a number of seconds"
STEP="setting up"; TMP=$(mktemp -d "${TMPDIR:-/tmp}/launch-check.XXXXXX")

echo "=== launch attempt 1 of 2"
A="$TMP/a1"; mkdir "$A"; ATTEMPTED=1
set +e; ( attempt ); status=$?; set -e
[ "$status" -ne 0 ] || exit 0

STEP="the retry decision"
why=$(retry_refusal "$A" | tail -n 1 || true)
if [ "$why" != OK ]; then
  why=${why:-the decision stopped without a reason}
  echo "No retry: $why"
  summary "Launch check failed; no retry: $why"
  exit 1
fi
BOOT_S=$(boot_limit)
lost=$(head -n 1 "$A/lost" 2>/dev/null || true); lost=${lost:-unknown}
echo "=== launch attempt 2 of 2 (attempt 1: the emulator vanished: no emulator process, adb get-state: $lost; the app's process unchanged)"
for f in log pid exit; do [ ! -f "$PWD/emulator.$f" ] || mv "$PWD/emulator.$f" "$PWD/emulator.attempt1.$f"; done
STEP="starting the emulator again"
timeout 10 adb kill-server >/dev/null 2>&1 || true                       # the dead emulator's transport goes with it
if ! EMULATOR_SKIP_INSTALL=1 bash "$START_EMULATOR" "$(head -n 1 "$ARGS_FILE")" "$BOOT_S"; then
  summary "Launch check failed on attempt 2: the emulator did not start again (attempt 1: the emulator vanished; adb get-state: $lost)"
  exit 1
fi
A="$TMP/a2"; mkdir "$A"
set +e; ( attempt ); status=$?; set -e
if [ "$status" -eq 0 ]; then
  summary "Launch check passed on attempt 2; attempt 1: the emulator vanished (no emulator process; adb get-state: $lost)"
  exit 0
fi
summary "Launch check failed on attempt 2 after a retry (attempt 1: the emulator vanished; adb get-state: $lost)"
exit 1
