#!/usr/bin/env bash
# nightly-gate.sh <sha> <today YYYYMMDD> — the nightly workflow's gate (#363; SPEC "When it runs" 3). Run in a checkout of
# the commit with full history and tags. Writes run=, reason=, sha=, date=, name=, tag= to $GITHUB_OUTPUT (the base it
# compared with is in the log; T6 adds it as an output when it needs it):
#   - name <releaseBase>-nightly.<today> (releaseBase read from the commit's app/build.gradle.kts), tag android-v<name>;
#   - run=false (a quiet success) when today's UTC date already has a nightly: a release the token can see, or a tag;
#   - run=false when no file under android/ differs between the commit and base_tag: the newest PUBLISHED nightly's tag
#     (a draft or a leftover tag without a release is not one), else android-v2.2.7-rc.8 for the first nightly;
#   - else run=true.
# With a read-only token GitHub lists no draft releases ("Only users with push access will receive listings for draft
# releases"), so in dry-run mode a draft for today is not seen; T6, which creates drafts, owns that check.
# Fails the run (no decision written) on bad input, a failed release lookup or output that is not a JSON list (an empty
# answer must not read as "no nightly yet"), an unreadable base or a missing base tag. The list is read up to 1000
# releases and sorted here by the date in each tag, so nothing depends on the order GitHub returns.
set -euo pipefail
fail() { echo "::error::$*"; exit 1; }
[ $# -eq 2 ] || fail "usage: nightly-gate.sh <sha> <today YYYYMMDD>"
SHA="$1"; TODAY="$2"
FIRST_BASE_TAG=android-v2.2.7-rc.8            # the first nightly is compared with rc.8 (SPEC "When it runs" 3)
NIGHTLY_TAG='^android-v[0-9]+\.[0-9]+\.[0-9]+-nightly\.[0-9]{8}$'

[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || fail "'$SHA' is not a full commit SHA (40 lower-case hex characters)"
[[ "$TODAY" =~ ^[0-9]{8}$ ]] || fail "today '$TODAY' is not YYYYMMDD"
BASE=$(git show "$SHA:android/app/build.gradle.kts" | sed -n 's/^val releaseBase = "\([^"]*\)".*/\1/p')
[[ "$BASE" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "releaseBase '$BASE' in $SHA:android/app/build.gradle.kts is not X.Y.Z"
NAME="$BASE-nightly.$TODAY"; TAG="android-v$NAME"

RELEASES=$(gh release list --repo "$GITHUB_REPOSITORY" --limit 1000 --json tagName,isDraft) || fail "the release list could not be read"
jq -e 'type == "array"' >/dev/null 2>&1 <<<"$RELEASES" || fail "the release list is not a JSON list (empty, or an error), so the gate cannot decide"
echo "Releases read: $(jq length <<<"$RELEASES") (a read-only token is shown no draft releases)"

decide() {   # decide <run> <reason> <base tag>
  printf 'run=%s\nreason=%s\nsha=%s\ndate=%s\nname=%s\ntag=%s\n' "$1" "$2" "$SHA" "$TODAY" "$NAME" "$TAG" >> "$GITHUB_OUTPUT"
  echo "Gate: run=$1${2:+ — $2} (base $3, commit $SHA, $NAME)"
}

# No `| head` under pipefail: a producer cut off by SIGPIPE would fail the script (test-release-workflows.rb checks it)
TODAY_RELEASE=$(jq -r --arg d "-nightly.$TODAY" '[.[] | select(.tagName | endswith($d)) | .tagName] | first // empty' <<<"$RELEASES")
TODAY_TAG=$(git tag -l "android-v*-nightly.$TODAY"); TODAY_TAG="${TODAY_TAG%%$'\n'*}"   # the first, if any
if [ -n "$TODAY_RELEASE$TODAY_TAG" ]; then
  decide false "today ($TODAY UTC) already has a nightly: ${TODAY_RELEASE:-tag $TODAY_TAG}" "-"; exit 0
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
