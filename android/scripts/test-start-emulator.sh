#!/usr/bin/env bash
# test-start-emulator.sh — checks start-emulator.sh, the nightly's emulator start (#363). Fakes stand in for the SDK's
# tools and behave as the real ones were seen to:
#   - avdmanager writes the AVD to $ANDROID_AVD_HOME only when that folder already exists, otherwise to
#     $XDG_CONFIG_HOME/.android/avd when that variable is set (the runner image sets it), otherwise to $HOME/.android/avd;
#   - the emulator looks in $ANDROID_AVD_HOME, $ANDROID_SDK_HOME/avd and $HOME/.android/avd, and exits at once with
#     "Unknown AVD name" when none holds the AVD.
# The cases: on a runner-like environment the AVD lands where the emulator looks and the boot is awaited; the AVD folder
# and the five location variables are printed (an unset one as <unset>), with `avdmanager list avd`; a create that leaves
# no nightly.ini stops before the emulator starts and lists every .ini found; an emulator that exits ends the wait at once
# with its log; one that never boots fails at the limit with its log; adb's folder reaches $GITHUB_PATH.
# Run from anywhere: bash android/scripts/test-start-emulator.sh
set -uo pipefail
SCRIPT="$(cd "$(dirname "$0")" && pwd)/start-emulator.sh"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/start-emulator-test.XXXXXX")
cleanup() { [ -f "$WORK/emu.pid" ] && kill "$(cat "$WORK/emu.pid")" 2>/dev/null; rm -rf "$WORK"; }
trap cleanup EXIT
failures=0; ran=0
IMAGE="system-images;android-36;google_apis;x86_64"
SDK="$WORK/sdk"; T="$SDK/cmdline-tools/latest/bin"
mkdir -p "$T" "$SDK/emulator" "$SDK/platform-tools" "$WORK/bin"

# ── Fakes: each records its call in $WORK/calls ──────────────────────────────────────────────────────────────────────
cat > "$T/sdkmanager" <<'STUB'
#!/usr/bin/env bash
echo "sdkmanager $*" >> "$WORK/calls"
echo "[=======================================] 100% Unzipping... (fake)"
STUB
cat > "$T/avdmanager" <<'STUB'
#!/usr/bin/env bash
echo "avdmanager $* | ANDROID_AVD_HOME=${ANDROID_AVD_HOME-<unset>}" >> "$WORK/calls"
if [ -n "${ANDROID_AVD_HOME-}" ] && [ -d "$ANDROID_AVD_HOME" ]; then d="$ANDROID_AVD_HOME"
elif [ -n "${XDG_CONFIG_HOME-}" ]; then d="$XDG_CONFIG_HOME/.android/avd"
else d="$HOME/.android/avd"; fi
case "$1 $2" in
  "create avd")
    printf 'Do you wish to create a custom hardware profile? [no] '; read -r _ || true
    [ "${FAKE_CREATE-}" = none ] && exit 0
    [ "${FAKE_CREATE-}" = xdg ] && d="$XDG_CONFIG_HOME/.android/avd"    # a tool that ignores ANDROID_AVD_HOME
    mkdir -p "$d/$4.avd"; printf 'path=%s/%s.avd\n' "$d" "$4" > "$d/$4.ini" ;;
  "list avd")
    echo "Available Android Virtual Devices:"
    for f in "$d"/*.ini; do [ -f "$f" ] && printf '    Name: %s\n    Path: %s\n' "$(basename "$f" .ini)" "${f%.ini}.avd"; done; exit 0 ;;
  *) echo "fake avdmanager: unexpected $*" >&2; exit 2 ;;
esac
STUB
cat > "$SDK/emulator/emulator" <<'STUB'
#!/usr/bin/env bash
echo "emulator $*" >> "$WORK/calls"
echo $$ > "$WORK/emu.pid"
name="$2"; found=""
for d in ${ANDROID_AVD_HOME:+"$ANDROID_AVD_HOME"} ${ANDROID_SDK_HOME:+"$ANDROID_SDK_HOME/avd"} "$HOME/.android/avd"; do
  [ -f "$d/$name.ini" ] && { found="$d"; break; }
done
if [ -z "$found" ] || [ "${FAKE_EMU-}" = exit ]; then
  echo "ERROR | Unknown AVD name [$name], use -list-avds to see valid list. (fake)"; exit 1
fi
echo "INFO | starting (fake, AVD in $found)"
[ "${FAKE_EMU-}" = hang ] || { sleep 1; : > "$WORK/booted"; }
while :; do sleep 1; done
STUB
cat > "$SDK/platform-tools/adb" <<'STUB'
#!/usr/bin/env bash
[ "$*" = "shell getprop sys.boot_completed" ] || { echo "fake adb: unexpected $*" >&2; exit 2; }
[ -f "$WORK/booted" ] || { echo "adb: no devices/emulators found" >&2; exit 1; }
printf '1\r\n'
STUB
# GNU timeout is not on every host: the fake runs the command without a limit
printf '#!/usr/bin/env bash\nshift; exec "$@"\n' > "$WORK/bin/timeout"
chmod +x "$T/sdkmanager" "$T/avdmanager" "$SDK/emulator/emulator" "$SDK/platform-tools/adb" "$WORK/bin/timeout"

# run <boot limit s> [env...]: a fresh runner-like home (HOME, XDG_CONFIG_HOME set; no ANDROID_* location variable)
run() {
  local limit="$1"; shift
  [ -f "$WORK/emu.pid" ] && kill "$(cat "$WORK/emu.pid")" 2>/dev/null
  rm -rf "$WORK/home" "$WORK/run" "$WORK/calls" "$WORK/booted" "$WORK/emu.pid" "$WORK/github_path"
  mkdir -p "$WORK/home/.config" "$WORK/run"; : > "$WORK/github_path"
  local t0=$SECONDS
  OUT=$(cd "$WORK/run" && env -u ANDROID_AVD_HOME -u ANDROID_USER_HOME -u ANDROID_SDK_HOME -u ANDROID_PREFS_ROOT \
        PATH="$WORK/bin:$PATH" WORK="$WORK" HOME="$WORK/home" XDG_CONFIG_HOME="$WORK/home/.config" \
        ANDROID_HOME="$SDK" GITHUB_PATH="$WORK/github_path" EMULATOR_POLL_S=1 "$@" \
        bash "$SCRIPT" "$IMAGE" "$limit" 2>&1); CODE=$?
  ELAPSED=$((SECONDS - t0))
}
check() {
  ran=$((ran + 1))
  if [ -z "$WHY" ]; then echo "PASS $1"; else echo "FAIL $1 — $WHY"; echo "$OUT" | sed 's/^/    /'; failures=$((failures + 1)); fi
}
why() { WHY="${WHY:+$WHY; }$*"; }
calls() { sed "s#$WORK#<W>#g" "$WORK/calls" 2>/dev/null | tr '\n' '|'; }
has() { [[ "$OUT" == *"$1"* ]] || why "output lacks '$1'"; }

# ── A runner-like environment: the AVD lands where the emulator looks, and the boot is awaited ─────────────────────────
WHY=""; run 10
[ "$CODE" -eq 0 ] || why "exit $CODE"
[ -f "$WORK/home/.android/avd/nightly.ini" ] || why "no nightly.ini in \$HOME/.android/avd"
[ ! -e "$WORK/home/.config/.android/avd/nightly.ini" ] || why "the AVD was written to the XDG folder"
want="sdkmanager --install $IMAGE emulator platform-tools|avdmanager create avd -n nightly -k $IMAGE --force | ANDROID_AVD_HOME=<W>/home/.android/avd|avdmanager list avd | ANDROID_AVD_HOME=<W>/home/.android/avd|emulator -avd nightly -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect|"
[ "$(calls)" = "$want" ] || why "calls '$(calls)', expected '$want'"
check "on a runner-like home (XDG_CONFIG_HOME set) the AVD is created where the emulator looks, then the boot is awaited"

WHY=""
has "ANDROID_AVD_HOME=$WORK/home/.android/avd"; has "ANDROID_USER_HOME=<unset>"; has "ANDROID_SDK_HOME=<unset>"
has "HOME=$WORK/home"; has "XDG_CONFIG_HOME=$WORK/home/.config"
has "Path: $WORK/home/.android/avd/nightly.avd"
check "the five location variables (an unset one as <unset>) and avdmanager's list are printed"

WHY=""
[ "$(cat "$WORK/github_path")" = "$SDK/platform-tools" ] || why "GITHUB_PATH holds '$(cat "$WORK/github_path")'"
has "booted"
check "a booted emulator puts adb's folder on GITHUB_PATH"

# ── A create that leaves no nightly.ini: stop before the emulator, listing every .ini found ──────────────────────────────
WHY=""; run 10 FAKE_CREATE=none
[ "$CODE" -ne 0 ] || why "exit 0"
has "::error::"; has "nightly.ini"; has "$WORK/home/.android/avd"
[[ "$(calls)" != *"emulator -avd"* ]] || why "the emulator was started"
check "a create that leaves no nightly.ini stops before the emulator starts, naming the AVD folder"

WHY=""; run 10 FAKE_CREATE=xdg
[ "$CODE" -ne 0 ] || why "exit 0"
has "$WORK/home/.config/.android/avd/nightly.ini"
[[ "$(calls)" != *"emulator -avd"* ]] || why "the emulator was started"
check "an AVD written to the XDG folder anyway stops the start, and the failure lists that .ini (dry run 1's case)"

# ── An emulator that exits: the wait ends at once, with its log ────────────────────────────────────────────────────────
WHY=""; run 20 FAKE_EMU=exit
[ "$CODE" -ne 0 ] || why "exit 0"
has "::error::"; has "exited"; has "Unknown AVD name"
[ "$ELAPSED" -le 5 ] || why "it took ${ELAPSED} s with a 20 s limit"
check "an emulator that exits ends the wait at once, with the last lines of its log"

# ── An emulator that never boots: fail at the limit, with its log ──────────────────────────────────────────────────────
WHY=""; run 4 FAKE_EMU=hang
[ "$CODE" -ne 0 ] || why "exit 0"
has "::error::"; has "within 4 s"; has "starting (fake"
[ "$ELAPSED" -ge 3 ] || why "it gave up after ${ELAPSED} s, before the 4 s limit"
[ ! -s "$WORK/github_path" ] || why "GITHUB_PATH was written"
check "an emulator that never boots fails at the limit, with the last lines of its log"

# ── Arguments ─────────────────────────────────────────────────────────────────────────────────────────────────────────
WHY=""; run ten
[ "$CODE" -ne 0 ] && [[ "$OUT" == *"::error::"* ]] || why "a limit that is not a number was not refused"
[ ! -f "$WORK/calls" ] || why "a tool was called"
check "a boot limit that is not a number is refused before any tool runs"

WHY=""; run 10 ANDROID_HOME=
[ "$CODE" -ne 0 ] && [[ "$OUT" == *"::error::"* ]] || why "an empty ANDROID_HOME was not refused"
check "an empty ANDROID_HOME is refused"

[ "$ran" -gt 0 ] || { echo "emulator start checks: no case ran"; exit 1; }
[ "$failures" -eq 0 ] && echo "emulator start checks: all $ran passed" || { echo "emulator start checks: $failures of $ran failed"; exit 1; }
