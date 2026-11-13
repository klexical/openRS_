#!/usr/bin/env bash
# test-release-identity.sh — checks the release identity app/build.gradle.kts produces (#214, #362): with no property the
# build is the literal release in the file; -PnightlyDate=YYYYMMDD makes it a nightly of the same base, versionCode
# (YYYYMMDD − 20000000) × 10; a nightly date that is empty, not a real eight-digit date or after the build's UTC date is
# refused with the reason, and so is the stage "nightly" written in the file (a nightly comes only from the property).
# Reads the generated release BuildConfig (no signing, no keys, no APK). Needs a JDK and the Android SDK, as any build.
# The stage case edits app/build.gradle.kts for one run and restores it byte for byte (checked; restored on exit too).
# Run from anywhere: bash android/scripts/test-release-identity.sh   (dates below are invented test values)
set -uo pipefail
ANDROID_DIR="$(cd "$(dirname "$0")/.." && pwd)"
GEN="$ANDROID_DIR/app/build/generated/source/buildConfig/release/com/openrs/dash/BuildConfig.java"
BUILD="$ANDROID_DIR/app/build.gradle.kts"
BACKUP=$(mktemp "${TMPDIR:-/tmp}/build.gradle.kts.XXXXXX"); cp "$BUILD" "$BACKUP"
trap 'cmp -s "$BACKUP" "$BUILD" || cp "$BACKUP" "$BUILD"; rm -f "$BACKUP"' EXIT
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

# with_stage <stage> <expect_* call...>: the same check with releaseStage = "<stage>" in the build file, then restored
with_stage() {
  local stage="$1"; shift
  sed "s/^val releaseStage = \"[^\"]*\"/val releaseStage = \"$stage\"/" "$BACKUP" > "$BUILD"
  grep -q "^val releaseStage = \"$stage\"" "$BUILD" || { fail "stage $stage" "could not set the stage"; cp "$BACKUP" "$BUILD"; return; }
  "$@"
  cp "$BACKUP" "$BUILD"; cmp -s "$BACKUP" "$BUILD" || { echo "FAIL could not restore $BUILD"; exit 1; }
}

TODAY=$(date -u +%Y%m%d)
expect_identity "no property: the literal release"         "$LITERAL_NAME"             "$LITERAL_CODE"
expect_identity "nightly 20251201 → 2512010"                "$BASE-nightly.20251201"    2512010  -PnightlyDate=20251201
expect_identity "nightly 20260101 → 2601010 (year change)"  "$BASE-nightly.20260101"    2601010  -PnightlyDate=20260101
expect_identity "nightly 20240229 (leap day) → 2402290"     "$BASE-nightly.20240229"    2402290  -PnightlyDate=20240229
expect_identity "nightly of today's UTC date"               "$BASE-nightly.$TODAY"      $(( (10#$TODAY - 20000000) * 10 ))  -PnightlyDate=$TODAY
expect_refused  "an empty nightly date is refused"          "nightlyDate"                         -PnightlyDate=
expect_refused  "seven digits are refused"                  "YYYYMMDD"                            -PnightlyDate=2025120
expect_refused  "a dashed date is refused"                  "YYYYMMDD"                            -PnightlyDate=2025-12-01
expect_refused  "month 13 is refused"                       "not a calendar date"                 -PnightlyDate=20251301
expect_refused  "29 February in a common year is refused"   "not a calendar date"                 -PnightlyDate=20250229
expect_refused  "a date before 2000 is refused"             "2000"                                -PnightlyDate=19991231
expect_refused  "a date after the build's UTC date is refused" "after today"                      -PnightlyDate=20991231
with_stage nightly expect_refused "the stage \"nightly\" in the file is refused"         "releaseStage"
with_stage nightly expect_refused "the stage \"nightly\" with a date is refused too"     "releaseStage"  -PnightlyDate=20251201

rm -f "$GEN"
[ "$failures" -eq 0 ] && echo "release identity checks: all passed" || { echo "release identity checks: $failures failed"; exit 1; }
