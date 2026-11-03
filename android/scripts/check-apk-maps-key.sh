#!/usr/bin/env bash
# check-apk-maps-key.sh <apk> — fails when the APK's Maps key (meta-data com.google.android.geo.API_KEY) is missing,
# empty or an unexpanded placeholder (#235: the published rc.6 APK carried an empty value). Run by verify-release.sh;
# prints the key's length only, never its value.
set -euo pipefail
APK="$1"
fail() { echo "::error::$*"; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
[ -f "$APK" ] || fail "APK not found: $APK"
XML=$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK") || fail "cannot read the manifest of $(basename "$APK")"

# The element's name attribute, then its value attribute: `android:value(0x01010024)="…"` (aapt2's xmltree format)
VALUE=$(awk '
  /android:name\(0x01010003\)="com\.google\.android\.geo\.API_KEY"/ { found = 1; next }
  found && /android:value\(0x01010024\)=/ {
    s = $0; sub(/.*android:value\(0x01010024\)="/, "", s); sub(/".*/, "", s); print "VALUE:" s; exit
  }
  found && /E: / { exit }
  END { if (!found) print "ABSENT" }' <<<"$XML")

case "$VALUE" in
  ABSENT)    fail "$(basename "$APK") has no com.google.android.geo.API_KEY meta-data" ;;
  "VALUE:")  fail "$(basename "$APK") has an empty Maps key — the build had no MAPS_API_KEY (#235)" ;;
  VALUE:*'${'*) fail "$(basename "$APK") has an unexpanded Maps key placeholder" ;;
  VALUE:*)   echo "Maps key: present in $(basename "$APK") ($(( ${#VALUE} - 6 )) chars)" ;;
  *)         fail "$(basename "$APK"): the Maps key meta-data has no value attribute" ;;
esac
