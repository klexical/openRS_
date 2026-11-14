#!/usr/bin/env bash
# start-emulator.sh <system image> <boot limit seconds> — installs the image, creates the AVD "nightly", starts the
# emulator without a window and waits for its boot, for the nightly's launch check (#363). Needs ANDROID_HOME (the SDK);
# on success the emulator keeps running and adb's folder is added to $GITHUB_PATH when that is set.
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
#   - It prints the emulator's version (its first line) and the emulator's process id, and writes that id to emulator.pid
#     beside emulator.log, for launch-check.sh's failure diagnostics.
# EMULATOR_POLL_S (default 5) sets the seconds between boot checks; the tests shorten it.
set -euo pipefail
fail() { echo "::error::$*"; exit 1; }
[ $# -eq 2 ] || fail "usage: start-emulator.sh <system image> <boot limit seconds>"
IMAGE="$1"; BOOT_LIMIT_S="$2"; POLL_S="${EMULATOR_POLL_S:-5}"
[[ "$BOOT_LIMIT_S" =~ ^[0-9]+$ ]] || fail "boot limit '$BOOT_LIMIT_S' is not a number of seconds"
[[ "$POLL_S" =~ ^[1-9][0-9]*$ ]] || fail "EMULATOR_POLL_S '$POLL_S' is not a positive number of seconds"
[ -n "${ANDROID_HOME-}" ] || fail "ANDROID_HOME is not set"
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin"; EMULATOR="$ANDROID_HOME/emulator/emulator"; ADB="$ANDROID_HOME/platform-tools/adb"
AVD=nightly; LOG="$PWD/emulator.log"; PID_FILE="$PWD/emulator.pid"

export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"
for v in ANDROID_AVD_HOME ANDROID_USER_HOME ANDROID_SDK_HOME HOME XDG_CONFIG_HOME; do echo "$v=${!v-<unset>}"; done

# yes is killed by SIGPIPE once sdkmanager exits: under pipefail that would fail the run, hence (yes || true).
# sdkmanager's output stays in the log, so a package it cannot find is named there.
(yes || true) | "$SDKM/sdkmanager" --install "$IMAGE" emulator platform-tools
# The version is not pinned (sdkmanager installs the newest), and its banner is far from the log's end (#363): its first line
version=$(timeout 10 "$EMULATOR" -version 2>&1 || true)
echo "Emulator version: ${version%%$'\n'*}"
"$SDKM/avdmanager" create avd -n "$AVD" -k "$IMAGE" --force <<<"no"   # "no" to a custom hardware profile
echo                                                                   # its prompt ends without a newline
"$SDKM/avdmanager" list avd || echo "(avdmanager list avd failed with status $?; the check below still decides)"
if [ ! -f "$ANDROID_AVD_HOME/$AVD.ini" ]; then
  XDG_AVD="${XDG_CONFIG_HOME:+$XDG_CONFIG_HOME/.android}"
  echo "::error::no $AVD.ini in ANDROID_AVD_HOME ($ANDROID_AVD_HOME) after the create; .ini files under HOME/.android ($HOME/.android) and XDG_CONFIG_HOME/.android (${XDG_AVD:-<unset>}):"
  find "$HOME/.android" ${XDG_AVD:+"$XDG_AVD"} -name '*.ini' 2>/dev/null || true
  exit 1
fi

nohup "$EMULATOR" -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect > "$LOG" 2>&1 &
EMULATOR_PID=$!
# launch-check.sh reads it on a failure, to say whether this process is alive and to match the kernel's kill lines (#363)
echo "$EMULATOR_PID" > "$PID_FILE"
echo "Emulator process $EMULATOR_PID (in emulator.pid)"
STARTED=$SECONDS; waited=0
while :; do
  if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
    status=0; wait "$EMULATOR_PID" || status=$?
    echo "::error::the emulator exited (status $status) about $((SECONDS - STARTED)) s after its start, before it booted; the last lines of its log follow"
    tail -n 60 "$LOG" || true
    exit 1
  fi
  booted=$(timeout 10 "$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  waited=$((SECONDS - STARTED))
  [ "$booted" = 1 ] && break
  if [ "$waited" -ge "$BOOT_LIMIT_S" ]; then
    echo "::error::the emulator did not finish booting within ${BOOT_LIMIT_S} s; the last lines of its log follow"
    tail -n 60 "$LOG" || true
    exit 1
  fi
  sleep "$POLL_S"
done
echo "Emulator booted about ${waited} s after its start (AVD $AVD in $ANDROID_AVD_HOME)"
[ -z "${GITHUB_PATH-}" ] || echo "$ANDROID_HOME/platform-tools" >> "$GITHUB_PATH"
