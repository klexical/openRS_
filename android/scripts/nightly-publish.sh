#!/usr/bin/env bash
# nightly-publish.sh <tag> <title> <sha> <base tag> <file> <sha256> — the nightly's publishing step (#363). Needs
# GH_TOKEN with contents: write and GITHUB_REPOSITORY. In order:
#   1. stop quietly (exit 0, nothing created) when <tag> already has a release in any state (a draft included: this token
#      is shown drafts, the gate's read-only one is not) or a tag ref;
#   2. notes: GitHub's generated list of merged pull requests since <base tag> (the newest published nightly, rc.8 for the
#      first), then the SHA-256 line, then the changelog link at <sha>; on the first nightly the rc.8 line;
#   3. a DRAFT pre-release for <tag> at <sha>, Latest off; the tag ref is read and logged (when GitHub creates a draft's tag
#      is not documented: open fact 1);
#   4. the one upload; then the release must hold exactly one asset, named like <file>, whose downloaded bytes match <sha256>;
#   5. publish it as a pre-release, Latest off; then its tag must point at <sha> and it must not be the release marked Latest.
# Any failure exits non-zero. It deletes nothing: the workflow's clean-up job (nightly-cleanup.sh) deletes this run's
# draft when this step fails, is cancelled or times out. A failure after step 5 leaves a published release in place.
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

# 1. Today's tag already taken, in any state: a quiet stop
RELEASES=$(gh release list --repo "$R" --limit 1000 --json tagName,isDraft,isPrerelease) || fail "the release list could not be read"
jq -e 'type == "array"' >/dev/null 2>&1 <<<"$RELEASES" || fail "the release list is not a JSON list (empty, or an error), so nothing is created"
STATE=$(jq -r --arg t "$TAG" '[.[] | select(.tagName == $t) | if .isDraft then "draft" elif .isPrerelease then "pre-release" else "published release" end] | first // empty' <<<"$RELEASES")
if [ -n "$STATE" ]; then summary "Nothing published: $TAG already has a release ($STATE); nothing created or deleted."; exit 0; fi
EXISTING=$(tag_ref "$TAG")
if [ -n "$EXISTING" ]; then summary "Nothing published: $TAG already has a tag (at $EXISTING) with no release; nothing created or deleted."; exit 0; fi

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
gh release create "$TAG" --repo "$R" --draft --prerelease --latest=false --target "$SHA" --title "$TITLE" \
  --notes-file "$NOTES" >/dev/null || fail "the draft for $TAG could not be created"
AT_DRAFT=$(tag_ref "$TAG")
summary "Draft created for $TAG at $SHA; the tag ref after the draft: ${AT_DRAFT:-absent} (open fact 1)."

# 4. The one upload, and exactly that one asset with the right bytes
gh release upload "$TAG" "$FILE" --repo "$R" || fail "the upload of $ASSET to the draft failed"
VIEW=$(gh release view "$TAG" --repo "$R" --json isDraft,isPrerelease,assets) || fail "the draft $TAG could not be read"
jq -e --arg a "$ASSET" '.isDraft == true and .isPrerelease == true and (.assets | length) == 1 and .assets[0].name == $a' \
  >/dev/null <<<"$VIEW" || fail "the draft does not hold exactly one asset $ASSET (or is not a draft pre-release): $(jq -c '{isDraft, isPrerelease, assets: [.assets[].name]}' <<<"$VIEW")"
CHECK=$(mktemp -d)
gh release download "$TAG" --repo "$R" --pattern "$ASSET" --dir "$CHECK" || fail "the uploaded $ASSET could not be downloaded"
[ "$(sha256 "$CHECK/$ASSET")" = "$SHA256" ] || fail "the uploaded $ASSET does not match the digest $SHA256"

# 5. Publish: a pre-release, never Latest
gh release edit "$TAG" --repo "$R" --draft=false --prerelease --latest=false >/dev/null || fail "the draft $TAG could not be published"
VIEW=$(gh release view "$TAG" --repo "$R" --json isDraft,isPrerelease,assets) || fail "the published $TAG could not be read"
jq -e '.isDraft == false and .isPrerelease == true' >/dev/null <<<"$VIEW" || fail "$TAG is not a published pre-release after publishing"
AT=$(tag_ref "$TAG")
[ "$AT" = "$SHA" ] || fail "the published tag $TAG points at '${AT:-nothing}', not $SHA"
LATEST_ERR=$(mktemp); LATEST=$(gh api "repos/$R/releases/latest" --jq .tag_name 2>"$LATEST_ERR") \
  || grep -q 'HTTP 404' "$LATEST_ERR" || fail "the release marked Latest could not be read: $(cat "$LATEST_ERR")"
[ "$LATEST" != "$TAG" ] || fail "$TAG is marked Latest; a nightly never is"
summary "Published $TAG as a pre-release (Latest: ${LATEST:-none}), one asset $ASSET, SHA-256 $SHA256, tag at $SHA."
