#!/usr/bin/env bash
# verify-release.sh <tag> <apk> — release identity checks run by CI's publish-release before an APK is attached (#214).
#   1. APK filename is openRS_v<name>.apk for tag android-v<name>
#   2. the APK's embedded versionName equals <name>
#   3. its versionCode is higher than every earlier android-v* tag's (no reused or lower code is ever distributed)
#   4. it is signed with the openRS_ release certificate
# Needs the Android SDK build-tools (aapt2, apksigner) and the git tags (checkout with fetch-depth 0).
set -euo pipefail
TAG="$1"; APK="$2"
EXPECTED_CERT_SHA256="c99b6f263ee3b5bb610040431403d459e0e7b12aa941f114f0d9717d974ada74"
fail() { echo "::error::$*"; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
NAME="${TAG#android-v}"
[ "$NAME" != "$TAG" ] || fail "tag $TAG is not android-v<version>"
[ -f "$APK" ] || fail "APK not found: $APK"

[ "$(basename "$APK")" = "openRS_v${NAME}.apk" ] || fail "APK $(basename "$APK") does not match tag $TAG (expected openRS_v${NAME}.apk)"

BADGING=$("$BT/aapt2" dump badging "$APK" | awk 'NR==1')
VN=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING")
VC=$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGING")
[ "$VN" = "$NAME" ] || fail "embedded versionName '$VN' does not match tag $TAG"

MAX=0; MAX_TAG=""
for t in $(git tag -l 'android-v*'); do
  [ "$t" = "$TAG" ] && continue
  c=$(git show "${t}:android/app/build.gradle.kts" 2>/dev/null \
      | grep -oE '(releaseVersionCode|versionCode) *= *[0-9]+' | grep -oE '[0-9]+$' | head -1 || true)
  if [ -n "$c" ] && [ "$c" -gt "$MAX" ]; then MAX=$c; MAX_TAG=$t; fi
done
[ "$VC" -gt "$MAX" ] || fail "versionCode $VC is not higher than $MAX already released as $MAX_TAG"

CERT=$("$BT/apksigner" verify --print-certs "$APK" 2>/dev/null | sed -n 's/.*certificate SHA-256 digest: //p' | awk 'NR==1')
[ "$CERT" = "$EXPECTED_CERT_SHA256" ] || fail "APK signer ${CERT:-none} is not the openRS_ release certificate"

echo "Release identity OK: $TAG → $(basename "$APK"), versionName $VN, versionCode $VC (> $MAX, $MAX_TAG), signer ${CERT:0:16}…"
