#!/usr/bin/env bash
# test-launch-check.sh — checks launch-check.sh, the nightly's launch check (#363; SPEC "What a run does" 5): the Play
# Store is disabled first (.claude/rules/build-release.md: fresh Play-services images kill the app), the APK installs, the
# log is cleared BEFORE the cold start, and the run fails on a missing or changed process id after the wait, a verifier
# rejection, a fatal exception or an ANR. A fake adb answers from fixtures and records the order of calls.
# Run from anywhere: bash android/scripts/test-launch-check.sh
set -uo pipefail
SCRIPT="$(cd "$(dirname "$0")" && pwd)/launch-check.sh"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/launch-check-test.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
failures=0; ran=0
APK="$WORK/openRS_v2.2.7-nightly.20261110.apk"; : > "$APK"

# ── Fake adb: records each call in $WORK/calls; pidof answers FAKE_PID1 then FAKE_PID2; logcat -d prints $WORK/log ──
mkdir -p "$WORK/bin"
cat > "$WORK/bin/adb" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$WORK/calls"
case "$*" in
  "install -r "*)                 exit "${FAKE_INSTALL_EXIT:-0}" ;;
  "logcat -c")                    : > "$WORK/log-cleared" ;;
  "logcat -d")                    [ -f "$WORK/log-cleared" ] && cat "$WORK/log" ;;
  "shell am start "*)             [ -f "$WORK/log-cleared" ] || cp "$WORK/log" "$WORK/log-before"; exit "${FAKE_START_EXIT:-0}" ;;
  "shell pidof com.openrs.dash")  n=$(cat "$WORK/pidof-n" 2>/dev/null || echo 0); n=$((n + 1)); echo $n > "$WORK/pidof-n"
                                  if [ $n -eq 1 ]; then printf '%s\r\n' "${FAKE_PID1-}"; else printf '%s\r\n' "${FAKE_PID2-}"; fi ;;
  "shell pm disable-user"*)       exit "${FAKE_PM_EXIT:-0}" ;;
  *)                              echo "fake adb: unexpected $*" >&2; exit 2 ;;
esac
STUB
chmod +x "$WORK/bin/adb"

# run <wait seconds> <log text> [env...]
run() {
  local wait="$1" log="$2"; shift 2
  rm -f "$WORK/calls" "$WORK/log-cleared" "$WORK/pidof-n"; printf '%s' "$log" > "$WORK/log"
  OUT=$(env PATH="$WORK/bin:$PATH" WORK="$WORK" FAKE_PID1=4242 FAKE_PID2=4242 "$@" bash "$SCRIPT" "$APK" "$wait" 2>&1); CODE=$?
}
check() {
  ran=$((ran + 1))
  if [ -z "$WHY" ]; then echo "PASS $1"; else echo "FAIL $1 — $WHY"; echo "$OUT" | sed 's/^/    /'; failures=$((failures + 1)); fi
}
why() { WHY="${WHY:+$WHY; }$*"; }
calls() { sed 's/ [^ ]*\.apk$/ <apk>/' "$WORK/calls" 2>/dev/null | tr '\n' '|'; }

# expect_fail <case> <message> <log> [env...]
expect_fail() {
  local c="$1" needle="$2" log="$3"; shift 3; WHY=""
  run 0 "$log" "$@"
  [ "$CODE" -ne 0 ] || why "exit 0"
  [[ "$OUT" == *"::error::"* && "$OUT" == *"$needle"* ]] || why "output lacks '::error::' and '$needle'"
  check "$c"
}

WHY=""; run 0 "I ActivityManager: Start proc com.openrs.dash"
[ "$CODE" -eq 0 ] || why "exit $CODE"
want="shell pm disable-user --user 0 com.android.vending|install -r <apk>|logcat -c|shell am start -S -W -n com.openrs.dash/com.openrs.dash.ui.MainActivity|shell pidof com.openrs.dash|shell pidof com.openrs.dash|logcat -d|"
[ "$(calls)" = "$want" ] || why "calls '$(calls)', expected '$want'"
check "a clean launch passes, the Play Store disabled first and the log cleared before the start"

WHY=""; run 0 "" FAKE_PM_EXIT=1
[ "$CODE" -eq 0 ] || why "exit $CODE"; check "an image without the Play Store still passes"

expect_fail "no process after the start"            "not running"            "" FAKE_PID1=
expect_fail "the process gone after the wait"       "gone"                   "" FAKE_PID2=
expect_fail "a different process id after the wait" "4243"                   "" FAKE_PID2=4243
expect_fail "a verifier rejection"                  "Verifier rejected"      "W art: Verifier rejected class DrivePageKt\$x"
expect_fail "a fatal exception"                     "FATAL EXCEPTION"        "E AndroidRuntime: FATAL EXCEPTION: main"
expect_fail "an ANR"                                "ANR in com.openrs.dash" "E ActivityManager: ANR in com.openrs.dash"
expect_fail "an install that fails"                 "install"                "" FAKE_INSTALL_EXIT=1
expect_fail "a start that fails"                    "start"                  "" FAKE_START_EXIT=1

WHY=""; OUT=$(env PATH="$WORK/bin:$PATH" WORK="$WORK" bash "$SCRIPT" "$WORK/missing.apk" 0 2>&1); CODE=$?
[ "$CODE" -ne 0 ] && [[ "$OUT" == *"::error::"* ]] || why "a missing APK was not refused"; check "a missing APK is refused"
WHY=""; OUT=$(env PATH="$WORK/bin:$PATH" WORK="$WORK" bash "$SCRIPT" "$APK" ten 2>&1); CODE=$?
[ "$CODE" -ne 0 ] && [[ "$OUT" == *"::error::"* ]] || why "a wait that is not a number was not refused"; check "a wait that is not a number is refused"

[ "$ran" -gt 0 ] || { echo "launch check checks: no case ran"; exit 1; }
[ "$failures" -eq 0 ] && echo "launch check checks: all $ran passed" || { echo "launch check checks: $failures of $ran failed"; exit 1; }
