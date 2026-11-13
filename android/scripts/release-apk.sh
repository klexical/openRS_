#!/usr/bin/env bash
# release-apk.sh <name> <tag> <nightly date or ""> — the build step of the shared release recipe,
# .github/workflows/release-apk.yml (#363). Called by CI's publish-release for an RC or stable tag (no date), and by the
# nightly with its UTC date. Checked before Gradle runs: the tag is android-v<name>; a date is eight digits and given
# exactly when the name is <base>-nightly.<date>. Then assembleRelease, with -PnightlyDate only for a nightly (the build
# refuses an empty one, #362); the output folder must hold exactly one APK, openRS_v<name>.apk; verify-release.sh checks
# it. Only then are path=, file= and sha256= written to $GITHUB_OUTPUT (and printed). The keystore and the API keys are
# the workflow's steps before this one. Run from anywhere; never prints a key.
set -euo pipefail
fail() { echo "::error::$*"; exit 1; }
[ $# -eq 3 ] || fail "usage: release-apk.sh <name> <tag> <nightly date or \"\">"
NAME="$1"; TAG="$2"; DATE="$3"
ANDROID_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUTDIR="$ANDROID_DIR/app/build/outputs/apk/release"

[ -n "$NAME" ] || fail "the release name is empty"
[ "$TAG" = "android-v$NAME" ] || fail "tag $TAG is not android-v$NAME, the tag of release name $NAME"
GRADLE_ARGS=(assembleRelease --no-daemon)
if [ -n "$DATE" ]; then
  [[ "$DATE" =~ ^[0-9]{8}$ ]] || fail "nightly date '$DATE' is not YYYYMMDD"
  [[ "$NAME" == *"-nightly.$DATE" ]] || fail "release name $NAME is not a nightly of $DATE (<base>-nightly.$DATE)"
  GRADLE_ARGS+=("-PnightlyDate=$DATE")
else
  [[ "$NAME" != *-nightly.* ]] || fail "release name $NAME is a nightly, but no nightly date was given"
fi

chmod +x "$ANDROID_DIR/gradlew"
(cd "$ANDROID_DIR" && ./gradlew "${GRADLE_ARGS[@]}")

APKS=()
for f in "$OUTDIR"/*.apk; do [ -e "$f" ] && APKS+=("$f"); done
[ "${#APKS[@]}" -eq 1 ] || fail "the build output must hold exactly one APK, openRS_v$NAME.apk; it holds ${#APKS[@]}"
APK="${APKS[0]}"; FILE="$(basename "$APK")"
[ "$FILE" = "openRS_v$NAME.apk" ] || fail "the build produced $FILE, not openRS_v$NAME.apk"

(cd "$ANDROID_DIR" && bash scripts/verify-release.sh "$TAG" "$APK")

if command -v sha256sum >/dev/null; then SHA=$(sha256sum "$APK" | awk '{print $1}'); else SHA=$(shasum -a 256 "$APK" | awk '{print $1}'); fi
[[ "$SHA" =~ ^[0-9a-f]{64}$ ]] || fail "could not compute the SHA-256 of $FILE"
printf 'path=%s\nfile=%s\nsha256=%s\n' "$APK" "$FILE" "$SHA" | tee -a "${GITHUB_OUTPUT:-/dev/null}"
