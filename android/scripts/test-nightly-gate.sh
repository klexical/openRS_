#!/usr/bin/env bash
# test-nightly-gate.sh — checks nightly-gate.sh, the nightly workflow's gate (#363): build only when files under android/
# differ between the commit and the newest PUBLISHED nightly's tag (rc.8's for the first nightly; a leftover tag or a draft
# does not count), and today's UTC date has no nightly release and no tag; refuse bad input and a failed release lookup.
# A throwaway repository holds the commits and tags; a fake gh prints a release list from a fixture. Dates and names are
# invented test values. Run from anywhere: bash android/scripts/test-nightly-gate.sh
set -uo pipefail
SCRIPT="$(cd "$(dirname "$0")" && pwd)/nightly-gate.sh"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/nightly-gate-test.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
failures=0; ran=0
D=20261110                                   # "today" in every case
NAME=2.2.7-nightly.$D; TAG=android-v$NAME

# ── Fake gh: `gh release list … --json tagName,isDraft` prints $WORK/releases.json, or fails when FAKE_GH_FAIL=1 ──
mkdir -p "$WORK/bin"
cat > "$WORK/bin/gh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" > "$WORK/gh-args"
[ "${FAKE_GH_FAIL:-0}" = 1 ] && { echo "HTTP 502" >&2; exit 1; }
[ "$1 $2" = "release list" ] || { echo "fake gh: unexpected $*" >&2; exit 2; }
cat "$WORK/releases.json"
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
g checkout -q "$C3" 2>/dev/null

# run <sha> <today> [env...]: runs the gate in the repository; OUT, CODE, and GITHUB_OUTPUT's lines in $WORK/out
run() {
  local sha="$1" today="$2"; shift 2
  : > "$WORK/out"; rm -f "$WORK/gh-args"
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
  is run true; is sha "$2"; is date "$D"; is name "$NAME"; is tag "$TAG"; is base_tag "$3"
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
releases '[{"tagName":"android-v2.2.7-nightly.20261105","isDraft":true},{"tagName":"android-v2.2.7-nightly.20261101","isDraft":false}]'
expect_run  "a draft nightly is not a base"                                "$C1" android-v2.2.7-nightly.20261101
releases '[{"tagName":"android-v2.2.7-rc.8","isDraft":false}]'
expect_run  "a leftover nightly tag without a published release is not a base" "$C1" android-v2.2.7-rc.8

# Today taken: a release in any state the token can see, or a tag
releases "[{\"tagName\":\"$TAG\",\"isDraft\":false}]"
expect_skip "today's published nightly is a quiet skip"                    "$C3" "already has a nightly"
releases "[{\"tagName\":\"$TAG\",\"isDraft\":true},{\"tagName\":\"android-v2.2.7-rc.8\",\"isDraft\":false}]"
expect_skip "today's draft, when the token can see it, is a quiet skip"    "$C3" "already has a nightly"
releases "[{\"tagName\":\"android-v2.2.8-nightly.$D\",\"isDraft\":false}]"
expect_skip "today's nightly of another base is a quiet skip"              "$C3" "already has a nightly"
releases '[{"tagName":"android-v2.2.7-rc.8","isDraft":false}]'
g tag "android-v2.2.6-nightly.$D" "$C0"
expect_skip "a tag for today without a release is a quiet skip"           "$C3" "already has a nightly"
g tag -d "android-v2.2.6-nightly.$D" >/dev/null
WHY=""; run "$C1" "$D"; [[ "$OUT" == *"draft"* ]] || why "the log does not say drafts are not visible"; check "the log says drafts are not visible to it"

# Refused: bad input, a failed lookup, an unreadable base, no base tag
expect_fail "a commit that is not a full SHA"     "40"            staging "$D"
expect_fail "a date that is not eight digits"     "YYYYMMDD"      "$C1" 2026111
expect_fail "a failed release lookup"             "release list"  "$C1" "$D" FAKE_GH_FAIL=1
releases '[]'
g tag -d android-v2.2.7-rc.8 >/dev/null
expect_fail "no published nightly and no rc.8 tag" "android-v2.2.7-rc.8" "$C1" "$D"
g tag android-v2.2.7-rc.8 "$C0"
printf 'val releaseBase = "2.2"\n' > "$R/android/app/build.gradle.kts"; g add -A; g commit -q -m bad-base; CB=$(g rev-parse HEAD)
expect_fail "a release base that is not X.Y.Z"    "releaseBase"   "$CB" "$D"

[ "$ran" -gt 0 ] || { echo "nightly gate checks: no case ran"; exit 1; }
[ "$failures" -eq 0 ] && echo "nightly gate checks: all $ran passed" || { echo "nightly gate checks: $failures of $ran failed"; exit 1; }
