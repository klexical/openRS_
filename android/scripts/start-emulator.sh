#!/usr/bin/env bash
# start-emulator.sh <system image> <boot limit seconds> — installs the image, creates the AVD "nightly", starts the
# emulator without a window and waits for its boot, for the nightly's launch check (#363). Needs ANDROID_HOME (the SDK);
# on success the emulator keeps running and adb's folder is added to $GITHUB_PATH when that is set.
#   - The emulator runs with -gpu swangle; the GLES renderer its log names is printed on every end and must be swangle (#363).
#   - One AVD folder for both tools: avdmanager and the emulator do not agree on a default. GitHub's Ubuntu runner image
#     sets XDG_CONFIG_HOME; checked locally, with it set, avdmanager (cmdline-tools 20.0 and 12.0) resolves its AVD folder
#     to $XDG_CONFIG_HOME/.android/avd, while the emulator looks in $ANDROID_AVD_HOME, $ANDROID_SDK_HOME/avd and
#     $HOME/.android/avd ("Unknown AVD name", #363). So ANDROID_AVD_HOME is exported for both, and the folder is created
#     BEFORE the create: checked locally, avdmanager ignores an ANDROID_AVD_HOME that does not exist.
#   - The location variables and `avdmanager list avd` are printed, and nightly.ini must be in that folder before the
#     emulator starts; otherwise the run stops and lists every .ini under both candidate folders.
#   - The wait ends at once when the emulator process is gone (the launcher replaces itself with the emulator by execv,
#     so its process id stays the emulator's), with the last 60 lines of its log; it also ends at the boot limit, measured
#     by the clock from the emulator's start (a boot check can take up to its 10 s timeout).
#   - It prints the emulator's version (Pkg.Revision of the SDK's emulator/source.properties: `emulator -version` fails on
#     the runner for want of libpulse) and the emulator's process id, and writes that id to emulator.pid beside
#     emulator.log, for launch-check.sh's failure diagnostics.
#   - The emulator runs under a small wrapper that writes its exit status to emulator.exit (`exit=<status>`; 128 + the
#     signal for a death by signal). The wrapper gets the emulator's path from its environment, never on its command line,
#     so launch-check.sh's `pgrep -f 'qemu-system|emulator/emulator'` never finds the wrapper (#363).
#   - Before the start it asks the kernel to print the fatal-signal line (`kernel.print-fatal-signals=1`, through
#     `sudo -n sysctl`), and says whether that worked.
#   - For launch-check.sh's one retry (#363): the first start writes its own start time to emulator.started (a later start
#     keeps it: the job's clock) and the image to emulator.args. EMULATOR_SKIP_INSTALL=1 skips sdkmanager (the retry: the
#     packages are there, and it is a network call on the failing path).
# EMULATOR_POLL_S (default 5) sets the seconds between boot checks; the tests shorten it.
set -euo pipefail
fail() { echo "::error::$*"; exit 1; }
[ $# -eq 2 ] || fail "usage: start-emulator.sh <system image> <boot limit seconds>"
IMAGE="$1"; BOOT_LIMIT_S="$2"; POLL_S="${EMULATOR_POLL_S:-5}"
[[ "$BOOT_LIMIT_S" =~ ^[0-9]+$ ]] || fail "boot limit '$BOOT_LIMIT_S' is not a number of seconds"
[[ "$POLL_S" =~ ^[1-9][0-9]*$ ]] || fail "EMULATOR_POLL_S '$POLL_S' is not a positive number of seconds"
[ -n "${ANDROID_HOME-}" ] || fail "ANDROID_HOME is not set"
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin"; EMULATOR="$ANDROID_HOME/emulator/emulator"; ADB="$ANDROID_HOME/platform-tools/adb"
AVD=nightly; LOG="$PWD/emulator.log"; PID_FILE="$PWD/emulator.pid"; EXIT_FILE="$PWD/emulator.exit"
[ -f "$PWD/emulator.started" ] || date +%s > "$PWD/emulator.started"
printf '%s\n' "$IMAGE" > "$PWD/emulator.args"

export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"
for v in ANDROID_AVD_HOME ANDROID_USER_HOME ANDROID_SDK_HOME HOME XDG_CONFIG_HOME; do echo "$v=${!v-<unset>}"; done

# yes is killed by SIGPIPE once sdkmanager exits: under pipefail that would fail the run, hence (yes || true).
# sdkmanager's output stays in the log, so a package it cannot find is named there.
if [ "${EMULATOR_SKIP_INSTALL-}" = 1 ]; then echo "sdkmanager skipped (EMULATOR_SKIP_INSTALL=1: the packages are installed)"
else (yes || true) | "$SDKM/sdkmanager" --install "$IMAGE" emulator platform-tools; fi
# The version is not pinned (sdkmanager installs the newest), and its banner is far from the log's end (#363)
version=$(sed -n 's/^Pkg\.Revision=//p' "$ANDROID_HOME/emulator/source.properties" 2>/dev/null | head -n 1 || true)
echo "Emulator version: ${version:-(not found in emulator/source.properties)}"
"$SDKM/avdmanager" create avd -n "$AVD" -k "$IMAGE" --force <<<"no"   # "no" to a custom hardware profile
echo                                                                   # its prompt ends without a newline
"$SDKM/avdmanager" list avd || echo "(avdmanager list avd failed with status $?; the check below still decides)"
if [ ! -f "$ANDROID_AVD_HOME/$AVD.ini" ]; then
  XDG_AVD="${XDG_CONFIG_HOME:+$XDG_CONFIG_HOME/.android}"
  echo "::error::no $AVD.ini in ANDROID_AVD_HOME ($ANDROID_AVD_HOME) after the create; .ini files under HOME/.android ($HOME/.android) and XDG_CONFIG_HOME/.android (${XDG_AVD:-<unset>}):"
  find "$HOME/.android" ${XDG_AVD:+"$XDG_AVD"} -name '*.ini' 2>/dev/null || true
  exit 1
fi

# The kernel names the signal that ends a process once a crash handler has re-delivered it (#363; default off)
if sudo -n sysctl -w kernel.print-fatal-signals=1 >/dev/null 2>&1; then echo "kernel.print-fatal-signals: on"
else echo "kernel.print-fatal-signals: could not be set (sudo -n sysctl failed); the kernel will not name a fatal signal"; fi

rm -f "$PID_FILE" "$EXIT_FILE"
# The wrapper: the emulator's own process id into emulator.pid (launch-check.sh reads it on a failure, to say whether this
# process is alive and to match the kernel's lines), its exit status into emulator.exit
nohup env EMU_BIN="$EMULATOR" EMU_PID_FILE="$PID_FILE" EMU_EXIT_FILE="$EXIT_FILE" \
  bash -c '"$EMU_BIN" "$@" & p=$!; echo "$p" > "$EMU_PID_FILE"; s=0; wait "$p" || s=$?; echo "exit=$s" > "$EMU_EXIT_FILE"' \
  _ -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot -gpu swangle > "$LOG" 2>&1 &
for _ in $(seq 1 50); do [ -s "$PID_FILE" ] && break; sleep 0.1; done
EMULATOR_PID=$(cat "$PID_FILE" 2>/dev/null || true)
[ -n "$EMULATOR_PID" ] || fail "the emulator's wrapper wrote no process id within 5 s"
echo "Emulator process $EMULATOR_PID (in emulator.pid)"
# renderer_of_log: the GLES renderer the log's first gles_mode_selected line names, [A-Za-z0-9_] only (a value from the
# log never reaches a workflow command); empty when there is no such line
renderer_of_log() { sed -n '/gles_mode_selected:/{s/.*gles_mode_selected:\([A-Za-z0-9_]*\).*/\1/p;q;}' "$LOG" 2>/dev/null || true; }
# On a failed boot too, past the 60-line tail: a red night must still say whether swangle was chosen (QA's S1, #363)
say_renderer() {
  local g; g=$(renderer_of_log)
  if [ -n "$g" ]; then echo "The emulator's renderer: GLES $g (the log's first gles_mode_selected line)"
  else echo "The emulator's renderer: unknown (no gles_mode_selected line in its log)"; fi
}
STARTED=$SECONDS; waited=0
while :; do
  if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
    for _ in $(seq 1 20); do [ -s "$EXIT_FILE" ] && break; sleep 0.1; done   # the wrapper writes it as the emulator ends
    status=$(sed -n 's/^exit=//p' "$EXIT_FILE" 2>/dev/null || true)
    echo "::error::the emulator exited (status ${status:-unknown}) about $((SECONDS - STARTED)) s after its start, before it booted; the last lines of its log follow"
    say_renderer
    tail -n 60 "$LOG" || true
    exit 1
  fi
  booted=$(timeout 10 "$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  waited=$((SECONDS - STARTED))
  [ "$booted" = 1 ] && break
  if [ "$waited" -ge "$BOOT_LIMIT_S" ]; then
    echo "::error::the emulator did not finish booting within ${BOOT_LIMIT_S} s; the last lines of its log follow"
    say_renderer
    tail -n 60 "$LOG" || true
    exit 1
  fi
  sleep "$POLL_S"
done
echo "Emulator booted about ${waited} s after its start (AVD $AVD in $ANDROID_AVD_HOME)"
# The renderer is the night's proof (#363): under -gpu swiftshader_indirect the emulator crashed on its host RenderThread
# with the legacy SwiftShader GLES translator (gles_mode_selected:swiftshader, run 43731241198). The log's emuglConfig_init
# line names the GLES translator chosen; anything but swangle, or no line, stops here, with the emulator stopped.
gles=$(renderer_of_log)
if [ -z "$gles" ]; then
  echo "::error::the emulator's log has no gles_mode_selected line, so its renderer is unknown (start-emulator.sh asks for -gpu swangle); the last lines of its log follow"
  tail -n 60 "$LOG" || true; kill "$EMULATOR_PID" 2>/dev/null || true; exit 1
elif [ "$gles" != swangle ]; then
  echo "::error::the emulator selected the GLES renderer '$gles', not swangle (start-emulator.sh asks for -gpu swangle); its renderer lines follow"
  grep -E 'emuglConfig_init|Graphics backend|Graphics API Version' "$LOG" || true; kill "$EMULATOR_PID" 2>/dev/null || true; exit 1
fi
echo "Emulator renderer: GLES $gles (the log's emuglConfig_init line)"
[ -z "${GITHUB_PATH-}" ] || echo "$ANDROID_HOME/platform-tools" >> "$GITHUB_PATH"
