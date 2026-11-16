#!/usr/bin/env bash
# nightly-cleanup.sh <tag> <sha> — the nightly's clean-up after a failed, cancelled or timed-out publish step (#363).
# Needs GH_TOKEN with contents: write, GITHUB_REPOSITORY, GITHUB_RUN_ID and GITHUB_RUN_ATTEMPT.
#   - Before anything is deleted, a tag ref for <tag> must point at <sha>, the commit this run recorded: a tag at another
#     commit is not this run's, and nothing is deleted.
#   - The tag's release is deleted only when it is a DRAFT that is this run attempt's: at <sha>, and its notes holding, on a
#     line of its own, the line nightly-publish.sh writes for this run and attempt. A draft from an earlier run or attempt
#     (a leftover, or a nightly taken back to draft) fails the step and is left. A published release (a pre-release or not)
#     is never deleted: the run fails and says so.
#   - Then the tag ref, if any, is deleted (it points at <sha>), and it must be gone; only GitHub's 404 counts as gone,
#     any other answer fails.
set -euo pipefail
fail() { echo "::error::$*"; exit 1; }
[ $# -eq 2 ] || fail "usage: nightly-cleanup.sh <tag> <sha>"
TAG="$1"; SHA="$2"
R="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}"
# Only a nightly's tag, or the throwaway trial's (test-nightly-cleanup-<date>, which no updater reads), is ever touched
[[ "$TAG" =~ ^(android-v[0-9]+\.[0-9]+\.[0-9]+-nightly\.[0-9]{8}|test-nightly-cleanup-[0-9]{8})$ ]] \
  || fail "'$TAG' is neither a nightly's tag nor the trial's: refused"
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || fail "'$SHA' is not a full commit SHA (40 lower-case hex characters)"
RUN_ID="${GITHUB_RUN_ID:-}"; RUN_ATTEMPT="${GITHUB_RUN_ATTEMPT:-}"   # set by Actions in every job; no token scope needed
[[ "$RUN_ID" =~ ^[0-9]+$ ]] || fail "GITHUB_RUN_ID is not a number ('$RUN_ID')"
[[ "$RUN_ATTEMPT" =~ ^[0-9]+$ ]] || fail "GITHUB_RUN_ATTEMPT is not a number ('$RUN_ATTEMPT')"
# The line that marks a draft as this run attempt's, on a line of its own in the draft's notes only (#363)
MARK="<!-- nightly draft of run $RUN_ID attempt $RUN_ATTEMPT at $SHA -->"
summary() { [ -z "${GITHUB_STEP_SUMMARY:-}" ] || echo "$*" >> "$GITHUB_STEP_SUMMARY"; echo "$*"; }

tag_ref() {   # the commit the tag ref points at, or nothing on 404; any other failure ends the run
  local out err code=0
  err=$(mktemp); out=$(gh api "repos/$R/git/ref/tags/$1" --jq .object.sha 2>"$err") || code=$?
  if [ "$code" -eq 0 ]; then rm -f "$err"; echo "$out"; return; fi
  if grep -q 'HTTP 404' "$err"; then rm -f "$err"; return; fi
  echo "::error::the tag ref $1 could not be read: $(cat "$err")" >&2; rm -f "$err"; exit 1
}

RELEASES=$(gh release list --repo "$R" --limit 1000 --json tagName,isDraft,isPrerelease) || fail "the release list could not be read; nothing deleted"
jq -e 'type == "array"' >/dev/null 2>&1 <<<"$RELEASES" || fail "the release list is not a JSON list; nothing deleted"
STATE=$(jq -r --arg t "$TAG" '[.[] | select(.tagName == $t) | if .isDraft then "draft" elif .isPrerelease then "pre-release" else "release" end] | first // empty' <<<"$RELEASES")

AT=$(tag_ref "$TAG")
[ -z "$AT" ] || [ "$AT" = "$SHA" ] || fail "the tag $TAG points at another commit ($AT, not $SHA): not this run's, so nothing is deleted"

case "$STATE" in
  draft)
    VIEW=$(gh release view "$TAG" --repo "$R" --json isDraft,targetCommitish,body) || fail "the draft $TAG could not be read; nothing deleted"
    jq -e --arg s "$SHA" '.isDraft == true and .targetCommitish == $s' >/dev/null <<<"$VIEW" \
      || fail "the draft $TAG is not at $SHA: not this run attempt's, so it is left and nothing is deleted"
    jq -r '.body' <<<"$VIEW" | tr -d '\r' | grep -qxF -- "$MARK" \
      || fail "the draft $TAG does not name run $RUN_ID attempt $RUN_ATTEMPT: not this run attempt's, so it is left and nothing is deleted"
    gh release delete "$TAG" --repo "$R" --yes || fail "the draft $TAG could not be deleted"
    summary "Deleted this run attempt's draft $TAG (run $RUN_ID, attempt $RUN_ATTEMPT)." ;;
  "")
    : ;;
  *)
    fail "$TAG is a published $STATE, not a draft: it is not deleted, nor its tag (a bad nightly is taken back by hand)" ;;
esac

AT=$(tag_ref "$TAG")
if [ -n "$AT" ]; then
  [ "$AT" = "$SHA" ] || fail "the tag $TAG points at another commit ($AT, not $SHA): not this run's, so it is left"
  gh api --method DELETE "repos/$R/git/refs/tags/$TAG" >/dev/null || fail "the tag $TAG still exists and could not be deleted"
  summary "Deleted the tag $TAG left at $SHA."
fi
for _ in 1 2 3; do
  AT=$(tag_ref "$TAG")
  [ -n "$AT" ] || { summary "Clean-up done: no release and no tag $TAG remain."; exit 0; }
  sleep "${CLEANUP_SLEEP:-5}"
done
fail "the tag $TAG still exists after the clean-up"
