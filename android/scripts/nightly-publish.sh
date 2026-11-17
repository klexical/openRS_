#!/usr/bin/env bash
# nightly-publish.sh <tag> <title> <sha> <base tag> <file> <sha256> — the nightly's publishing step (#363). Needs
# GH_TOKEN with contents: write and GITHUB_REPOSITORY. In order:
#   1. when <tag> already has a release (this token is shown drafts, the gate's read-only one is not): a published one is
#      checked as step 5 checks it, and a quiet stop (exit 0, nothing created) only if it checks out; a draft, a
#      release that does not check out, or a tag ref with no release fails the step, and nothing is deleted here
#      ("Re-run failed jobs" then stays red until someone looks; "Re-run all jobs" re-runs the gate, which skips quietly
#      only when today's nightly is a clean published one and fails on anything else for today, Q114);
#   2. notes: GitHub's generated list of merged pull requests since <base tag> (the newest published nightly, rc.8 for the
#      first), then the SHA-256 line, then the changelog link at <sha>; on the first nightly the rc.8 line;
#   3. a DRAFT pre-release for <tag> at <sha>, Latest off, whose notes end with a line naming this run and attempt
#      (GITHUB_RUN_ID, GITHUB_RUN_ATTEMPT): the clean-up deletes only a draft that holds it; the tag ref is read and logged
#      (when GitHub creates a draft's tag is not documented);
#   4. the one upload; then the release must hold exactly one asset, named like <file>, whose downloaded bytes match <sha256>;
#   5. publish it as a pre-release, Latest off, with the notes of step 2 (without the run line); then its tag must point at
#      <sha> and it must not be the release marked Latest.
# Any failure exits non-zero. It deletes nothing: the workflow's clean-up job (nightly-cleanup.sh) deletes this run
# attempt's draft when this step fails, is cancelled or times out. A failure after step 5 leaves a published release in place.
set -euo pipefail
fail() { echo "::error::$*"; exit 1; }
[ $# -eq 6 ] || fail "usage: nightly-publish.sh <tag> <title> <sha> <base tag> <file> <sha256>"
TAG="$1"; TITLE="$2"; SHA="$3"; BASE_TAG="$4"; FILE="$5"; SHA256="$6"
R="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}"
FIRST_BASE_TAG=android-v2.2.7-rc.8            # the first nightly's base (nightly-gate.sh): its notes carry the rc.8 line

# Only a nightly's tag, or the throwaway trial's (test-nightly-cleanup-<date>, which no updater reads), is ever touched
[[ "$TAG" =~ ^(android-v[0-9]+\.[0-9]+\.[0-9]+-nightly\.[0-9]{8}|test-nightly-cleanup-[0-9]{8})$ ]] \
  || fail "'$TAG' is neither a nightly's tag nor the trial's: refused"
[[ "$BASE_TAG" =~ ^android-v[0-9]+\.[0-9]+\.[0-9]+(-rc\.[0-9]+|-nightly\.[0-9]{8})?$ ]] || fail "'$BASE_TAG' is not an android-v release tag"
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || fail "'$SHA' is not a full commit SHA (40 lower-case hex characters)"
[[ "$SHA256" =~ ^[0-9a-f]{64}$ ]] || fail "'$SHA256' is not a SHA-256 (64 lower-case hex characters)"
[ -f "$FILE" ] || fail "$FILE does not exist"
RUN_ID="${GITHUB_RUN_ID:-}"; RUN_ATTEMPT="${GITHUB_RUN_ATTEMPT:-}"   # set by Actions in every job; no token scope needed
[[ "$RUN_ID" =~ ^[0-9]+$ ]] || fail "GITHUB_RUN_ID is not a number ('$RUN_ID')"
[[ "$RUN_ATTEMPT" =~ ^[0-9]+$ ]] || fail "GITHUB_RUN_ATTEMPT is not a number ('$RUN_ATTEMPT')"
# The line that marks a draft as this run attempt's, on a line of its own in the draft's notes only (#363)
MARK="<!-- nightly draft of run $RUN_ID attempt $RUN_ATTEMPT at $SHA -->"
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
[ "$(sha256 "$FILE")" = "$SHA256" ] || fail "$FILE does not match the digest $SHA256"
ASSET=$(basename "$FILE")
summary() { [ -z "${GITHUB_STEP_SUMMARY:-}" ] || echo "$*" >> "$GITHUB_STEP_SUMMARY"; echo "$*"; }

# tag_ref <tag>: prints the commit the tag ref points at, or nothing when GitHub answers 404; any other failure ends the run
tag_ref() {
  local out err code=0
  err=$(mktemp); out=$(gh api "repos/$R/git/ref/tags/$1" --jq .object.sha 2>"$err") || code=$?
  if [ "$code" -eq 0 ]; then rm -f "$err"; echo "$out"; return; fi
  if grep -q 'HTTP 404' "$err"; then rm -f "$err"; return; fi
  echo "::error::the tag ref $1 could not be read: $(cat "$err")" >&2; rm -f "$err"; exit 1
}

# check_published <what>: the release for <tag> must be a published pre-release holding exactly the one asset <file>,
# whose downloaded bytes match <sha256>, its tag at <sha>, and not the release marked Latest (steps 1 and 5)
check_published() {
  local view pubcheck
  view=$(gh release view "$TAG" --repo "$R" --json isDraft,isPrerelease,assets) || fail "the $1 $TAG could not be read"
  jq -e '.isDraft == false and .isPrerelease == true' >/dev/null <<<"$view" || fail "$TAG is not a published pre-release ($1)"
  jq -e --arg a "$ASSET" '[.assets[].name] == [$a]' >/dev/null <<<"$view" \
    || fail "the $1 $TAG does not hold exactly one asset $ASSET: $(jq -c '[.assets[].name]' <<<"$view")"
  pubcheck=$(mktemp -d)
  gh release download "$TAG" --repo "$R" --pattern "$ASSET" --dir "$pubcheck" || fail "the $1 $ASSET could not be downloaded"
  [ "$(sha256 "$pubcheck/$ASSET")" = "$SHA256" ] || fail "the $1 $ASSET does not match the digest $SHA256"
  AT=$(tag_ref "$TAG")
  [ "$AT" = "$SHA" ] || fail "the $1 tag $TAG points at '${AT:-nothing}', not $SHA"
  LATEST_ERR=$(mktemp); LATEST=$(gh api "repos/$R/releases/latest" --jq .tag_name 2>"$LATEST_ERR") \
    || grep -q 'HTTP 404' "$LATEST_ERR" || fail "the release marked Latest could not be read: $(cat "$LATEST_ERR")"
  [ "$LATEST" != "$TAG" ] || fail "$TAG is marked Latest; a nightly never is"
}

# 1. Today's tag already taken: a published release that checks out is a quiet stop; a draft, one that does not, or a tag
#    with no release fails (the gate skips a day with a tag, so it is a leftover or made by hand)
RELEASES=$(gh release list --repo "$R" --limit 1000 --json tagName,isDraft,isPrerelease) || fail "the release list could not be read"
jq -e 'type == "array"' >/dev/null 2>&1 <<<"$RELEASES" || fail "the release list is not a JSON list (empty, or an error), so nothing is created"
STATE=$(jq -r --arg t "$TAG" '[.[] | select(.tagName == $t) | if .isDraft then "draft" elif .isPrerelease then "pre-release" else "published release" end] | first // empty' <<<"$RELEASES")
if [ "$STATE" = draft ]; then
  fail "$TAG already has a draft: a leftover of an earlier attempt or a nightly taken back; nothing published or deleted (look at it by hand)"
fi
if [ -n "$STATE" ]; then
  check_published "already published"
  summary "Nothing published: $TAG is already published and checks out (pre-release, one asset with the digest, tag at $SHA, not Latest); nothing created or deleted."
  exit 0
fi
EXISTING=$(tag_ref "$TAG")
[ -z "$EXISTING" ] || fail "$TAG already has a tag (at $EXISTING) with no release: a leftover or made by hand; nothing published or deleted (the clean-up deletes it only at $SHA)"

# 2. Notes: the generated list, then the SHA-256 line, then the changelog link, in that order (gh's --notes text would come
#    before the generated list, so the notes are composed here)
NOTES=$(mktemp)
GENERATED=$(gh api --method POST "repos/$R/releases/generate-notes" -f tag_name="$TAG" -f target_commitish="$SHA" \
  -f previous_tag_name="$BASE_TAG" --jq .body) || fail "the release notes could not be generated"
{
  printf '%s\n\n' "$GENERATED"
  printf 'SHA-256 of `%s`: `%s`\n\n' "$ASSET" "$SHA256"
  printf 'Full changelog: https://github.com/%s/blob/%s/android/CHANGELOG.md\n' "$R" "$SHA"
  [ "$BASE_TAG" != "$FIRST_BASE_TAG" ] || printf '\nOn rc.2–rc.7? Update to rc.8 first to keep receiving builds.\n'
} > "$NOTES"

# 3. The draft
DRAFT_NOTES=$(mktemp)
{ cat "$NOTES"; printf '\n%s\n' "$MARK"; } > "$DRAFT_NOTES"
gh release create "$TAG" --repo "$R" --draft --prerelease --latest=false --target "$SHA" --title "$TITLE" \
  --notes-file "$DRAFT_NOTES" >/dev/null || fail "the draft for $TAG could not be created"
AT_DRAFT=$(tag_ref "$TAG")
summary "Draft created for $TAG at $SHA (run $RUN_ID, attempt $RUN_ATTEMPT); the tag ref after the draft: ${AT_DRAFT:-absent}."

# 4. The one upload, and exactly that one asset with the right bytes
gh release upload "$TAG" "$FILE" --repo "$R" || fail "the upload of $ASSET to the draft failed"
VIEW=$(gh release view "$TAG" --repo "$R" --json isDraft,isPrerelease,assets) || fail "the draft $TAG could not be read"
jq -e --arg a "$ASSET" '.isDraft == true and .isPrerelease == true and (.assets | length) == 1 and .assets[0].name == $a' \
  >/dev/null <<<"$VIEW" || fail "the draft does not hold exactly one asset $ASSET (or is not a draft pre-release): $(jq -c '{isDraft, isPrerelease, assets: [.assets[].name]}' <<<"$VIEW")"
CHECK=$(mktemp -d)
gh release download "$TAG" --repo "$R" --pattern "$ASSET" --dir "$CHECK" || fail "the uploaded $ASSET could not be downloaded"
[ "$(sha256 "$CHECK/$ASSET")" = "$SHA256" ] || fail "the uploaded $ASSET does not match the digest $SHA256"

# 5. Publish: a pre-release, never Latest
gh release edit "$TAG" --repo "$R" --draft=false --prerelease --latest=false --notes-file "$NOTES" >/dev/null \
  || fail "the draft $TAG could not be published"
check_published "published"
summary "Published $TAG as a pre-release (Latest: ${LATEST:-none}), one asset $ASSET, SHA-256 $SHA256, tag at $SHA."
