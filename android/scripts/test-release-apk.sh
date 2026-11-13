#!/usr/bin/env bash
# test-release-apk.sh — checks release-apk.sh, the shared release recipe's build step (#363): the RC / stable path passes
# no -PnightlyDate at all (the build refuses an empty one, #362), a nightly passes its date; the tag must be
# android-v<name> and the name a nightly of that date exactly when a date is given, checked before Gradle runs; the build
# output must hold exactly one APK, openRS_v<name>.apk; verify-release.sh runs on it; the path, file name and SHA-256
# are written for the workflow only when everything passed. A throwaway android/ folder holds a fake gradlew and a fake
# verify-release.sh (verify-release.sh has its own test). Dates are invented test values.
# Run from anywhere: bash android/scripts/test-release-apk.sh
set -uo pipefail
SCRIPT="$(cd "$(dirname "$0")" && pwd)/release-apk.sh"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/release-apk-test.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
A="$WORK/android"; OUTDIR="$A/app/build/outputs/apk/release"
failures=0; ran=0

# ── Fake android/: gradlew records its arguments and writes the APKs FAKE_APKS names; verify-release.sh records its
#    arguments and exits FAKE_VERIFY_EXIT ─────────────────────────────────────────────────────────────────────────────
mkdir -p "$A/scripts"
cp "$SCRIPT" "$A/scripts/release-apk.sh"
cat > "$A/gradlew" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$WORK/gradle-args"
mkdir -p "$OUTDIR"
for f in $FAKE_APKS; do printf 'apk %s\n' "$f" > "$OUTDIR/$f"; done
exit "${FAKE_GRADLE_EXIT:-0}"
STUB
cat > "$A/scripts/verify-release.sh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$WORK/verify-args"
exit "${FAKE_VERIFY_EXIT:-0}"
STUB
chmod +x "$A/gradlew"

sha256_of() { openssl dgst -sha256 "$1" | sed 's/.*= //'; }

# run <name> <tag> <nightly date> [env assignments...]: runs the recipe from the repository root, as the workflow does
run() {
  local name="$1" tag="$2" date="$3"; shift 3
  rm -rf "$OUTDIR" "$WORK/gradle-args" "$WORK/verify-args" "$WORK/gh-output"; : > "$WORK/gh-output"
  OUT=$(cd "$WORK" && env WORK="$WORK" OUTDIR="$OUTDIR" GITHUB_OUTPUT="$WORK/gh-output" "$@" \
        bash android/scripts/release-apk.sh "$name" "$tag" "$date" 2>&1); CODE=$?
}

# check <case> <condition...>: one case's verdict, from the conditions collected in WHY
check() {
  ran=$((ran + 1))
  if [ -z "$WHY" ]; then echo "PASS $1"; else echo "FAIL $1 — $WHY"; echo "$OUT" | sed 's/^/    /'; failures=$((failures + 1)); fi
}
why() { WHY="${WHY:+$WHY; }$*"; }
expect_exit()   { [ "$CODE" -eq "$1" ] || why "exit $CODE, expected $1"; }
expect_out()    { [[ "$OUT" == *"$1"* ]] || why "output lacks '$1'"; }
gradle_args()   { tr '\n' ' ' < "$WORK/gradle-args" 2>/dev/null | sed 's/ $//'; }
gradle_ran()    { [ -f "$WORK/gradle-args" ]; }
verify_ran()    { [ -f "$WORK/verify-args" ]; }
no_outputs()    { [ ! -s "$WORK/gh-output" ] || why "outputs were written: $(tr '\n' ' ' < "$WORK/gh-output")"; }
output_is()     { grep -qx "$1=$2" "$WORK/gh-output" || why "output $1 is not '$2' ($(grep "^$1=" "$WORK/gh-output" || echo none))"; }
# same_file <base> <path> <file>: the path, absolute or relative to the folder <base>, names the file
same_file()     { [ -n "$2" ] && [ "$(cd "$1" && cd "$(dirname "$2")" 2>/dev/null && pwd -P)/$(basename "$2")" = "$(cd "$(dirname "$3")" && pwd -P)/$(basename "$3")" ]; }

# expect_built <case> <name> <tag> <date> <gradle arguments>: the recipe passes and reports the one APK
expect_built() {
  local c="$1" name="$2" tag="$3" date="$4" want_args="$5" apk="openRS_v$2.apk"; WHY=""
  run "$name" "$tag" "$date" FAKE_APKS="$apk"
  expect_exit 0
  [ "$(gradle_args)" = "$want_args" ] || why "gradle arguments '$(gradle_args)', expected '$want_args'"
  { [ "$(sed -n 1p "$WORK/verify-args" 2>/dev/null)" = "$tag" ] && same_file "$A" "$(sed -n 2p "$WORK/verify-args")" "$OUTDIR/$apk"; } \
    || why "verify-release.sh not run on ($tag, the APK)"
  output_is file "$apk"
  same_file "$WORK" "$(sed -n 's/^path=//p' "$WORK/gh-output")" "$OUTDIR/$apk" || why "output path does not name the APK"
  [ -f "$OUTDIR/$apk" ] && output_is sha256 "$(sha256_of "$OUTDIR/$apk")"
  check "$c"
}

# expect_refused <case> <message> <name> <tag> <date> <gradle runs: yes|no> [env...]: the recipe fails, writes no output
expect_refused() {
  local c="$1" needle="$2" name="$3" tag="$4" date="$5" gradle="$6"; shift 6; WHY=""
  run "$name" "$tag" "$date" "$@"
  expect_exit 1; expect_out "::error::"; expect_out "$needle"; no_outputs
  if [ "$gradle" = no ] && gradle_ran; then why "Gradle ran"; fi
  if [ "$gradle" = yes ] && ! gradle_ran; then why "Gradle did not run"; fi
  check "$c"
}

N=2.2.7-nightly.20261101
# The build: no -PnightlyDate for an RC or stable, the date for a nightly
expect_built "an RC passes no -PnightlyDate"        2.2.7-rc.8 android-v2.2.7-rc.8 "" "assembleRelease --no-daemon"
expect_built "a stable passes no -PnightlyDate"     2.2.7      android-v2.2.7      "" "assembleRelease --no-daemon"
expect_built "a nightly passes its date"            "$N"       "android-v$N"  20261101 "assembleRelease --no-daemon -PnightlyDate=20261101"

# The inputs, refused before Gradle runs
expect_refused "a tag that is not android-v<name>"      "android-v2.2.7-rc.8"   2.2.7-rc.8 android-v2.2.7-rc.7 ""      no
expect_refused "an empty name"                          "name"                  ""         android-v          ""      no
expect_refused "a date with a name that is no nightly"  "-nightly.20261101"     2.2.7-rc.8 android-v2.2.7-rc.8 20261101 no
expect_refused "a date that is not the name's"          "-nightly.20261102"     "$N"       "android-v$N"  20261102 no
expect_refused "a nightly name without a date"          "nightly"               "$N"       "android-v$N"  ""      no
expect_refused "a date that is not eight digits"        "YYYYMMDD"              "2.2.7-nightly.2026110" "android-v2.2.7-nightly.2026110" 2026110 no
expect_refused "a date carrying another argument"       "YYYYMMDD"              "2.2.7-nightly.20261101 -Px" "android-v2.2.7-nightly.20261101 -Px" "20261101 -Px" no

# The one-APK check on the build output
expect_refused "no APK in the build output"             "exactly one"   2.2.7-rc.8 android-v2.2.7-rc.8 "" yes FAKE_APKS=
expect_refused "two APKs in the build output"           "exactly one"   2.2.7-rc.8 android-v2.2.7-rc.8 "" yes "FAKE_APKS=openRS_v2.2.7-rc.8.apk openRS_v2.2.7-rc.7.apk"
expect_refused "one APK with another name"              "openRS_v2.2.7-rc.8.apk" 2.2.7-rc.8 android-v2.2.7-rc.8 "" yes FAKE_APKS=openRS_v2.2.7-rc.7.apk

# Failures of the build and of verify-release.sh stop the recipe
WHY=""; run 2.2.7-rc.8 android-v2.2.7-rc.8 "" FAKE_APKS=openRS_v2.2.7-rc.8.apk FAKE_GRADLE_EXIT=1
[ "$CODE" -ne 0 ] || why "exit 0"; no_outputs; ! verify_ran || why "verify-release.sh ran after a failed build"
check "a failed build stops before verify-release.sh"
WHY=""; run 2.2.7-rc.8 android-v2.2.7-rc.8 "" FAKE_APKS=openRS_v2.2.7-rc.8.apk FAKE_VERIFY_EXIT=1
[ "$CODE" -ne 0 ] || why "exit 0"; no_outputs
check "a failed verify-release.sh writes no output"

[ "$ran" -gt 0 ] || { echo "release recipe checks: no case ran"; exit 1; }
[ "$failures" -eq 0 ] && echo "release recipe checks: all $ran passed" || { echo "release recipe checks: $failures of $ran failed"; exit 1; }
