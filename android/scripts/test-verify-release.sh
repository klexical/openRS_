#!/usr/bin/env bash
# test-verify-release.sh — checks verify-release.sh's release identity rules (#214, #362) without a real APK or SDK: a
# throwaway git repository holds the android-v* tags, and a fake SDK's aapt2 / apksigner report what a sidecar file
# next to each fake APK says. Covers the nightly code rule (YYYYMMDD − 20000000) × 10 — the same rule as
# app/build.gradle.kts, example 20261201 → 2612010 — earlier nightly tags read from their names, a stable's slot 9 once
# nightlies exist, and the RC / stable checks as before. Dates and keys are invented test values.
# Run from anywhere: bash android/scripts/test-verify-release.sh
set -uo pipefail
VERIFY="$(cd "$(dirname "$0")" && pwd)/verify-release.sh"
CERT_OK="c99b6f263ee3b5bb610040431403d459e0e7b12aa941f114f0d9717d974ada74"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/verify-release-test.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
failures=0

# ── Fake SDK: the tools read <apk>.meta (VC=, VN=, CERT=, MAPS=) ─────────────────────────────────────────────────────
BT="$WORK/sdk/build-tools/99.0.0"; mkdir -p "$BT"
cat > "$BT/aapt2" <<'STUB'
#!/usr/bin/env bash
apk="${@: -1}"; . "$apk.meta"
if [ "$2" = "badging" ]; then
  echo "package: name='com.openrs.dash' versionCode='$VC' versionName='$VN' platformBuildVersionName='15'"
else
  echo "N: android=http://schemas.android.com/apk/res/android"
  [ "$MAPS" = absent ] && exit 0
  echo "      E: meta-data (line=1)"
  echo "        A: http://schemas.android.com/apk/res/android:name(0x01010003)=\"com.google.android.geo.API_KEY\" (Raw: \"x\")"
  echo "        A: http://schemas.android.com/apk/res/android:value(0x01010024)=\"$MAPS\" (Raw: \"x\")"
fi
STUB
cat > "$BT/apksigner" <<'STUB'
#!/usr/bin/env bash
apk="${@: -1}"; . "$apk.meta"
echo "Signer #1 certificate DN: CN=test"
echo "Signer #1 certificate SHA-256 digest: $CERT"
STUB
chmod +x "$BT/aapt2" "$BT/apksigner"

# ── Throwaway repository: one commit per tag, its build file holding the literal code ──────────────────────────────
REPO="$WORK/repo"; mkdir -p "$REPO/android/app"
g() { git -C "$REPO" -c user.name=test -c user.email=test@example.invalid -c commit.gpgsign=false -c tag.gpgsign=false "$@"; }
g init -q
# tag_at <tag> <literal releaseVersionCode in that commit's build file>
tag_at() {
  printf 'val releaseBase = "2.2.7"\nval releaseVersionCode = %s\n' "$2" > "$REPO/android/app/build.gradle.kts"
  g add -A; g commit -q --allow-empty -m "$1"; g tag "$1"
}
tag_at android-v2.2.6       36       # the file's literal of each release (2.2.6's value is invented)
tag_at android-v2.2.7-rc.7  37
tag_at android-v2.2.7-rc.8  39

# case_ <name> <0 pass | 1 fail> <output must contain> <tag> <apk file name> <versionCode> [versionName] [cert] [maps]
case_() {
  local name="$1" want="$2" needle="$3" tag="$4" apk="$WORK/apks/$5" vc="$6"
  local vn="${7:-${tag#android-v}}" cert="${8:-$CERT_OK}" maps="${9:-AIzaTESTplaceholderMapsKey_0123456789-x}"
  rm -rf "$WORK/apks"; mkdir -p "$WORK/apks"; : > "$apk"
  printf 'VC=%s\nVN=%s\nCERT=%s\nMAPS=%s\n' "$vc" "$vn" "$cert" "$maps" > "$apk.meta"
  local out code why=""
  out=$(cd "$REPO" && ANDROID_HOME="$WORK/sdk" bash "$VERIFY" "$tag" "$apk" 2>&1); code=$?
  [ "$code" -eq "$want" ] || why="exit $code, expected $want"
  [[ "$out" == *"$needle"* ]] || why="${why:+$why; }output lacks '$needle'"
  if [ -z "$why" ]; then echo "PASS $name"; else echo "FAIL $name — $why"; echo "$out" | sed 's/^/    /'; failures=$((failures + 1)); fi
}

# Before any nightly: the RC and stable checks as before
case_ "an RC tag verifies as before"                 0 "Release identity OK" android-v2.2.7-rc.8 openRS_v2.2.7-rc.8.apk 39
case_ "an RC code not above rc.7 fails"              1 "not higher than 37"  android-v2.2.7-rc.8 openRS_v2.2.7-rc.8.apk 37
case_ "a stable before nightlies verifies as before" 0 "Release identity OK" android-v2.2.7     openRS_v2.2.7.apk      40
case_ "the wrong signer fails"                       1 "is not the openRS_ release certificate" android-v2.2.7-rc.8 openRS_v2.2.7-rc.8.apk 39 "" 0000
case_ "a missing Maps key fails"                     1 "has no com.google.android.geo.API_KEY" android-v2.2.7-rc.8 openRS_v2.2.7-rc.8.apk 39 "" "" absent

# The first nightly: code by the rule, above rc.8's 39
N1=android-v2.2.7-nightly.20261201
case_ "a correct nightly passes (20261201 → 2612010)" 0 "versionCode 2612010"            $N1 openRS_v2.2.7-nightly.20261201.apk 2612010
case_ "a nightly with a wrong code fails"             1 "is not 2612010, the nightly rule's code" $N1 openRS_v2.2.7-nightly.20261201.apk 2612011
case_ "a nightly at rc.8's 39 fails"                  1 "is not 2612010, the nightly rule's code" $N1 openRS_v2.2.7-nightly.20261201.apk 39
case_ "a nightly with a wrong APK name fails"         1 "does not match tag"             $N1 openRS_v2.2.7-nightly.2026121.apk 2612010
case_ "a nightly with another versionName fails"      1 "embedded versionName"           $N1 openRS_v2.2.7-nightly.20261201.apk 2612010 2.2.7-rc.8
case_ "a nightly tag that is not a calendar date fails" 1 "not a calendar date"          android-v2.2.7-nightly.20261301 openRS_v2.2.7-nightly.20261301.apk 2613010

# Earlier nightly tags: their code comes from the name (their build file still says 39)
tag_at $N1 39
tag_at android-v2.2.7-nightly.20261205 39
case_ "a nightly not above an earlier nightly fails"  1 "not higher than 2612050 already released as android-v2.2.7-nightly.20261205" android-v2.2.7-nightly.20261203 openRS_v2.2.7-nightly.20261203.apk 2612030
case_ "the next nightly passes"                       0 "(> 2612050, android-v2.2.7-nightly.20261205)" android-v2.2.7-nightly.20261206 openRS_v2.2.7-nightly.20261206.apk 2612060

# A stable after nightlies: slot 9 of a calendar date, above every earlier tag
case_ "stable in slot 9 of the last nightly's date passes" 0 "versionCode 2612059"  android-v2.2.7 openRS_v2.2.7.apk 2612059
case_ "stable in slot 9 of a later date passes"       0 "versionCode 2612159"        android-v2.2.7 openRS_v2.2.7.apk 2612159
case_ "stable outside slot 9 fails"                   1 "slot 9"                     android-v2.2.7 openRS_v2.2.7.apk 2612150
case_ "stable with a literal like 40 fails"           1 "slot 9"                     android-v2.2.7 openRS_v2.2.7.apk 40
case_ "stable in slot 9 of no calendar date fails"    1 "not a calendar date"        android-v2.2.7 openRS_v2.2.7.apk 2613329
case_ "stable below the last nightly fails"           1 "not higher than 2612050"    android-v2.2.7 openRS_v2.2.7.apk 2612049

# After the stable: the next date's nightly is above it, the same date's is not
tag_at android-v2.2.7 2612159
case_ "a nightly on the stable's date fails"          1 "not higher than 2612159 already released as android-v2.2.7" android-v2.2.7-nightly.20261215 openRS_v2.2.7-nightly.20261215.apk 2612150
case_ "the next date's nightly passes"                0 "(> 2612159, android-v2.2.7)" android-v2.2.7-nightly.20261216 openRS_v2.2.7-nightly.20261216.apk 2612160

# An earlier tag that looks like a nightly but is not one by name cannot be read
tag_at android-v2.2.7-nightly.2026121 39
case_ "an unreadable earlier nightly tag fails"       1 "cannot read the versionCode of earlier tag android-v2.2.7-nightly.2026121" android-v2.2.7-nightly.20261220 openRS_v2.2.7-nightly.20261220.apk 2612200

[ "$failures" -eq 0 ] && echo "verify-release checks: all passed" || { echo "verify-release checks: $failures failed"; exit 1; }
