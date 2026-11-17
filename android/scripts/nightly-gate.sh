#!/usr/bin/env bash
# nightly-gate.sh <sha> <today YYYYMMDD> — the nightly workflow's gate (#363). Run in a checkout of
# the commit with full history and tags. Writes run=, reason=, sha=, date=, name=, tag=, base_tag= to $GITHUB_OUTPUT
# (base_tag: the tag it compared with, which the publish step's notes start from; empty when today already has a nightly):
#   - name <releaseBase>-nightly.<today> (releaseBase read from the commit's app/build.gradle.kts), tag android-v<name>;
#   - when today's UTC date already has a nightly (a release the token can see, or a tag): run=false (a quiet success)
#     only if it is a clean published nightly (check_today_clean, Q114), else the run fails;
#   - run=false when no file under android/ differs between the commit and base_tag: the newest PUBLISHED nightly's tag
#     (a draft or a leftover tag without a release is not one), else android-v2.2.7-rc.8 for the first nightly;
#   - else run=true.
# With a read-only token GitHub lists no draft releases ("Only users with push access will receive listings for draft
# releases"), so a draft for today is not seen here; the publish step, whose token is shown drafts, checks again before
# it creates one (nightly-publish.sh).
# Fails the run (no decision written) on bad input, a failed release lookup or output that is not a JSON list (an empty
# answer must not read as "no nightly yet"), a day whose nightly is not clean, an unreadable base or a missing base tag. The list is read up to 1000
# releases and sorted here by the date in each tag, so nothing depends on the order GitHub returns.
set -euo pipefail
fail() { echo "::error::$*"; exit 1; }
[ $# -eq 2 ] || fail "usage: nightly-gate.sh <sha> <today YYYYMMDD>"
SHA="$1"; TODAY="$2"
FIRST_BASE_TAG=android-v2.2.7-rc.8            # the first nightly is compared with rc.8 (#363)
NIGHTLY_TAG='^android-v[0-9]+\.[0-9]+\.[0-9]+-nightly\.[0-9]{8}$'

[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || fail "'$SHA' is not a full commit SHA (40 lower-case hex characters)"
[[ "$TODAY" =~ ^[0-9]{8}$ ]] || fail "today '$TODAY' is not YYYYMMDD"
BASE=$(git show "$SHA:android/app/build.gradle.kts" | sed -n 's/^val releaseBase = "\([^"]*\)".*/\1/p')
[[ "$BASE" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "releaseBase '$BASE' in $SHA:android/app/build.gradle.kts is not X.Y.Z"
NAME="$BASE-nightly.$TODAY"; TAG="android-v$NAME"

RELEASES=$(gh release list --repo "$GITHUB_REPOSITORY" --limit 1000 --json tagName,isDraft) || fail "the release list could not be read"
jq -e 'type == "array"' >/dev/null 2>&1 <<<"$RELEASES" || fail "the release list is not a JSON list (empty, or an error), so the gate cannot decide"
echo "Releases read: $(jq length <<<"$RELEASES") (a read-only token is shown no draft releases)"

# check_today_clean: today's nightly must be a clean published one, or the run fails (#363, Q114, Q115),
# so "Re-run all jobs" after a publish that failed past its last step stays red. Clean = what nightly-publish.sh checks
# of a published nightly (check_published), with the digest from the release's own notes (this gate has no build yet):
#   - today has exactly one nightly (one tag name across its releases and tags), judged under its own name even when
#     this run's base has moved since (Q119), and a release for it;
#   - it is not a draft and is a pre-release;
#   - it holds exactly one asset, openRS_v<name>.apk, whose downloaded bytes match the one SHA-256 line of its notes;
#   - its notes name one commit (the changelog link nightly-publish.sh writes) and its tag (this checkout's) points at it;
#     that commit need not be this run's: a run after a same-day staging merge skips quietly beside a clean nightly (Q115);
#   - it is not the release marked Latest.
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
check_today_clean() {
  # The release judged is today's one nightly under its own tag and name, even when this run's base has moved since
  # (a stable cut sets the next base on staging; Q119); a second, different nightly that day fails
  local tag name today_names asset view want dir named at latest err
  today_names=$(printf '%s\n%s\n' "$TODAY_RELEASES" "$TODAY_TAGS" | grep -v -x -e '' | sort -u || true)
  [ "$(grep -c . <<<"$today_names")" -eq 1 ] || fail "today ($TODAY UTC) has more than one nightly: $(echo $today_names)"
  tag="$today_names"
  [[ "$tag" =~ $NIGHTLY_TAG ]] || fail "today's $tag is not a nightly's tag (android-v<X.Y.Z>-nightly.<date>)"
  name="${tag#android-v}"
  [ -n "$TODAY_RELEASES" ] || fail "today's $tag has a tag but no release this token can see (a leftover, a draft or made by hand); look at it by hand"
  ! jq -e --arg t "$tag" 'any(.[]; .tagName == $t and .isDraft)' >/dev/null <<<"$RELEASES" \
    || fail "today's $tag is a draft: a leftover of an earlier attempt; look at it by hand"
  asset="openRS_v$name.apk"
  view=$(gh release view "$tag" --repo "$GITHUB_REPOSITORY" --json isDraft,isPrerelease,assets,body) \
    || fail "today's release $tag could not be read"
  jq -e '.isDraft == false and .isPrerelease == true' >/dev/null <<<"$view" || fail "today's $tag is not a published pre-release"
  jq -e --arg a "$asset" '[.assets[].name] == [$a]' >/dev/null <<<"$view" \
    || fail "today's $tag does not hold exactly one asset $asset: $(jq -c '[.assets[].name]' <<<"$view")"
  want=$(jq -r --arg a "$asset" '.body | split("\n") | map(rtrimstr("\r")) | map(select(startswith("SHA-256 of `" + $a + "`: `")))
    | if length == 1 then .[0] | capture(": `(?<h>[0-9a-f]{64})`$").h // empty else empty end' <<<"$view") \
    || fail "today's $tag notes could not be read"
  [ -n "$want" ] || fail "today's $tag notes do not carry exactly one SHA-256 line for $asset"
  dir=$(mktemp -d)
  gh release download "$tag" --repo "$GITHUB_REPOSITORY" --pattern "$asset" --dir "$dir" \
    || fail "today's $asset could not be downloaded"
  [ "$(sha256 "$dir/$asset")" = "$want" ] || fail "today's $asset does not match the SHA-256 in its notes ($want)"
  named=$(jq -r --arg p "Full changelog: https://github.com/$GITHUB_REPOSITORY/blob/" '.body | split("\n") | map(rtrimstr("\r"))
    | map(select(startswith($p)) | ltrimstr($p) | capture("^(?<c>[0-9a-f]{40})/android/CHANGELOG\\.md$").c // "?")
    | if length == 1 and .[0] != "?" then .[0] else empty end' <<<"$view") \
    || fail "today's $tag notes could not be read"
  [ -n "$named" ] || fail "today's $tag notes do not name exactly one commit (the changelog link)"
  at=$(git rev-parse -q --verify "refs/tags/$tag^{commit}" || true)
  [ "$at" = "$named" ] || fail "today's tag $tag points at '${at:-nothing}', not $named, the commit its notes name"
  err=$(mktemp)
  latest=$(gh api "repos/$GITHUB_REPOSITORY/releases/latest" --jq .tag_name 2>"$err") \
    || grep -q 'HTTP 404' "$err" || fail "the release marked Latest could not be read: $(cat "$err")"
  [ "$latest" != "$tag" ] || fail "today's $tag is marked Latest; a nightly never is"
  CLEAN_TAG=$tag; CLEAN_COMMIT=$named
  echo "Today's $tag checks out: a published pre-release, one asset with the digest of its notes, tag at $named, not Latest"
}

decide() {   # decide <run> <reason> <base tag>
  local base="$3"; [ "$base" != "-" ] || base=""
  printf 'run=%s\nreason=%s\nsha=%s\ndate=%s\nname=%s\ntag=%s\nbase_tag=%s\n' "$1" "$2" "$SHA" "$TODAY" "$NAME" "$TAG" "$base" >> "$GITHUB_OUTPUT"
  echo "Gate: run=$1${2:+ — $2} (base $3, commit $SHA, $NAME)"
}

# No `| head` under pipefail: a producer cut off by SIGPIPE would fail the script (test-release-workflows.rb checks it)
TODAY_RELEASES=$(jq -r --arg d "-nightly.$TODAY" '.[] | select(.tagName | endswith($d)) | .tagName' <<<"$RELEASES")
TODAY_TAGS=$(git tag -l "android-v*-nightly.$TODAY")
if [ -n "$TODAY_RELEASES$TODAY_TAGS" ]; then
  check_today_clean
  decide false "today ($TODAY UTC) already has a clean published nightly: $CLEAN_TAG at $CLEAN_COMMIT" "-"; exit 0
fi

PREV=$(jq -r --arg re "$NIGHTLY_TAG" '.[] | select(.isDraft | not) | .tagName | select(test($re))' <<<"$RELEASES" \
  | sort -t. -k4,4n | tail -1)          # the date is the last dot-separated field; X.Y.Z-nightly.YYYYMMDD
BASE_TAG="${PREV:-$FIRST_BASE_TAG}"
git rev-parse -q --verify "refs/tags/$BASE_TAG" >/dev/null || fail "base tag $BASE_TAG is not in this checkout (fetch-depth 0 fetches tags)"

if git diff --quiet "$BASE_TAG" "$SHA" -- android/; then
  decide false "no change under android/ since $BASE_TAG" "$BASE_TAG"
else
  decide true "" "$BASE_TAG"
fi
