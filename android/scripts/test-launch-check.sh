#!/usr/bin/env bash
# test-launch-check.sh — checks launch-check.sh, the nightly's launch check (#363): the Play
# Store is disabled first (.claude/rules/build-release.md: fresh Play-services images kill the app), the APK installs, the
# log is cleared BEFORE the cold start, and the run fails on a missing or changed process id after the wait, a verifier
# rejection, a fatal exception or an ANR. Every adb call runs under `timeout 60`; a call that fails or does not answer
# ends the check with a message naming its step, and every failure prints `adb devices`, the emulator processes, the
# emulator.pid process (alive or gone, and whether the kernel's kill lines name it), the device log's process and crash
# lines and the events buffer's kills and deaths (a line naming a key left out, the APK's two key values redacted by their
# shape: Maps AIza…, weather appid=… or a bare 32-hex run; a 40-hex run stays), the host's memory and kernel kill lines,
# `df -h`, the emulator's exit signatures from the whole emulator.log and its last 200 lines, and an end marker; each read
# that fails, is cut short or finds nothing says so. A fake adb answers from fixtures, as Android does (`pidof` exits 1
# when nothing matches, and adb passes the status on; with the device gone, logcat waits), and records the order of calls.
# Run from anywhere: bash android/scripts/test-launch-check.sh
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$DIR/launch-check.sh"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/launch-check-test.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
failures=0; ran=0
APK="$WORK/openRS_v2.2.7-nightly.20261110.apk"; : > "$APK"
mkdir -p "$WORK/bin" "$WORK/cwd"

# ── Fake adb: records each call in $WORK/calls. pidof answers FAKE_PID1 then FAKE_PID2 and exits 1 when it prints
#    nothing (as Android's does), unless the device command ends in "|| true". FAKE_CLOSED_AT / FAKE_HANG_AT name one
#    call (pm, install, start, pidof1, pidof2, logcat-d) that prints adb's "error: closed" and exits 1, or never answers;
#    after a closed call the device is gone (get-state and devices say so). ──
cat > "$WORK/bin/adb" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$WORK/calls"
gone() { [ -f "$WORK/gone" ]; }
hit() {   # hit <name>: act on FAKE_CLOSED_AT / FAKE_HANG_AT for this call
  if [ "${FAKE_HANG_AT-}" = "$1" ]; then exec sleep 30; fi   # exec: the fake timeout's kill reaches the sleep
  if [ "${FAKE_CLOSED_AT-}" = "$1" ] && { [ ! -f "$WORK/restarted" ] || [ -n "${FAKE_VANISH_AGAIN-}" ]; }; then
    : > "$WORK/gone"; if [ -n "${FAKE_ERR_FILE-}" ]; then cat "$FAKE_ERR_FILE" >&2; else echo "error: closed" >&2; fi; exit 1
  fi
  if gone; then echo "error: no devices/emulators found" >&2; exit 1; fi
}
case "$*" in
  "get-state")                     if gone; then echo "error: no devices/emulators found" >&2; exit 1; fi; echo device ;;
  "devices")                       echo "List of devices attached"; gone || printf 'emulator-5554\tdevice\n'; echo ;;
  "install -r "*)                  hit install; echo "Success"; exit "${FAKE_INSTALL_EXIT:-0}" ;;
  "logcat -c")                     hit logcat-c; : > "$WORK/log-cleared" ;;
  "logcat -d")                     [ "${FAKE_CLOSED_AT-}" = logcat-d-midway ] && { cat "$WORK/log"; : > "$WORK/gone"
                                     echo "error: closed" >&2; exit 1; }
                                   hit logcat-d; [ -f "$WORK/log-cleared" ] && cat "$WORK/log" ;;
  "logcat -d -b main,system,crash") if gone; then echo "- waiting for device -" >&2; exec sleep 30; fi
                                   [ -n "${FAKE_DEVLOG_PARTIAL-}" ] && { cat "$WORK/devlog"; exec sleep 30; }
                                   [ -n "${FAKE_DEVLOG_EMPTY-}" ] && { echo "I Zygote: unrelated"; exit 0; }; cat "$WORK/devlog" ;;
  "logcat -d -b events")           if gone; then echo "- waiting for device -" >&2; exec sleep 30; fi
                                   [ -n "${FAKE_EVENTS_PARTIAL-}" ] && { cat "$WORK/events"; exec sleep 30; }; cat "$WORK/events" ;;
  "shell am start "*)              hit start; [ -f "$WORK/log-cleared" ] || cp "$WORK/log" "$WORK/log-before"
                                   printf 'Status: ok\r\nActivity: %s\r\nComplete\r\n' "${FAKE_TOP-com.openrs.dash/.ui.MainActivity}"
                                   exit "${FAKE_START_EXIT:-0}" ;;
  "shell pidof com.openrs.dash"*)  perl -MTime::HiRes=time -e 'printf "%.3f\n", time' >> "$WORK/pidof-times"
                                   n=$(cat "$WORK/pidof-n" 2>/dev/null || echo 0); n=$((n + 1)); echo $n > "$WORK/pidof-n"
                                   hit "pidof$n"
                                   if [ $n -eq 1 ]; then p="${FAKE_PID1-}"; else p="${FAKE_PID2-}"; fi
                                   [ -z "$p" ] || printf '%s\r\n' "$p"
                                   [ -n "$p" ] || [[ "$*" == *"|| true" ]] || exit 1 ;;
  "shell pm disable-user"*)        hit pm; exit "${FAKE_PM_EXIT:-0}" ;;
  "shell p="*)                     gone && { echo "error: no devices/emulators found" >&2; exit 1; }   # the liveness trace
                                   printf 'pid:%s\r\n' "${FAKE_TRACE_PID-${FAKE_PID1-}}" ;;
  "kill-server")                   : ;;
  *)                               echo "fake adb: unexpected $*" >&2; exit 2 ;;
esac
STUB
# ── Fake timeout (macOS has none): records the limit asked for, runs the command, and stops it after FAKE_TIMEOUT_S
#    (default 2) with GNU timeout's status 124. ──
cat > "$WORK/bin/timeout" <<'STUB'
#!/usr/bin/env bash
echo "$1 $2" >> "$WORK/timeouts"; shift
"$@" & child=$!
# The timer marks the timeout BEFORE it kills, and the marker decides: asking whether the timer is still alive raced
# with its own exit (a killed call then read as status 143, not 124)
mark="$WORK/timed-out.$$"; rm -f "$mark"
( sleep "${FAKE_TIMEOUT_S:-2}"; : > "$mark"; kill "$child" 2>/dev/null ) >/dev/null 2>&1 & killer=$!   # off the caller's pipes
if wait "$child"; then status=0; else status=$?; fi
kill "$killer" 2>/dev/null; wait "$killer" 2>/dev/null
if [ -f "$mark" ]; then rm -f "$mark"; exit 124; fi
exit "$status"
STUB
# ── Fake free and sudo (dmesg): the host's memory and kernel log ──
printf '               total        used        free\nMem:           15990       14100         310\n' > "$WORK/free-out"
printf '%s\n' "[  812.1] usb 1-1: new device" "[  901.5] Out of memory: Killed process 2711 (qemu-system-x86)" > "$WORK/dmesg-out"
printf '#!/usr/bin/env bash\ncat "$WORK/free-out"\n' > "$WORK/bin/free"
printf '#!/usr/bin/env bash\n[ -n "${FAKE_SUDO_FAIL-}" ] && { echo "sudo: a password is required" >&2; exit 1; }\n[ "$*" = "-n dmesg" ] || exit 2\n[ -n "${FAKE_DMESG_EMPTY-}" ] && { echo "[    1.0] usb 1-1: new device"; exit 0; }\ncat "$WORK/dmesg-out"\n' > "$WORK/bin/sudo"
chmod +x "$WORK/bin/free" "$WORK/bin/sudo"
# The device log's lines: process and crash lines pass, other lines and any line naming a key do not, and crash lines
# carrying a key value without the word are printed with the value redacted. The two values are built here, so no
# key-shaped literal is in this file: the Maps form AIza + 35 characters, the weather form appid=<32 hex>.
MAPS_VALUE="AIza$(printf 'Q%.0s' $(seq 1 35))"; WEATHER_VALUE="$(printf '0f%.0s' $(seq 1 16))"
APPID_OTHER="$(printf 'Zq%.0s' $(seq 1 12))"; BARE_A="$(printf 'a1%.0s' $(seq 1 16))"; BARE_B="$(printf 'B2%.0s' $(seq 1 16))"; HEX40="$(printf 'c3%.0s' $(seq 1 20))"
printf '%s\n' "I ActivityManager: Start proc 4242:com.openrs.dash/u0a190 for top-activity" \
  "D SomeService: unrelated line" "E AndroidRuntime: Ensure the API Key FAKEKEY123 is set" \
  "E AndroidRuntime: java.lang.IllegalStateException: denied for $MAPS_VALUE in request" \
  "E AndroidRuntime: Caused by: java.io.IOException: GET /data/2.5/weather?lat=1&appid=$WEATHER_VALUE&units=metric" \
  "E AndroidRuntime: Caused by: java.io.IOException: GET /data/2.5/forecast?appid=$APPID_OTHER&lang=en" \
  "E AndroidRuntime: Caused by: java.lang.IllegalArgumentException: bad token $BARE_A" \
  "E AndroidRuntime: tokens [$BARE_A:$BARE_B] rejected" \
  "F DEBUG   : Fatal signal 6 (SIGABRT), BuildId: $HEX40" \
  "I ActivityManager: Process com.openrs.dash (pid 4242) has died" > "$WORK/devlog"
# The device's events buffer: kills and deaths pass, other events and any line naming a key do not, values redacted.
# The format is a real API 36.1 emulator's (am_kill with a space before its colon); the times are made up. killinfo's was
# not seen locally (no low-memory kill happened); it follows the same tag form.
BARE_C="$(printf 'd4%.0s' $(seq 1 16))"
cat > "$WORK/events" <<EVENTS
01-01 00:00:01.000   738  2108 I am_kill : [0,1473,com.google.android.gms.persistent,0,bg anr,185584]
01-01 00:00:02.000   738  2101 I am_proc_died: [0,1473,com.google.android.gms.persistent,0,19]
01-01 00:00:03.000   738   769 I am_proc_start: [0,2218,10224,com.openrs.dash,next-top-activity,{com.openrs.dash/com.openrs.dash.ui.MainActivity}]
01-01 00:00:04.000   738   757 I sysui_multi_action: [757,1,758,2]
01-01 00:00:05.000   738  2101 I am_proc_died: [0,2218,com.openrs.dash,0,19]
01-01 00:00:06.000   738  2299 I am_kill : [0,3001,com.example.apikeys,0,bg anr,1000]
01-01 00:00:07.000   227   227 I killinfo: [3002,10230,900,$BARE_C]
EVENTS
# ── Fake pgrep: the emulator's process, as on the runner ──
cat > "$WORK/bin/pgrep" <<'STUB'
#!/usr/bin/env bash
[ -n "${FAKE_PGREP_FAIL-}" ] && { echo "pgrep: cannot read /proc" >&2; exit 3; }
case "$*" in
  *crashpad_handler*) [ -n "${FAKE_HELPER_PS-}" ] && { echo "$FAKE_HELPER_PS"; exit 0; }; exit 1 ;;
esac
[ -n "${FAKE_EMULATOR_PS-}" ] && { echo "$FAKE_EMULATOR_PS"; exit 0; }; exit 1
STUB
# ── Fake start-emulator.sh (the retry's restart): records its arguments and EMULATOR_SKIP_INSTALL, brings the device
#    back with a fresh log and process count; FAKE_RESTART_FAIL fails it ──
cat > "$WORK/bin/start-emulator" <<'STUB'
#!/usr/bin/env bash
echo "$* skip=${EMULATOR_SKIP_INSTALL-}" >> "$WORK/restarts"
[ -n "${FAKE_RESTART_FAIL-}" ] && { echo "::error::the emulator did not finish booting (fake)"; exit 1; }
rm -f "$WORK/gone" "$WORK/pidof-n" "$WORK/log-cleared"; : > "$WORK/restarted"
for i in $(seq 1 3); do echo "second emulator log line $i"; done > "$PWD/emulator.log"
echo "Emulator booted (fake restart)"
STUB
chmod +x "$WORK/bin/adb" "$WORK/bin/timeout" "$WORK/bin/pgrep" "$WORK/bin/start-emulator"
# start-emulator.sh's log, in the working directory both steps share: 250 numbered lines
for i in $(seq 1 250); do
  case $i in
    5) echo "WARNING | detected a hanging thread 'QEMU2 main loop'. No response for 30001 ms" ;;
    7) echo "error: kvm run failed Bad address" ;;
    *) echo "emulator log line $i" ;;
  esac
done > "$WORK/cwd/emulator.log"
cp "$WORK/cwd/emulator.log" "$WORK/emulator.log.with-signatures"
for i in $(seq 1 250); do echo "emulator log line $i"; done > "$WORK/emulator.log.plain"
# start-emulator.sh's emulator.pid: a process id that has ended, and one the kernel log names
sleep 0 & DEAD_PID=$!; wait "$DEAD_PID"; echo "$DEAD_PID" > "$WORK/cwd/emulator.pid"
printf '%s\n' "[  812.1] usb 1-1: new device" "[  901.5] Out of memory: Killed process 2711 (qemu-system-x86)" \
  "[  901.6] oom-kill:constraint=CONSTRAINT_NONE,task=qemu-system-x86,pid=$DEAD_PID,uid=1001" > "$WORK/dmesg-out"

# run <wait seconds> <log text> [env...]  (from $WORK/cwd, where emulator.log is)
run() {
  local wait="$1" log="$2"; shift 2
  rm -f "$WORK/calls" "$WORK/log-cleared" "$WORK/pidof-n" "$WORK/pidof-times" "$WORK/gone" "$WORK/timeouts" "$WORK/restarted"
  printf '%s' "$log" > "$WORK/log"
  rm -f "$WORK/summary" "$WORK/restarts"
  OUT=$(cd "$WORK/cwd" && env PATH="$WORK/bin:$PATH" WORK="$WORK" FAKE_PID1=4242 FAKE_PID2=4242 \
        FAKE_EMULATOR_PS="2711 qemu-system-x86_64-headless -avd nightly" LAUNCH_TRACE_S=0.2 LAUNCH_HANDLER_WAIT_S=1 \
        LAUNCH_CRASH_DIR="$WORK/crash" LAUNCH_START_EMULATOR="$WORK/bin/start-emulator" GITHUB_STEP_SUMMARY="$WORK/summary" \
        "$@" bash "$SCRIPT" "$APK" "$wait" 2>&1); CODE=$?
}
check() {
  ran=$((ran + 1))
  if [ -z "$WHY" ]; then echo "PASS $1"; else echo "FAIL $1 — $WHY"; echo "$OUT" | sed 's/^/    /'; failures=$((failures + 1)); fi
}
why() { WHY="${WHY:+$WHY; }$*"; }
calls() { sed 's/ [^ ]*\.apk$/ <apk>/' "$WORK/calls" 2>/dev/null | grep -vxE 'get-state|devices' | grep -v '^shell p=' | tr '\n' '|'; }
has() { [[ "$OUT" == *"$1"* ]] || why "output lacks '$1'"; }

# expect_fail <case> <message> <log> [env...]
expect_fail() {
  local c="$1" needle="$2" log="$3"; shift 3; WHY=""
  run 0 "$log" "$@"
  [ "$CODE" -ne 0 ] || why "exit 0"
  [[ "$OUT" == *"::error::"* && "$OUT" == *"$needle"* ]] || why "output lacks '::error::' and '$needle'"
  check "$c"
}
# section <header>: the lines under a diagnostics header, up to the next "--- " header
section() { awk -v h="--- $1" 'index($0, h) == 1 {on = 1; next} /^--- / {on = 0} on' <<<"$OUT"; }
# diagnostics: what every failure prints after its message
diagnostics() {
  has "--- end of diagnostics"
  has "--- emulator.log: exit signatures (whole file, the last 20)"
  [[ "$(section "emulator.log: exit signatures")" == *"5:WARNING | detected a hanging thread 'QEMU2 main loop'"* ]] || why "the hanging-thread line (line 5) is not in the signatures"
  [[ "$(section "emulator.log: exit signatures")" == *"7:error: kvm run failed Bad address"* ]] || why "the KVM line (line 7) is not in the signatures"
  has "--- disk (df -h)"
  [[ "$(section "disk")" == *"Filesystem"* ]] || why "no df table under the disk header"
  has "--- the emulator's process (emulator.pid)"
  has "--- the device's events: process kills and deaths (the last 20)"
  has "adb devices"; has "List of devices attached"
  has "2711 qemu-system-x86_64-headless -avd nightly"
  has "emulator log line 250"; has "emulator log line 51"
  [[ "$OUT" != *"emulator log line 50"$'\n'* ]] || why "more than the last 200 lines of emulator.log"
  has "Mem:           15990"; has "Out of memory: Killed process 2711"
  [[ "$OUT" != *"usb 1-1"* ]] || why "a kernel line that is not a kill or out-of-memory line"
}
# devicelog: the device log's process lines are shown, other lines and any line naming a key are not
devicelog() {
  has "Process com.openrs.dash (pid 4242) has died"; has "Start proc 4242:com.openrs.dash"
  [[ "$OUT" != *"unrelated line"* ]] || why "a device log line that is not a process or crash line"
  [[ "$OUT" != *"FAKEKEY123"* ]] || why "a device log line naming a key was printed"
  [[ "$OUT" != *"$MAPS_VALUE"* ]] || why "a Maps-shaped key value (AIza…) on a crash line was printed"
  [[ "$OUT" != *"$WEATHER_VALUE"* ]] || why "a weather key value (appid=…) on a crash line was printed"
  has "denied for AIza<redacted> in request"; has "appid=<redacted>&units=metric"
  [[ "$OUT" != *"$BARE_A"* && "$OUT" != *"$BARE_B"* ]] || why "a bare 32-hex value (the weather key without appid=) was printed"
  has "bad token <redacted>"; has "tokens [<redacted>:<redacted>] rejected"
  [[ "$OUT" != *"$APPID_OTHER"* ]] || why "an appid= value that is not 32 hex was printed"; has "forecast?appid=<redacted>&lang=en"
  has "BuildId: $HEX40"
}

WHY=""; run 0 "I ActivityManager: Start proc com.openrs.dash"
[ "$CODE" -eq 0 ] || why "exit $CODE"
want="shell pm disable-user --user 0 com.android.vending|install -r <apk>|logcat -c|shell am start -S -W -n com.openrs.dash/com.openrs.dash.ui.MainActivity|shell pidof com.openrs.dash || true|shell pidof com.openrs.dash || true|logcat -d|"
[ "$(calls)" = "$want" ] || why "calls '$(calls)', expected '$want'"
has "Launch check OK"
check "a clean launch passes, the Play Store disabled first and the log cleared before the start"

WHY=""; run 0 ""
n=$(grep -v -c '^shell p=' "$WORK/calls"); t=$(grep -c '^60 adb$' "$WORK/timeouts" 2>/dev/null || echo 0)
[ "$t" -eq "$n" ] || why "$t of $n adb calls ran under timeout 60"
[ -z "$(grep ' adb$' "$WORK/timeouts" | grep -v -E '^(60|3) adb$')" ] || why "an adb call under another limit: $(sort -u "$WORK/timeouts" | tr '\n' ' ')"
[ "$(grep -c '^shell p=' "$WORK/calls")" -eq "$(grep -c '^3 adb$' "$WORK/timeouts")" ] || why "a liveness trace call not under timeout 3"
check "every adb call runs under timeout 60, the liveness trace's under timeout 3"

WHY=""; run 1 ""
[ "$CODE" -eq 0 ] || why "exit $CODE"
gap=$(awk 'NR==1{a=$1} NR==2{printf "%.3f", $1-a}' "$WORK/pidof-times" 2>/dev/null)
awk -v g="${gap:-0}" 'BEGIN{exit !(g >= 0.9)}' || why "the second process check came ${gap:-?} s after the first, expected at least the 1 s wait"
check "the second process check comes after the wait"

WHY=""; run 0 "" FAKE_PM_EXIT=1
[ "$CODE" -eq 0 ] || why "exit $CODE"; check "an image without the Play Store still passes"

WHY=""; run 0 "" FAKE_CLOSED_AT=pm
[[ "$OUT" != *"Play Store not present on this image"* ]] || why "an adb failure at the Play Store step was called an image without it"
has "Play Store not disabled (not on this image, or adb failed)"; has "::error::installing the APK"
check "adb failing at the Play Store step is not called a missing Play Store, and the install step is named"

expect_fail "no process after the start (pidof exits 1, as on Android)" "not running" "" FAKE_PID1=
expect_fail "the process gone after the wait (pidof exits 1)"           "is gone 0 s after the start" "" FAKE_PID2=
expect_fail "a different process id after the wait" "4243"                   "" FAKE_PID2=4243
expect_fail "a verifier rejection"                  "Verifier rejected"      "W art: Verifier rejected class DrivePageKt\$x"
expect_fail "a fatal exception"                     "FATAL EXCEPTION"        "E AndroidRuntime: FATAL EXCEPTION: main"
expect_fail "an ANR"                                "ANR in com.openrs.dash" "E ActivityManager: ANR in com.openrs.dash"
expect_fail "an install that fails"                 "install"                "" FAKE_INSTALL_EXIT=1
expect_fail "a start that fails"                    "start"                  "" FAKE_START_EXIT=1

# The dry run 2 failure: adb's "error: closed" at any call after the start ends the check by name, says the connection
# is gone, and prints the diagnostics
for at in "pidof1:the process check after the start" "pidof2:the process check after the wait" "logcat-d:reading the log"; do
  WHY=""; start=$SECONDS; run 0 "" FAKE_CLOSED_AT="${at%%:*}"
  [ "$CODE" -ne 0 ] || why "exit 0"
  [ $((SECONDS - start)) -le 15 ] || why "took $((SECONDS - start)) s: a diagnostic waited for the device that is gone"
  has "::error::${at#*:}"; has "error: closed"; has "the emulator is no longer connected"
  diagnostics
  check "adb's 'error: closed' at ${at%%:*} is named ('${at#*:}'), with the diagnostics"
done

WHY=""; for i in $(seq 1 15000); do printf 'error: closed '; done > "$WORK/long-err"     # 210 kB: more than a pipe holds
run 0 "" FAKE_CLOSED_AT=pidof1 FAKE_ERR_FILE="$WORK/long-err"
has "::error::the process check after the start"; has "the emulator is no longer connected"
[ "$(grep -m1 '::error::' <<<"$OUT" | wc -c)" -lt 600 ] || why "the message holds more than 300 characters of adb's error"
check "a long adb error is cut short in the message, not fatal to it"

WHY=""; run 0 "I ActivityManager: Start proc com.openrs.dash"$'\n'"E Maps: Ensure the API Key FAKEKEY456 is set" FAKE_CLOSED_AT=logcat-d-midway
has "::error::reading the log"; has "the emulator is no longer connected"
[[ "$OUT" != *"FAKEKEY456"* ]] || why "the device log read before the connection closed was printed"
check "a log read that fails part-way prints none of what it read"

WHY=""; run 0 "" FAKE_INSTALL_EXIT=1
has "adb still sees the emulator"; diagnostics; devicelog
check "a failure with the emulator still connected says so, with the diagnostics"

WHY=""; start=$SECONDS; run 0 "" FAKE_HANG_AT=pidof1
[ "$CODE" -ne 0 ] || why "exit 0"
has "::error::the process check after the start"; has "did not answer within 60 s"
[ $((SECONDS - start)) -le 15 ] || why "took $((SECONDS - start)) s: the hung call was not stopped"
check "an adb call that never answers is stopped and named"

WHY=""; run 0 ""
[ "$CODE" -eq 0 ] || why "exit $CODE"
[[ "$OUT" != *"adb devices"* && "$OUT" != *"emulator log line"* ]] || why "a passing run printed the diagnostics"
check "a passing run does not print the diagnostics"
WHY=""; run 0 "" FAKE_EMULATOR_PS= FAKE_CLOSED_AT=pidof2
has "no emulator process"
check "a failure with no emulator process left says so"

WHY=""; rm -f "$WORK/cwd/emulator.log.keep"; mv "$WORK/cwd/emulator.log" "$WORK/cwd/emulator.log.keep"
run 0 "" FAKE_CLOSED_AT=pidof1; mv "$WORK/cwd/emulator.log.keep" "$WORK/cwd/emulator.log"
has "no emulator.log in"; has "::error::the process check after the start"
check "a failure without emulator.log says so"

WHY=""; grep -qF 'LOG="$PWD/emulator.log"' "$DIR/start-emulator.sh" || why "start-emulator.sh no longer writes \$PWD/emulator.log"
grep -qF 'EMULATOR_LOG="$PWD/emulator.log"' "$SCRIPT" || why "launch-check.sh no longer reads \$PWD/emulator.log"
check "both scripts use the same emulator.log, in the working directory both steps share"

WHY=""; run 0 "" FAKE_TOP=com.google.android.permissioncontroller/com.android.permissioncontroller.permission.ui.GrantPermissionsActivity
[ "$CODE" -eq 0 ] || why "exit $CODE"
has "on top after the start: com.google.android.permissioncontroller"
check "the activity on top after the start is named when it is not the app's"

WHY=""; run 0 "" TMPDIR="$WORK/no-such-dir"
[ "$CODE" -ne 0 ] || why "exit 0"
has "::error::the launch check stopped"; diagnostics
check "an unexpected failure still ends with a message of the check's own and the diagnostics"

# ── The emulator's exit signatures: every one research's guide lists (§6) is found anywhere in the log, redacted ─────
SIGS=("WARNING | detected a hanging thread 'QEMU2 main loop'. No response for 30001 ms"
  "ERROR | Failed hang detection predicate: 'system image'"
  "crashhandler_die: fatal: Emulator crashed"
  "FATAL | Emulator: exiting because of the internal error 'gl'"
  "error: kvm run failed Bad address"
  "KVM internal error. Suberror: 1"
  "KVM: unknown exit, hardware reason 3"
  "ERROR | Failed to initialize GL emulation."
  "ERROR | Failed to initialize FrameBuffer()."
  "ERROR | Failed to initialize Gfxstream renderer!"
  "ERROR | Failed to initialize gfxstream frontend!"
  "Unable to save snapshot: Not enough disk space. Please free up some space and try again."
  "qemu: write error: No space left on device")
WHY=""; { for s in "${SIGS[@]}"; do echo "$s"; done; echo "crashhandler_die: fatal: token $BARE_A"; for i in $(seq 1 250); do echo "emulator log line $i"; done; } > "$WORK/cwd/emulator.log"
run 0 "" FAKE_CLOSED_AT=pidof2; cp "$WORK/emulator.log.with-signatures" "$WORK/cwd/emulator.log"
sig=$(section "emulator.log: exit signatures")
for s in "${SIGS[@]}"; do [[ "$sig" == *"$s"* ]] || why "not found: '$s'"; done
[[ "$sig" != *"$BARE_A"* && "$sig" == *"crashhandler_die: fatal: token <redacted>"* ]] || why "a signature line's 32-hex value was not redacted"
check "every exit signature research's guide lists is found outside the tail, and its lines are redacted"

# ── A read cut short keeps what it read (#363: a timed-out read on a wedged device is when those lines matter) ────────
WHY=""; run 0 "" FAKE_INSTALL_EXIT=1 FAKE_DEVLOG_PARTIAL=1 FAKE_EVENTS_PARTIAL=1
d=$(section "the device log's process and crash lines"); e=$(section "the device's events")
[[ "$d" == *"(read cut short: adb logcat, exit 124)"* && "$d" == *"Process com.openrs.dash (pid 4242) has died"* ]] || why "the device log's partial read: '$d'"
[[ "$e" == *"(read cut short: adb logcat -b events, exit 124)"* && "$e" == *"I am_proc_died: [0,2218,com.openrs.dash,0,19]"* ]] || why "the events' partial read: '$e'"
[[ "$d" != *"FAKEKEY123"* && "$e" != *"apikeys"* ]] || why "a partial read skipped the key filter"
check "a read that is cut short says so and still shows the lines it read"

# ── Time limits on the local reads that can hang: df 10 s, ps 5 s, free 10 s ─────────────────────────────────────────
WHY=""; sleep 60 & LIVE=$!; echo "$LIVE" > "$WORK/cwd/emulator.pid"
run 0 "" FAKE_CLOSED_AT=pidof2; kill "$LIVE" 2>/dev/null; wait "$LIVE" 2>/dev/null; echo "$DEAD_PID" > "$WORK/cwd/emulator.pid"
for want in "10 df" "5 ps"; do grep -q -x "$want" "$WORK/timeouts" || why "no '$want' under timeout"; done
[ "$(grep -c -x '10 free' "$WORK/timeouts")" -eq 2 ] || why "$(grep -c -x '10 free' "$WORK/timeouts") of the 2 free calls (the baseline, the diagnostics) under timeout 10"
check "df, ps and free run under their time limits"

# ── What a failed night needs (#363): every section says "no matching lines" or "could not read", never nothing ─────
WHY=""; run 0 ""
b=$(grep -n -- '--- host memory before the cold start' <<<"$OUT" | cut -d: -f1); s=$(grep -n 'Status: ok' <<<"$OUT" | cut -d: -f1)
[ -n "$b" ] && [ -n "$s" ] && [ "$b" -lt "$s" ] || why "no host memory baseline before the cold start (line '${b:-none}', start '${s:-none}')"
[[ "$(section "host memory before the cold start")" == *"Mem:"* ]] || why "the baseline holds no free -m table"
check "a host memory baseline is printed before the cold start"

WHY=""; run 0 "" FAKE_CLOSED_AT=pidof2
p=$(section "the emulator's process (emulator.pid)")
[[ "$p" == *"pid $DEAD_PID: gone"* ]] || why "the ended emulator pid $DEAD_PID is not reported gone: '$p'"
[[ "$p" == *"the kernel's kill lines name pid $DEAD_PID: yes"* ]] || why "the kernel's oom-kill line for pid $DEAD_PID is not matched: '$p'"
has "--- end of diagnostics"
check "the emulator's pid from emulator.pid is reported gone, and matched against the kernel's kill lines"

WHY=""; sleep 60 & LIVE=$!; echo "$LIVE" > "$WORK/cwd/emulator.pid"
run 0 "" FAKE_CLOSED_AT=pidof2; kill "$LIVE" 2>/dev/null; wait "$LIVE" 2>/dev/null; echo "$DEAD_PID" > "$WORK/cwd/emulator.pid"
p=$(section "the emulator's process (emulator.pid)")
[[ "$p" == *"pid $LIVE: alive (sleep)"* ]] || why "a live pid is not reported alive with its command: '$p'"
[[ "$p" == *"the kernel's kill lines name pid $LIVE: no"* ]] || why "a pid the kernel did not kill is not reported so: '$p'"
check "a live emulator pid is reported alive with its command name, and not named by the kernel"

WHY=""; cp "$WORK/dmesg-out" "$WORK/dmesg-out.keep"
printf '%s\n' "[  901.6] oom-kill:constraint=CONSTRAINT_NONE,task=other,pid=${DEAD_PID}9,uid=1001" "[  901.7] Killed process ${DEAD_PID}7 (other)" > "$WORK/dmesg-out"
run 0 "" FAKE_CLOSED_AT=pidof2; cp "$WORK/dmesg-out.keep" "$WORK/dmesg-out"
[[ "$(section "the emulator's process (emulator.pid)")" == *"the kernel's kill lines name pid $DEAD_PID: no"* ]] || why "a kernel line naming pid ${DEAD_PID}9 or ${DEAD_PID}7 was taken for pid $DEAD_PID"
check "the kernel match takes the exact pid, not one that starts with it"

WHY=""; cp "$WORK/dmesg-out" "$WORK/dmesg-out.keep"
printf '%s\n' "[  880.1] audit: type=1400 apparmor=\"DENIED\" operation=\"open\" pid=$DEAD_PID comm=\"x\"" "[  880.2] something ppid=$DEAD_PID child" \
  "[  880.3] oom-kill:constraint=CONSTRAINT_NONE,task=other,pid=4,ppid=$DEAD_PID,uid=1001" > "$WORK/dmesg-out"
run 0 "" FAKE_CLOSED_AT=pidof2; cp "$WORK/dmesg-out.keep" "$WORK/dmesg-out"
[[ "$(section "the emulator's process (emulator.pid)")" == *"the kernel's kill lines name pid $DEAD_PID: no"* ]] || why "an audit pid= line or a ppid= line was taken for a kill line"
check "an audit pid= line and a ppid= line, also inside an out-of-memory line, are not kill lines for that pid"

WHY=""; cp "$WORK/dmesg-out" "$WORK/dmesg-out.keep"
printf '%s\n' "[  901.5] Out of memory: Killed process $DEAD_PID (qemu-system-x86) total-vm:1kB" > "$WORK/dmesg-out"
run 0 "" FAKE_CLOSED_AT=pidof2; cp "$WORK/dmesg-out.keep" "$WORK/dmesg-out"
[[ "$(section "the emulator's process (emulator.pid)")" == *"the kernel's kill lines name pid $DEAD_PID: yes"* ]] || why "'Killed process $DEAD_PID' alone is not matched"
check "a 'Killed process <pid>' line alone is matched"

WHY=""; mv "$WORK/cwd/emulator.pid" "$WORK/emulator.pid.keep"; run 0 "" FAKE_CLOSED_AT=pidof2; mv "$WORK/emulator.pid.keep" "$WORK/cwd/emulator.pid"
[[ "$(section "the emulator's process (emulator.pid)")" == *"(could not read: no emulator.pid in"* ]] || why "a missing emulator.pid is not reported"
check "a missing emulator.pid is reported, not left blank"

WHY=""; grep -qF 'PID_FILE="$PWD/emulator.pid"' "$DIR/start-emulator.sh" || why "start-emulator.sh no longer writes \$PWD/emulator.pid"
grep -qF 'EMULATOR_PID_FILE="$PWD/emulator.pid"' "$SCRIPT" || why "launch-check.sh no longer reads \$PWD/emulator.pid"
check "both scripts use the same emulator.pid, in the working directory both steps share"

WHY=""; run 0 "" FAKE_CLOSED_AT=pidof2 FAKE_SUDO_FAIL=1 FAKE_PGREP_FAIL=1
[[ "$(section "host kernel: killed processes")" == *"(could not read: sudo -n dmesg, exit 1)"* ]] || why "a failed kernel read is not reported"
[[ "$(section "emulator processes")" == *"(could not read: pgrep, exit 3)"* ]] || why "a failed pgrep is not reported"
[[ "$(section "the device log's process and crash lines")" == *"(could not read: adb logcat, exit 124)"* ]] || why "a device log read that timed out (device gone) is not reported"
[[ "$(section "the device's events")" == *"(could not read: adb logcat -b events, exit 124)"* ]] || why "an events read that timed out is not reported"
[[ "$(section "the emulator's process (emulator.pid)")" == *"the kernel's lines could not be read"* ]] || why "the pid match does not say the kernel read failed"
has "--- end of diagnostics"
check "a read that fails says '(could not read: …)' with its exit status, and the diagnostics still reach their end"

WHY=""; run 0 "" FAKE_INSTALL_EXIT=1 FAKE_DMESG_EMPTY=1 FAKE_DEVLOG_EMPTY=1
for h in "host kernel: killed processes" "the device log's process and crash lines"; do
  [[ "$(section "$h")" == *"(no matching lines)"* ]] || why "'$h' read fine, matched nothing, and does not say so"
done
cp "$WORK/emulator.log.plain" "$WORK/cwd/emulator.log"; run 0 "" FAKE_CLOSED_AT=pidof2; cp "$WORK/emulator.log.with-signatures" "$WORK/cwd/emulator.log"
[[ "$(section "emulator.log: exit signatures")" == *"(no matching lines)"* ]] || why "an emulator.log without exit signatures does not say so"
check "a read that works and matches nothing says '(no matching lines)'"

WHY=""; run 0 "" FAKE_INSTALL_EXIT=1
e=$(section "the device's events")
[[ "$e" == *"I am_kill : [0,1473,com.google.android.gms.persistent,0,bg anr,185584]"* ]] || why "an am_kill line is missing"
[[ "$e" == *"I am_proc_died: [0,2218,com.openrs.dash,0,19]"* ]] || why "the app's am_proc_died line is missing"
[[ "$e" == *"I killinfo: [3002,10230,900,<redacted>]"* ]] || why "the killinfo line is missing or its 32-hex value not redacted"
[[ "$e" != *"am_proc_start"* && "$e" != *"sysui_multi_action"* ]] || why "an event that is not a kill or a death was shown"
[[ "$e" != *"apikeys"* ]] || why "an events line naming a key was shown"
[[ "$e" != *"$BARE_C"* ]] || why "a 32-hex value in the events was printed"
check "the device's events show the kills and deaths, never a line naming a key, with key values redacted"

WHY=""; OUT=$(env PATH="$WORK/bin:$PATH" WORK="$WORK" bash "$SCRIPT" "$WORK/missing.apk" 0 2>&1); CODE=$?
[ "$CODE" -ne 0 ] && [[ "$OUT" == *"::error::"* ]] || why "a missing APK was not refused"; check "a missing APK is refused"
WHY=""; OUT=$(env PATH="$WORK/bin:$PATH" WORK="$WORK" bash "$SCRIPT" "$APK" ten 2>&1); CODE=$?
[ "$CODE" -ne 0 ] && [[ "$OUT" == *"::error::"* ]] || why "a wait that is not a number was not refused"; check "a wait that is not a number is refused"


# ── The one retry (#363): only for a vanished emulator. A fresh working folder each time (a retry renames emulator.*) ──
# run_vanish [env...]: the process check after the wait loses the device ("error: closed"), the emulator.pid process is
# gone and no emulator process is left; the trace answers the app's process id; the job started just now
run_vanish() {
  local cwd="$WORK/rcwd"; rm -rf "$cwd"; mkdir -p "$cwd"
  cp "$WORK/emulator.log.plain" "$cwd/emulator.log"; echo "$DEAD_PID" > "$cwd/emulator.pid"
  date +%s > "$cwd/emulator.started"; echo "system-images;android-36;google_apis;x86_64" > "$cwd/emulator.args"
  rm -f "$WORK/calls" "$WORK/log-cleared" "$WORK/pidof-n" "$WORK/gone" "$WORK/timeouts" "$WORK/restarted" "$WORK/summary" "$WORK/restarts"
  printf '%s' "" > "$WORK/log"
  OUT=$(cd "$cwd" && env PATH="$WORK/bin:$PATH" WORK="$WORK" FAKE_PID1=4242 FAKE_PID2=4242 FAKE_EMULATOR_PS= \
        FAKE_CLOSED_AT=pidof2 LAUNCH_TRACE_S=0.2 LAUNCH_HANDLER_WAIT_S=1 LAUNCH_CRASH_DIR="$WORK/crash" \
        LAUNCH_START_EMULATOR="$WORK/bin/start-emulator" GITHUB_STEP_SUMMARY="$WORK/summary" "$@" \
        bash "$SCRIPT" "$APK" 1 2>&1); CODE=$?
  SUMMARY=$(cat "$WORK/summary" 2>/dev/null)
}

WHY=""; run_vanish
[ "$CODE" -eq 0 ] || why "exit $CODE"
has "=== launch attempt 1 of 2"; has "=== launch attempt 2 of 2"; has "the emulator is no longer connected"; has "--- end of diagnostics"
has "Launch check OK"
[ "$(grep -c . "$WORK/restarts" 2>/dev/null)" = 1 ] || why "restarts: '$(cat "$WORK/restarts" 2>/dev/null)'"
grep -q '^system-images;android-36;google_apis;x86_64 [0-9]* skip=1$' "$WORK/restarts" || why "the restart's arguments '$(cat "$WORK/restarts")'"
grep -q '^kill-server$' "$WORK/calls" || why "adb kill-server was not called before the restart"
[ -f "$WORK/rcwd/emulator.attempt1.log" ] && [ -f "$WORK/rcwd/emulator.attempt1.pid" ] || why "attempt 1's emulator.log / .pid were not kept"
[[ "$SUMMARY" == "Launch check passed on attempt 2; attempt 1: the emulator vanished"* ]] || why "summary '$SUMMARY'"
check "a vanished emulator is started again once, without sdkmanager, and attempt 2 passes; the summary says so (#363)"

WHY=""; run_vanish FAKE_VANISH_AGAIN=1
[ "$CODE" -ne 0 ] || why "exit 0"
[ "$(grep -c . "$WORK/restarts" 2>/dev/null)" = 1 ] || why "restarted $(grep -c . "$WORK/restarts" 2>/dev/null) times"
[ "$(grep -c '^=== launch attempt' <<<"$OUT")" = 2 ] || why "not exactly two attempts"
[[ "$SUMMARY" == "Launch check failed on attempt 2 after a retry"* ]] || why "summary '$SUMMARY'"
check "attempt 2 vanishing too ends the check: no third attempt (#363)"

WHY=""; run_vanish FAKE_RESTART_FAIL=1
[ "$CODE" -ne 0 ] || why "exit 0"
[[ "$SUMMARY" == "Launch check failed on attempt 2: the emulator did not start again"* ]] || why "summary '$SUMMARY'"
[ "$(grep -c '^install ' "$WORK/calls")" = 1 ] || why "an install ran after the restart failed"
[[ "$OUT" != *"Launch check OK"* ]] || why "a check passed without an emulator"
check "an emulator that does not start again ends the check, with its summary line (#363)"

for c in "FAKE_EMULATOR_PS=2711 qemu-system-x86_64-headless -avd nightly:an emulator process is still running" \
         "FAKE_TRACE_PID=:no sample answered after the process check|the app's process was none" \
         "FAKE_TRACE_PID=5151:the app's process was 5151 at the last answer, not 4242" \
         "LAUNCH_TRACE_S=600:no sample answered after the process check"; do
  WHY=""; run_vanish "${c%%:*}"
  [ "$CODE" -ne 0 ] || why "exit 0"
  [ ! -s "$WORK/restarts" ] || why "the emulator was started again"
  grep -qE "No retry: (${c#*:})" <<<"$OUT" || why "no 'No retry: ${c#*:}' line"
  [[ "$SUMMARY" == "Launch check failed; no retry: "* ]] || why "summary '$SUMMARY'"
  check "no retry (fail closed) when ${c%%:*}: '${c#*:}' (#363)"
done

WHY=""; run_vanish FAKE_CLOSED_AT=install FAKE_PID1=
[ "$CODE" -ne 0 ] || why "exit 0"; [ ! -s "$WORK/restarts" ] || why "the emulator was started again"
grep -q "No retry: no liveness trace" <<<"$OUT" || why "no 'no liveness trace' refusal"
check "a vanish before the cold start has no trace, so no retry (fail closed) (#363)"

# R2: the device-log reads both time out on this path (the device is gone), so the trace alone refuses an app that died
WHY=""; run_vanish FAKE_TRACE_PID=
[[ "$(section "the device log's process and crash lines")" == *"(could not read: adb logcat, exit 124)"* ]] || why "the device-log read did not time out"
[ ! -s "$WORK/restarts" ] || why "the emulator was started again although the app was gone at the last answer"
check "with the device log unreadable, the trace alone refuses a retry when the app was gone (QA's R2) (#363)"

WHY=""; run 0 "" FAKE_INSTALL_EXIT=1
grep -q "No retry: the failure was not a lost connection to the emulator" <<<"$OUT" || why "no refusal line"
[ "$(cat "$WORK/summary" 2>/dev/null)" = "Launch check failed; no retry: the failure was not a lost connection to the emulator" ] || why "summary '$(cat "$WORK/summary" 2>/dev/null)'"
check "a failure with the emulator still connected is never retried (#363)"

for f in "FAKE_PID2=:is gone" "FAKE_PID2=4243:restarted"; do
  WHY=""; run 0 "" "${f%%:*}"
  [ "$CODE" -ne 0 ] || why "exit 0"; [ ! -s "$WORK/restarts" ] || why "the emulator was started again"
  has "${f#*:}"; check "the app's own failure ('${f#*:}') is never retried (#363)"
done
WHY=""; run 0 "E AndroidRuntime: FATAL EXCEPTION: main"
[ ! -s "$WORK/restarts" ] || why "the emulator was started again"; check "a fatal exception is never retried (#363)"

WHY=""; cwd="$WORK/rcwd"; run_vanish
started_late=$(( $(date +%s) - 700 ))
rm -rf "$cwd"; mkdir -p "$cwd"; cp "$WORK/emulator.log.plain" "$cwd/emulator.log"; echo "$DEAD_PID" > "$cwd/emulator.pid"
echo "$started_late" > "$cwd/emulator.started"; echo "img" > "$cwd/emulator.args"
rm -f "$WORK/calls" "$WORK/pidof-n" "$WORK/gone" "$WORK/restarted" "$WORK/summary" "$WORK/restarts"
OUT=$(cd "$cwd" && env PATH="$WORK/bin:$PATH" WORK="$WORK" FAKE_PID1=4242 FAKE_PID2=4242 FAKE_EMULATOR_PS= FAKE_CLOSED_AT=pidof2 \
      LAUNCH_TRACE_S=0.2 LAUNCH_HANDLER_WAIT_S=1 LAUNCH_CRASH_DIR="$WORK/crash" LAUNCH_START_EMULATOR="$WORK/bin/start-emulator" \
      GITHUB_STEP_SUMMARY="$WORK/summary" bash "$SCRIPT" "$APK" 1 2>&1); CODE=$?
[ ! -s "$WORK/restarts" ] || why "started again with too little time"
grep -qE "No retry: not enough time: -?[0-9]+ s of boot time left, 120 s needed" <<<"$OUT" || why "no time refusal"
check "no retry when the job's time cannot hold a second boot (#363)"

WHY=""; run_vanish
b=$(awk '{print $2}' "$WORK/restarts")
[ "${b:-0}" -le 420 ] && [ "${b:-0}" -ge 120 ] || why "attempt 2's boot limit $b is not within 120..420"
check "attempt 2's boot limit is what the job has left, at most 420 s (#363)"

# The new diagnostics: the exit status, helper processes, kernel fault lines with fixed fields only, the crash folder,
# the trace and the load
REG_HEX="$(printf '%s' 'AIzaSyQQ' | od -An -tx1 | tr -d ' \n')"
cp "$WORK/dmesg-out" "$WORK/dmesg-out.keep"
printf '%s\n' "[  950.1] QEMU2 main loop[2590]: segfault at 41417a6153795151 ip 00007f1234567890 sp 00007ffd12345678 error 6 in libgfxstream_backend.so[7f1234000000+800000] likely on CPU 2 (core 2, socket 0)" \
  "[  950.1] Code: 48 89 c7 e8 00 00 00 00 $REG_HEX" \
  "[  950.2] /usr/local/lib/android/sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64: QEMU2 main loop: potentially unexpected fatal signal 11." \
  "[  950.2] RAX: $REG_HEX RBX: 0000000000000000 RCX: 00007f00deadbeef" \
  "[  950.2] RIP: 0033:0x7f1234567890" \
  "[  950.3] traps: Render Thread[2601] general protection fault ip:7f00aa sp:7ffdbb error:0 in libGLESv2.so[7f00+1000]" \
  "[  950.4] evil; name-is-too-long[2602]: segfault at 0 ip 0 sp 0 error 4 in /tmp/evil;$MAPS_VALUE.so[0+1]" >> "$WORK/dmesg-out"
echo "exit=139" > "$WORK/cwd/emulator.exit"; mkdir -p "$WORK/crash/completed"; printf 'x%.0s' $(seq 1 10) > "$WORK/crash/completed/3f2a.dmp"
WHY=""; run 0 "" FAKE_INSTALL_EXIT=1 FAKE_HELPER_PS="2780 crashpad_handler"
f=$(section "host kernel: fault and fatal-signal lines")
[[ "$f" == *"segfault: thread QEMU2 main loop, error 6 in libgfxstream_backend.so"* ]] || why "the segfault line's fixed fields"
[[ "$f" == *"fatal signal 11: thread QEMU2 main loop"* ]] || why "the fatal-signal line"
[[ "$f" == *"trap (general protection fault): thread Render Thread, error 0 in libGLESv2.so"* ]] || why "the trap line"
[[ "$f" == *"segfault: thread <name withheld>, error 4 in <name withheld>"* ]] || why "a name outside the rules was not withheld"
[[ "$OUT" != *"$REG_HEX"* && "$OUT" != *"41417a6153795151"* && "$OUT" != *"00007f1234567890"* ]] || why "a raw address or register value was printed"
[[ "$f" != *"RAX"* && "$f" != *"RIP"* && "$f" != *"Code:"* ]] || why "a register or Code: line was printed"
[[ "$OUT" != *"$MAPS_VALUE"* ]] || why "a key-shaped value in a kernel line was printed"
[[ "$(section "the emulator's exit status")" == *"exit=139 (signal 11: SEGV)"* ]] || why "the exit status line"
[[ "$(section "emulator helper processes")" == *"2780 crashpad_handler"* ]] || why "the helper process"
[[ "$(section "the crash database")" == *"completed/3f2a.dmp  10 bytes"* ]] || why "the crash folder's file"
[[ "$(section "the newest crash dump")" == *"could not decode: "* ]] || why "a dump that is not one should read 'could not decode'"
[[ "$(section "the liveness trace")" == *"(no trace: the check stopped before the cold start)"* ]] || why "the trace section before the cold start"
has "--- host load (uptime)"
check "the new diagnostics print fixed fields only: no raw address, register or Code: bytes (owner's rule, QA's V2) (#363)"
cp "$WORK/dmesg-out.keep" "$WORK/dmesg-out"; rm -f "$WORK/cwd/emulator.exit"; rm -rf "$WORK/crash"

WHY=""; run 0 "" FAKE_DMESG_EMPTY=1 FAKE_INSTALL_EXIT=1
[[ "$(section "host kernel: fault and fatal-signal lines")" == *"none is expected, so this decides nothing"* ]] || why "no-line wording"
check "no fault line is worded as deciding nothing (D1) (#363)"

for k in 1 2 3 4 5; do
  WHY=""; run 0 "" FAKE_START_EXIT=1
  sleep 0.5; left=$(pgrep -f -- "$SCRIPT" | tr '\n' ' ')
  [ -z "$left" ] || { why "launch-check.sh processes left running: $left"; pkill -f -- "$SCRIPT"; }
  [ -z "$WHY" ] || break
done
check "no liveness trace outlives a failed check, whatever moment it is stopped at (#363)"

WHY=""; run 1 "" FAKE_CLOSED_AT=logcat-d
t=$(section "the liveness trace")
grep -qE '^\+[0-9]+s emulator=(alive|gone) app=4242 load=[^ ]+ after=1$' <<<"$t" || why "no answered trace line after the start"
check "the liveness trace shows the app's process id after the start (#363)"

WHY=""; r=$(python3 -I "$DIR/test-crash-facts.py" 2>&1); [ $? -eq 0 ] || why "$r"
check "crash-facts.py prints only the four facts (test-crash-facts.py) (#363)"

[ "$ran" -gt 0 ] || { echo "launch check checks: no case ran"; exit 1; }
[ "$failures" -eq 0 ] && echo "launch check checks: all $ran passed" || { echo "launch check checks: $failures of $ran failed"; exit 1; }
