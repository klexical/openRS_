#!/usr/bin/env bash
# test-nightly-gate.sh — checks nightly-gate.sh, the nightly workflow's gate (#363): build only when files under android/
# differ between the commit and the newest PUBLISHED nightly's tag (rc.8's for the first nightly; a leftover tag or a draft
# does not count), and today's UTC date has no nightly release and no tag; skip quietly only when today's nightly is a
# clean published one at the run's commit, and fail on anything else for today (Q114); refuse bad input and a failed
# lookup. A throwaway repository holds the commits and tags; a fake gh prints a release list, a release view, an asset and
# the release marked Latest from fixtures. Dates and names are invented test values. Run from anywhere: bash android/scripts/test-nightly-gate.sh
set -uo pipefail
SCRIPT="$(cd "$(dirname "$0")" && pwd)/nightly-gate.sh"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/nightly-gate-test.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
failures=0; ran=0
D=20261110                                   # "today" in every case
NAME=2.2.7-nightly.$D; TAG=android-v$NAME

# ── Fake gh. `release list` prints $WORK/releases.json (FAKE_GH_FAIL=1: fails); for a day that has a nightly (#363,
#    Q114) `release view` prints $WORK/view.json, `release download` copies $WORK/asset, `api …/releases/latest` prints
#    $WORK/latest (absent: HTTP 404). FAKE_VIEW_FAIL / FAKE_DOWNLOAD_FAIL / FAKE_LATEST_FAIL=1 make those fail. Every call
#    is appended to $WORK/gh-calls ──
mkdir -p "$WORK/bin"
cat > "$WORK/bin/gh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" > "$WORK/gh-args"; printf '%s\n' "$*" >> "$WORK/gh-calls"
[ "${FAKE_GH_FAIL:-0}" = 1 ] && { echo "HTTP 502" >&2; exit 1; }
case "$1 $2" in
  "release list") cat "$WORK/releases.json" ;;
  "release view") [ "${FAKE_VIEW_FAIL:-0}" = 1 ] && { echo "HTTP 502" >&2; exit 1; }; cat "$WORK/view.json" ;;
  "release download")
    [ "${FAKE_DOWNLOAD_FAIL:-0}" = 1 ] && { echo "HTTP 502" >&2; exit 1; }
    pat=""; dir=""; while [ $# -gt 0 ]; do case "$1" in --pattern) pat="$2"; shift ;; --dir) dir="$2"; shift ;; esac; shift; done
    cp "$WORK/asset" "$dir/$pat" ;;
  "api repos/owner/repo/releases/latest")
    [ "${FAKE_LATEST_FAIL:-0}" = 1 ] && { echo "HTTP 502" >&2; exit 1; }
    [ -f "$WORK/latest" ] || { echo "gh: Not Found (HTTP 404)" >&2; exit 1; }
    cat "$WORK/latest" ;;
  *) echo "fake gh: unexpected $*" >&2; exit 2 ;;
esac
STUB
chmod +x "$WORK/bin/gh"
releases() { printf '%s\n' "$1" > "$WORK/releases.json"; }     # releases '<JSON array>'

# ── Throwaway repository: rc.8, then a change under android/, then a change outside it ──────────────────────────────
R="$WORK/repo"; mkdir -p "$R/android/app" "$R/web"
g() { git -C "$R" -c user.name=test -c user.email=test@example.invalid -c commit.gpgsign=false -c tag.gpgsign=false "$@"; }
g init -q
printf 'val releaseBase = "2.2.7"\nval releaseStage = "rc"\n' > "$R/android/app/build.gradle.kts"; echo a > "$R/web/x"
g add -A; g commit -q -m rc8; g tag android-v2.2.7-rc.8; C0=$(g rev-parse HEAD)
echo 1 > "$R/android/app/A.kt"; g add -A; g commit -q -m android-change; C1=$(g rev-parse HEAD)
echo b > "$R/web/x"; g add -A; g commit -q -m web-change; C2=$(g rev-parse HEAD)
echo 2 > "$R/android/app/A.kt"; g add -A; g commit -q -m android-change-2; C3=$(g rev-parse HEAD)
g checkout -q -b web-only "$C0"; echo c > "$R/web/x"; g add -A; g commit -q -m web-only; CW=$(g rev-parse HEAD)
g checkout -q -b scripts-only "$C0"; mkdir -p "$R/android/scripts"; echo s > "$R/android/scripts/x"; g add -A; g commit -q -m scripts-only; CS=$(g rev-parse HEAD)
g checkout -q "$C3" 2>/dev/null

# run <sha> <today> [env...]: runs the gate in the repository; OUT, CODE, and GITHUB_OUTPUT's lines in $WORK/out
run() {
  local sha="$1" today="$2"; shift 2
  : > "$WORK/out"; rm -f "$WORK/gh-args" "$WORK/gh-calls"
  OUT=$(cd "$R" && env PATH="$WORK/bin:$PATH" WORK="$WORK" GITHUB_OUTPUT="$WORK/out" GITHUB_REPOSITORY=owner/repo "$@" \
        bash "$SCRIPT" "$sha" "$today" 2>&1); CODE=$?
}
out() { sed -n "s/^$1=//p" "$WORK/out"; }
check() {
  ran=$((ran + 1))
  if [ -z "$WHY" ]; then echo "PASS $1"; else echo "FAIL $1 — $WHY"; echo "$OUT" | sed 's/^/    /'; failures=$((failures + 1)); fi
}
why() { WHY="${WHY:+$WHY; }$*"; }
is() { [ "$(out "$1")" = "$2" ] || why "$1 is '$(out "$1")', expected '$2'"; }

# expect_run <case> <sha> <base tag>: the gate says build, names the nightly and the tag it compared with
expect_run() {
  WHY=""; run "$2" "$D"
  [ "$CODE" -eq 0 ] || why "exit $CODE"
  is run true; is sha "$2"; is date "$D"; is name "$NAME"; is tag "$TAG"
  [[ "$OUT" == *"(base $3,"* ]] || why "the log does not name the base $3"
  is base_tag "$3"                         # the publish step's notes start from it (#363)
  check "$1"
}
# expect_skip <case> <sha> <reason text>: the gate says skip (a quiet success) and why
expect_skip() {
  WHY=""; run "$2" "$D"
  [ "$CODE" -eq 0 ] || why "exit $CODE"
  is run false; [[ "$(out reason)" == *"$3"* ]] || why "reason '$(out reason)' lacks '$3'"
  check "$1"
}
# expect_fail <case> <message> <sha> <today> [env...]: the gate fails the run and writes no decision
expect_fail() {
  local c="$1" needle="$2" sha="$3" today="$4"; shift 4; WHY=""
  run "$sha" "$today" "$@"
  [ "$CODE" -ne 0 ] || why "exit 0"
  [[ "$OUT" == *"::error::"* && "$OUT" == *"$needle"* ]] || why "output lacks '::error::' and '$needle'"
  [ -z "$(out run)" ] || why "a decision was written (run=$(out run))"
  check "$c"
}

# The first nightly: compared with rc.8
releases '[{"tagName":"android-v2.2.7-rc.8","isDraft":false}]'
expect_run  "the first nightly is compared with rc.8"                      "$C1" android-v2.2.7-rc.8
expect_skip "no change under android/ since rc.8 is a quiet skip"          "$C0" "no change under android/"
expect_skip "a change outside android/ only is a quiet skip"               "$CW" "no change under android/"
expect_run  "a change only under android/scripts builds (all of android/ counts)" "$CS" android-v2.2.7-rc.8
[[ "$(cat "$WORK/gh-args" 2>/dev/null)" == *"--repo owner/repo"* ]] && WHY="" || WHY="gh was not asked about this repository"
check "the release list is this repository's"

# The newest PUBLISHED nightly is the base; a draft or a leftover tag is not
g tag android-v2.2.7-nightly.20261105 "$C1"
releases '[{"tagName":"android-v2.2.7-nightly.20261105","isDraft":false},{"tagName":"android-v2.2.7-rc.8","isDraft":false}]'
expect_skip "no change since the newest published nightly is a quiet skip" "$C1" "no change under android/"
expect_skip "a web-only commit after that nightly is a quiet skip"         "$C2" "no change under android/"
expect_run  "a change since the newest published nightly builds"           "$C3" android-v2.2.7-nightly.20261105
g tag android-v2.2.7-nightly.20261101 "$C0"
releases '[{"tagName":"android-v2.2.7-nightly.20261101","isDraft":false},{"tagName":"android-v2.2.7-nightly.20261105","isDraft":false}]'
expect_run  "the newest by date is the base, whatever the list order"      "$C3" android-v2.2.7-nightly.20261105
g tag android-v2.2.9-nightly.20261102 "$C0"; g tag android-v2.2.10-nightly.20261106 "$C3"
releases '[{"tagName":"android-v2.2.9-nightly.20261102","isDraft":false},{"tagName":"android-v2.2.10-nightly.20261106","isDraft":false}]'
expect_skip "a later two-digit base is the newest by date (2.2.10 after 2.2.9)" "$C3" "no change under android/ since android-v2.2.10-nightly.20261106"
g tag -d android-v2.2.9-nightly.20261102 android-v2.2.10-nightly.20261106 >/dev/null
releases '[{"tagName":"android-v2.2.7-nightly.20261105","isDraft":true},{"tagName":"android-v2.2.7-nightly.20261101","isDraft":false}]'
expect_run  "a draft nightly is not a base"                                "$C1" android-v2.2.7-nightly.20261101
releases '[{"tagName":"android-v2.2.7-rc.8","isDraft":false}]'
expect_run  "a leftover nightly tag without a published release is not a base" "$C1" android-v2.2.7-rc.8

# Today taken (Q114): a quiet skip only for a clean published nightly at the run's commit; anything else for today fails
# the run, so "Re-run all jobs" after a publish that failed past its last step stays red (#363)
APK="openRS_v$NAME.apk"
printf 'apk bytes\n' > "$WORK/asset"
DIGEST=$( (command -v sha256sum >/dev/null && sha256sum "$WORK/asset" || shasum -a 256 "$WORK/asset") | cut -d' ' -f1)
NOTES="* A merged pull request\n\nSHA-256 of \`$APK\`: \`$DIGEST\`\n\nFull changelog: https://github.com/owner/repo/blob/$C3/android/CHANGELOG.md\n"
view() {   # view <isDraft> <isPrerelease> <asset names as a JSON list> [notes]: what `gh release view` prints
  jq -n --argjson d "$1" --argjson p "$2" --argjson a "$3" --arg b "$(printf "${4:-$NOTES}")" \
    '{isDraft: $d, isPrerelease: $p, assets: [$a[] | {name: .}], body: $b}' > "$WORK/view.json"
}
clean_day() {   # today's release for $TAG, published, one asset, digest in its notes, tag at C3, rc.8 marked Latest
  releases "[{\"tagName\":\"$TAG\",\"isDraft\":false},{\"tagName\":\"android-v2.2.7-rc.8\",\"isDraft\":false}]"
  view false true "[\"$APK\"]"; printf 'apk bytes\n' > "$WORK/asset"; echo android-v2.2.6 > "$WORK/latest"
  g tag -f "$TAG" "$C3" >/dev/null 2>&1
}
called() { grep -q -F -- "$1" "$WORK/gh-calls" 2>/dev/null || why "gh was not asked '$1'"; }

clean_day
expect_skip "today's clean published nightly at the run's commit is a quiet skip" "$C3" "clean published nightly"
called "release view $TAG --repo owner/repo"; called "release download $TAG --repo owner/repo --pattern $APK"
called "api repos/owner/repo/releases/latest"; check "a quiet skip comes only after the release, its asset and Latest are read"
rm -f "$WORK/latest"
expect_skip "a clean nightly when no release is marked Latest is a quiet skip" "$C3" "clean published nightly"

clean_day; expect_fail "today's nightly with its tag at another commit (staging moved) fails" "points at" "$C1" "$D"
clean_day; view true true "[\"$APK\"]"
releases "[{\"tagName\":\"$TAG\",\"isDraft\":true}]"
expect_fail "today's draft, when the token can see it, fails"            "draft"            "$C3" "$D"
clean_day; view true true "[\"$APK\"]"
expect_fail "a release the list shows published but the view shows a draft fails" "published pre-release" "$C3" "$D"
clean_day; view false false "[\"$APK\"]"
expect_fail "today's release that is not a pre-release fails"             "published pre-release" "$C3" "$D"
clean_day; view false true "[\"$APK\",\"extra.txt\"]"
expect_fail "today's release with a second asset fails"                   "exactly one asset" "$C3" "$D"
clean_day; view false true "[\"openRS_v2.2.7-nightly.20261109.apk\"]"
expect_fail "today's release whose one asset has another name fails"      "exactly one asset" "$C3" "$D"
clean_day; view false true "[]"
expect_fail "today's release with no asset fails"                         "exactly one asset" "$C3" "$D"
clean_day; view false true "[\"$APK\"]" "* A merged pull request\n"
expect_fail "today's release whose notes carry no SHA-256 line fails"     "SHA-256 line"     "$C3" "$D"
clean_day; view false true "[\"$APK\"]" "$NOTES\nSHA-256 of \`$APK\`: \`$DIGEST\`\n"
expect_fail "today's release whose notes carry two SHA-256 lines fails"   "SHA-256 line"     "$C3" "$D"
clean_day; printf 'other bytes\n' > "$WORK/asset"
expect_fail "today's asset whose bytes do not match its notes fails"      "does not match"   "$C3" "$D"
clean_day; echo "$TAG" > "$WORK/latest"
expect_fail "today's nightly marked Latest fails"                         "Latest"           "$C3" "$D"
clean_day; g tag -d "$TAG" >/dev/null
expect_fail "today's release whose tag is not in the checkout fails"      "points at"        "$C3" "$D"
clean_day; expect_fail "a failed release view fails"                      "could not be read" "$C3" "$D" FAKE_VIEW_FAIL=1
clean_day; expect_fail "a failed asset download fails"                    "downloaded"       "$C3" "$D" FAKE_DOWNLOAD_FAIL=1
clean_day; expect_fail "a failed Latest lookup (not a 404) fails"         "Latest"           "$C3" "$D" FAKE_LATEST_FAIL=1
g tag -d "$TAG" >/dev/null
releases "[{\"tagName\":\"android-v2.2.8-nightly.$D\",\"isDraft\":false}]"
expect_fail "today's nightly of another base fails"                       "android-v2.2.8-nightly.$D" "$C3" "$D"
clean_day; releases "[{\"tagName\":\"$TAG\",\"isDraft\":false},{\"tagName\":\"android-v2.2.8-nightly.$D\",\"isDraft\":false}]"
expect_fail "a second release for today beside the clean one fails"       "android-v2.2.8-nightly.$D" "$C3" "$D"
g tag -d "$TAG" >/dev/null
releases '[{"tagName":"android-v2.2.7-rc.8","isDraft":false}]'
g tag "android-v2.2.6-nightly.$D" "$C0"
expect_fail "a tag for today without a release fails"                    "no release"       "$C3" "$D"
g tag -d "android-v2.2.6-nightly.$D" >/dev/null
g tag "$TAG" "$C3"
expect_fail "this run's tag for today without a release fails"           "no release"       "$C3" "$D"
g tag -d "$TAG" >/dev/null
WHY=""; run "$C1" "$D"; [[ "$OUT" == *"draft"* ]] || why "the log does not say drafts are not visible"; check "the log says drafts are not visible to it"

# Refused: bad input, a failed lookup, an unreadable base, no base tag
expect_fail "a commit that is not a full SHA"     "40"            staging "$D"
expect_fail "a date that is not eight digits"     "YYYYMMDD"      "$C1" 2026111
expect_fail "a failed release lookup"             "release list"  "$C1" "$D" FAKE_GH_FAIL=1
releases ''
expect_fail "an empty release list output"        "release list"  "$C1" "$D"
releases 'HTTP 502 Bad Gateway'
expect_fail "release list output that is not JSON" "release list" "$C1" "$D"
releases '{"message":"API rate limit exceeded"}'
expect_fail "an error object instead of a list"   "release list"  "$C1" "$D"
releases '[{"tagName":"android-v2.2.7-rc.8","isDraft":false}]'
WHY=""; run "$C1" "$D"; [[ "$(cat "$WORK/gh-args")" == *"--limit 1000"* ]] || why "gh args '$(cat "$WORK/gh-args")'"
check "the release list is read up to 1000 releases"
releases '[]'
g tag -d android-v2.2.7-rc.8 >/dev/null
expect_fail "no published nightly and no rc.8 tag" "android-v2.2.7-rc.8" "$C1" "$D"
g tag android-v2.2.7-rc.8 "$C0"
printf 'val releaseBase = "2.2"\n' > "$R/android/app/build.gradle.kts"; g add -A; g commit -q -m bad-base; CB=$(g rev-parse HEAD)
expect_fail "a release base that is not X.Y.Z"    "releaseBase"   "$CB" "$D"

[ "$ran" -gt 0 ] || { echo "nightly gate checks: no case ran"; exit 1; }
[ "$failures" -eq 0 ] && echo "nightly gate checks: all $ran passed" || { echo "nightly gate checks: $failures of $ran failed"; exit 1; }
