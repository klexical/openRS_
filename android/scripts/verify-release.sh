#!/usr/bin/env bash
# verify-release.sh <tag> <apk> — release identity checks run by CI's publish-release before an APK is attached (#214).
#   1. APK filename is openRS_v<name>.apk for tag android-v<name>
#   2. the APK's embedded versionName equals <name>
#   3. its versionCode is higher than every earlier android-v* tag's (no reused or lower code is ever distributed).
#      A nightly's code is the rule (YYYYMMDD − 20000000) × 10 of the date in its name — the same rule as
#      app/build.gradle.kts, 20261201 → 2612010 (#362): it must equal the rule for the tag under check, and an earlier
#      nightly tag's code is derived from its name (its build file holds the literal of the release it was built from).
#      A nightly is <X.Y.Z>-nightly.YYYYMMDD, its date a calendar date not after today (UTC). A stable above the last
#      literal code (39, rc.8) takes slot 9 of such a date, (YYYYMMDD − 20000000) × 10 + 9, whichever tags still exist.
#   4. it is signed with the openRS_ release certificate
#   5. it carries a Maps key (check-apk-maps-key.sh, #235 — length only, never the value); the weather key is optional
# Needs the Android SDK build-tools (aapt2, apksigner) and the git tags (checkout with fetch-depth 0).
set -euo pipefail
TAG="$1"; APK="$2"
EXPECTED_CERT_SHA256="c99b6f263ee3b5bb610040431403d459e0e7b12aa941f114f0d9717d974ada74"
fail() { echo "::error::$*"; exit 1; }

# is_date YYYYMMDD — a calendar date from 2000 on (bash 3.2: no date(1), whose flags differ between macOS and Linux)
is_date() {
  [[ "$1" =~ ^[0-9]{8}$ ]] || return 1
  local y=$((10#${1:0:4})) m=$((10#${1:4:2})) d=$((10#${1:6:2})) days
  [ "$y" -ge 2000 ] && [ "$m" -ge 1 ] && [ "$m" -le 12 ] && [ "$d" -ge 1 ] || return 1
  case $m in 4|6|9|11) days=30 ;; 2) days=28; if [ $((y % 4)) -eq 0 ] && { [ $((y % 100)) -ne 0 ] || [ $((y % 400)) -eq 0 ]; }; then days=29; fi ;; *) days=31 ;; esac
  [ "$d" -le "$days" ]
}
# nightly_code YYYYMMDD — the nightly rule (#362)
nightly_code() { echo $(( (10#$1 - 20000000) * 10 )); }
NIGHTLY_NAME='^[0-9]+\.[0-9]+\.[0-9]+-nightly\.[0-9]{8}$'
LAST_LITERAL_CODE=39     # rc.8: every release after it is on the date scale (#362)
TODAY=$(date -u +%Y%m%d)

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

case "$NAME" in
  *-nightly.*)
    [[ "$NAME" =~ $NIGHTLY_NAME ]] || fail "tag $TAG is not <X.Y.Z>-nightly.YYYYMMDD"
    D="${NAME##*-nightly.}"
    is_date "$D" || fail "tag $TAG: nightly date '$D' is not a calendar date YYYYMMDD from 2000"
    [ "$D" -le "$TODAY" ] || fail "tag $TAG: nightly date $D is after today ($TODAY UTC)"
    WANT=$(nightly_code "$D")
    [ "$VC" = "$WANT" ] || fail "versionCode $VC is not $WANT, the nightly rule's code for $TAG ((YYYYMMDD − 20000000) × 10)"
    ;;
esac

# Earlier tags whose versionCode can't be read from their build file. An entry needs a reason next to it; there are
# none — all 37 android-v* tags up to android-v2.2.7-rc.6 read (checked 2026-10-28). Any other unreadable tag fails.
LEGACY_TAGS=""

TAGS=$(git tag -l 'android-v*')
[ -n "$TAGS" ] || fail "no android-v* tags in this checkout — the versionCode check needs the tag history (actions/checkout fetch-depth: 0)"
MAX=0; MAX_TAG=""
for t in $TAGS; do
  [ "$t" = "$TAG" ] && continue
  case "$t" in
    *-nightly.*)   # the code is in the name; a name that is no <X.Y.Z>-nightly.<date> stays unreadable
      d="${t##*-nightly.}"; c=""
      if [[ "${t#android-v}" =~ $NIGHTLY_NAME ]] && is_date "$d"; then c=$(nightly_code "$d"); fi ;;
    *)
      c=$(git show "${t}:android/app/build.gradle.kts" 2>/dev/null \
          | grep -oE '(releaseVersionCode|versionCode) *= *[0-9]+' | grep -oE '[0-9]+$' | head -1 || true) ;;
  esac
  if [ -z "$c" ]; then
    case " $LEGACY_TAGS " in
      *" $t "*) echo "note: $t has no readable versionCode — listed in LEGACY_TAGS"; continue ;;
    esac
    fail "cannot read the versionCode of earlier tag $t (${t}:android/app/build.gradle.kts) — without it this release can't be shown to be higher; fix the lookup, or add $t to LEGACY_TAGS with the reason"
  fi
  if [ "$c" -gt "$MAX" ]; then MAX=$c; MAX_TAG=$t; fi
done
# A stable above the last literal code: slot 9 of a calendar date not after today, whether or not nightly tags still exist
# (they may be deleted by hand). Above that date's nightly is the check below; below the next date's nightly holds because
# the date is not in the future, and that nightly's own check refuses it if it were not above this release.
if [[ "$NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] && [ "$VC" -gt "$LAST_LITERAL_CODE" ]; then
  SD=$((VC / 10 + 20000000))
  [ $((VC % 10)) -eq 9 ] || fail "stable versionCode $VC is not in slot 9 of a date ((YYYYMMDD − 20000000) × 10 + 9), required above $LAST_LITERAL_CODE"
  is_date "$SD" || fail "stable versionCode $VC is slot 9 of $SD, which is not a calendar date"
  [ "$SD" -le "$TODAY" ] || fail "stable versionCode $VC is slot 9 of $SD, which is after today ($TODAY UTC)"
fi
[ "$VC" -gt "$MAX" ] || fail "versionCode $VC is not higher than $MAX already released as $MAX_TAG"

CERT=$("$BT/apksigner" verify --print-certs "$APK" 2>/dev/null | sed -n 's/.*certificate SHA-256 digest: //p' | awk 'NR==1')
[ "$CERT" = "$EXPECTED_CERT_SHA256" ] || fail "APK signer ${CERT:-none} is not the openRS_ release certificate"

bash "$(dirname "$0")/check-apk-maps-key.sh" "$APK"

echo "Release identity OK: $TAG → $(basename "$APK"), versionName $VN, versionCode $VC (> $MAX, $MAX_TAG), signer ${CERT:0:16}…"
