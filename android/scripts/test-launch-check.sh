#!/usr/bin/env bash
# test-launch-check.sh — checks launch-check.sh, the nightly's launch check (#363): the Play
# Store is disabled first (.claude/rules/build-release.md: fresh Play-services images kill the app), the APK installs, the
# log is cleared BEFORE the cold start, and the run fails on a missing or changed process id after the wait, a verifier
# rejection, a fatal exception or an ANR. Every adb call runs under `timeout 60`; a call that fails or does not answer
# ends the check with a message naming its step, and every failure prints `adb devices`, the emulator processes, the
# device log's process and crash lines (never a line naming a key), the host's memory and kernel kill lines, and the last
# 200 lines of start-emulator.sh's emulator.log. A fake adb answers from fixtures, as Android does (`pidof` exits 1
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
  if [ "${FAKE_CLOSED_AT-}" = "$1" ]; then
    : > "$WORK/gone"; if [ -n "${FAKE_ERR_FILE-}" ]; then cat "$FAKE_ERR_FILE" >&2; else echo "error: closed" >&2; fi; exit 1
  fi
  if gone; then echo "error: no devices/emulators found" >&2; exit 1; fi
}
case "$*" in
  "get-state")                     if gone; then echo "error: no devices/emulators found" >&2; exit 1; fi; echo device ;;
  "devices")                       echo "List of devices attached"; gone || printf 'emulator-5554\tdevice\n'; echo ;;
  "install -r "*)                  hit install; echo "Success"; exit "${FAKE_INSTALL_EXIT:-0}" ;;
  "logcat -c")                     hit logcat-c; : > "$WORK/log-cleared" ;;
  "logcat -d")                     hit logcat-d; [ -f "$WORK/log-cleared" ] && cat "$WORK/log" ;;
  "logcat -d -b main,system,crash") if gone; then echo "- waiting for device -" >&2; exec sleep 30; fi; cat "$WORK/devlog" ;;
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
  *)                               echo "fake adb: unexpected $*" >&2; exit 2 ;;
esac
STUB
# ── Fake timeout (macOS has none): records the limit asked for, runs the command, and stops it after FAKE_TIMEOUT_S
#    (default 2) with GNU timeout's status 124. ──
cat > "$WORK/bin/timeout" <<'STUB'
#!/usr/bin/env bash
echo "$1 $2" >> "$WORK/timeouts"; shift
"$@" & child=$!
( sleep "${FAKE_TIMEOUT_S:-2}"; kill "$child" 2>/dev/null ) >/dev/null 2>&1 & killer=$!   # off the caller's pipes
if wait "$child"; then status=0; else status=$?; fi
if kill -0 "$killer" 2>/dev/null; then kill "$killer" 2>/dev/null; wait "$killer" 2>/dev/null; exit "$status"; fi
exit 124
STUB
# ── Fake free and sudo (dmesg): the host's memory and kernel log ──
printf '               total        used        free\nMem:           15990       14100         310\n' > "$WORK/free-out"
printf '%s\n' "[  812.1] usb 1-1: new device" "[  901.5] Out of memory: Killed process 2711 (qemu-system-x86)" > "$WORK/dmesg-out"
printf '#!/usr/bin/env bash\ncat "$WORK/free-out"\n' > "$WORK/bin/free"
printf '#!/usr/bin/env bash\n[ "$*" = "-n dmesg" ] && cat "$WORK/dmesg-out"\n' > "$WORK/bin/sudo"
chmod +x "$WORK/bin/free" "$WORK/bin/sudo"
# The device log's lines: process and crash lines pass, other lines and any line naming a key do not
printf '%s\n' "I ActivityManager: Start proc 4242:com.openrs.dash/u0a190 for top-activity" \
  "D SomeService: unrelated line" "E AndroidRuntime: Ensure the API Key FAKEKEY123 is set" \
  "I ActivityManager: Process com.openrs.dash (pid 4242) has died" > "$WORK/devlog"
# ── Fake pgrep: the emulator's process, as on the runner ──
cat > "$WORK/bin/pgrep" <<'STUB'
#!/usr/bin/env bash
[ -n "${FAKE_EMULATOR_PS-}" ] && { echo "$FAKE_EMULATOR_PS"; exit 0; }; exit 1
STUB
chmod +x "$WORK/bin/adb" "$WORK/bin/timeout" "$WORK/bin/pgrep"
# start-emulator.sh's log, in the working directory both steps share: 250 numbered lines
for i in $(seq 1 250); do echo "emulator log line $i"; done > "$WORK/cwd/emulator.log"

# run <wait seconds> <log text> [env...]  (from $WORK/cwd, where emulator.log is)
run() {
  local wait="$1" log="$2"; shift 2
  rm -f "$WORK/calls" "$WORK/log-cleared" "$WORK/pidof-n" "$WORK/pidof-times" "$WORK/gone" "$WORK/timeouts"
  printf '%s' "$log" > "$WORK/log"
  OUT=$(cd "$WORK/cwd" && env PATH="$WORK/bin:$PATH" WORK="$WORK" FAKE_PID1=4242 FAKE_PID2=4242 \
        FAKE_EMULATOR_PS="2711 qemu-system-x86_64-headless -avd nightly" "$@" bash "$SCRIPT" "$APK" "$wait" 2>&1); CODE=$?
}
check() {
  ran=$((ran + 1))
  if [ -z "$WHY" ]; then echo "PASS $1"; else echo "FAIL $1 — $WHY"; echo "$OUT" | sed 's/^/    /'; failures=$((failures + 1)); fi
}
why() { WHY="${WHY:+$WHY; }$*"; }
calls() { sed 's/ [^ ]*\.apk$/ <apk>/' "$WORK/calls" 2>/dev/null | grep -vxE 'get-state|devices' | tr '\n' '|'; }
has() { [[ "$OUT" == *"$1"* ]] || why "output lacks '$1'"; }

# expect_fail <case> <message> <log> [env...]
expect_fail() {
  local c="$1" needle="$2" log="$3"; shift 3; WHY=""
  run 0 "$log" "$@"
  [ "$CODE" -ne 0 ] || why "exit 0"
  [[ "$OUT" == *"::error::"* && "$OUT" == *"$needle"* ]] || why "output lacks '::error::' and '$needle'"
  check "$c"
}
# diagnostics: what every failure prints after its message
diagnostics() {
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
}

WHY=""; run 0 "I ActivityManager: Start proc com.openrs.dash"
[ "$CODE" -eq 0 ] || why "exit $CODE"
want="shell pm disable-user --user 0 com.android.vending|install -r <apk>|logcat -c|shell am start -S -W -n com.openrs.dash/com.openrs.dash.ui.MainActivity|shell pidof com.openrs.dash || true|shell pidof com.openrs.dash || true|logcat -d|"
[ "$(calls)" = "$want" ] || why "calls '$(calls)', expected '$want'"
has "Launch check OK"
check "a clean launch passes, the Play Store disabled first and the log cleared before the start"

WHY=""; run 0 ""
n=$(grep -c . "$WORK/calls"); t=$(grep -c . "$WORK/timeouts" 2>/dev/null || echo 0)
[ "$t" -eq "$n" ] || why "$t of $n adb calls ran under timeout"
[ -z "$(grep -v '^60 adb$' "$WORK/timeouts" 2>/dev/null)" ] || why "a limit other than 'timeout 60 adb': $(sort -u "$WORK/timeouts" | tr '\n' ' ')"
check "every adb call runs under timeout 60"

WHY=""; run 1 ""
[ "$CODE" -eq 0 ] || why "exit $CODE"
gap=$(awk 'NR==1{a=$1} NR==2{printf "%.3f", $1-a}' "$WORK/pidof-times" 2>/dev/null)
awk -v g="${gap:-0}" 'BEGIN{exit !(g >= 0.9)}' || why "the second process check came ${gap:-?} s after the first, expected at least the 1 s wait"
check "the second process check comes after the wait"

WHY=""; run 0 "" FAKE_PM_EXIT=1
[ "$CODE" -eq 0 ] || why "exit $CODE"; check "an image without the Play Store still passes"

expect_fail "no process after the start (pidof exits 1, as on Android)" "not running" "" FAKE_PID1=
expect_fail "the process gone after the wait (pidof exits 1)"           "gone"        "" FAKE_PID2=
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

WHY=""; OUT=$(env PATH="$WORK/bin:$PATH" WORK="$WORK" bash "$SCRIPT" "$WORK/missing.apk" 0 2>&1); CODE=$?
[ "$CODE" -ne 0 ] && [[ "$OUT" == *"::error::"* ]] || why "a missing APK was not refused"; check "a missing APK is refused"
WHY=""; OUT=$(env PATH="$WORK/bin:$PATH" WORK="$WORK" bash "$SCRIPT" "$APK" ten 2>&1); CODE=$?
[ "$CODE" -ne 0 ] && [[ "$OUT" == *"::error::"* ]] || why "a wait that is not a number was not refused"; check "a wait that is not a number is refused"

[ "$ran" -gt 0 ] || { echo "launch check checks: no case ran"; exit 1; }
[ "$failures" -eq 0 ] && echo "launch check checks: all $ran passed" || { echo "launch check checks: $failures of $ran failed"; exit 1; }
