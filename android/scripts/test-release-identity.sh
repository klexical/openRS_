#!/usr/bin/env bash
# test-release-identity.sh — checks the release identity app/build.gradle.kts produces (#214, #362): with no property the
# build is the literal release in the file; -PnightlyDate=YYYYMMDD makes it a nightly of the same base, versionCode
# (YYYYMMDD − 20000000) × 10; a nightly date that is empty or not a real eight-digit date is refused with the reason.
# Reads the generated release BuildConfig (no signing, no keys, no APK). Needs a JDK and the Android SDK, as any build.
# Run from anywhere: bash android/scripts/test-release-identity.sh   (dates below are invented test values)
set -uo pipefail
ANDROID_DIR="$(cd "$(dirname "$0")/.." && pwd)"
GEN="$ANDROID_DIR/app/build/generated/source/buildConfig/release/com/openrs/dash/BuildConfig.java"
# The literal release in the file (the RC or stable path, unchanged by a nightly)
LITERAL_NAME=$(sed -n 's/^val releaseBase = "\([^"]*\)".*/\1/p' "$ANDROID_DIR/app/build.gradle.kts")
STAGE=$(sed -n 's/^val releaseStage = "\([^"]*\)".*/\1/p' "$ANDROID_DIR/app/build.gradle.kts")
RC=$(sed -n 's/^val releaseCandidate = \([0-9]*\).*/\1/p' "$ANDROID_DIR/app/build.gradle.kts")
[ -n "$STAGE" ] && LITERAL_NAME="$LITERAL_NAME-$STAGE.$RC"
LITERAL_CODE=$(sed -n 's/^val releaseVersionCode = \([0-9]*\).*/\1/p' "$ANDROID_DIR/app/build.gradle.kts")
BASE=$(sed -n 's/^val releaseBase = "\([^"]*\)".*/\1/p' "$ANDROID_DIR/app/build.gradle.kts")
failures=0

field() { sed -n "s/.*$1 = \(.*\);/\1/p" "$GEN" | tr -d '"'; }

# gen <args...>: generate the release BuildConfig; sets OUT and CODE (the Gradle exit status)
gen() {
  rm -f "$GEN"
  OUT=$("$ANDROID_DIR/gradlew" -p "$ANDROID_DIR" -q --console=plain :app:generateReleaseBuildConfig "$@" 2>&1); CODE=$?
}

pass() { echo "PASS $1"; }
fail() { echo "FAIL $1 — $2"; [ -n "${OUT:-}" ] && echo "$OUT" | tail -20 | sed 's/^/    /'; failures=$((failures + 1)); }

# expect_identity <case> <name> <code> <gradle args...>
expect_identity() {
  local name="$1" want_name="$2" want_code="$3"; shift 3
  gen "$@"
  if [ "$CODE" -ne 0 ]; then fail "$name" "build failed (exit $CODE)"; return; fi
  local rn vn vc; rn=$(field RELEASE_NAME); vn=$(field VERSION_NAME); vc=$(field VERSION_CODE)
  if [ "$rn" = "$want_name" ] && [ "$vn" = "$want_name" ] && [ "$vc" = "$want_code" ]; then pass "$name"
  else fail "$name" "RELEASE_NAME '$rn', VERSION_NAME '$vn', VERSION_CODE '$vc'; expected '$want_name', '$want_code'"; fi
}

# expect_refused <case> <text the message must contain> <gradle args...>
expect_refused() {
  local name="$1" needle="$2"; shift 2
  gen "$@"
  if [ "$CODE" -eq 0 ]; then fail "$name" "the build was not refused"; return; fi
  if [[ "$OUT" == *"$needle"* ]]; then pass "$name"; else fail "$name" "refused, but the message lacks '$needle'"; fi
}

expect_identity "no property: the literal release"         "$LITERAL_NAME"             "$LITERAL_CODE"
expect_identity "nightly 20261201 → 2612010"                "$BASE-nightly.20261201"    2612010  -PnightlyDate=20261201
expect_identity "nightly 20270101 → 2701010 (year change)"  "$BASE-nightly.20270101"    2701010  -PnightlyDate=20270101
expect_identity "nightly 20280229 (leap day) → 2802290"     "$BASE-nightly.20280229"    2802290  -PnightlyDate=20280229
expect_refused  "an empty nightly date is refused"          "nightlyDate"                         -PnightlyDate=
expect_refused  "seven digits are refused"                  "YYYYMMDD"                            -PnightlyDate=2026120
expect_refused  "a dashed date is refused"                  "YYYYMMDD"                            -PnightlyDate=2026-12-01
expect_refused  "month 13 is refused"                       "not a calendar date"                 -PnightlyDate=20261301
expect_refused  "29 February in a common year is refused"   "not a calendar date"                 -PnightlyDate=20270229
expect_refused  "a date before 2000 is refused"             "2000"                                -PnightlyDate=19991231

rm -f "$GEN"
[ "$failures" -eq 0 ] && echo "release identity checks: all passed" || { echo "release identity checks: $failures failed"; exit 1; }
