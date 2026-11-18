#!/usr/bin/env bash
# check-commit-identity.sh <base sha> <head sha> [<also-exclude sha>...] — the pull request's NEW commits (reachable from
# head, not from base nor from any other sha given; the workflow passes staging's and main's tips) each carry the
# owner's noreply address as author, and as committer too, except that a merge commit may carry GitHub's own committer
# address (a merge made on GitHub); none carries a `Claude-Session:` line, the key in any case, in any paragraph (owner
# Q141; the integrator's four details of Q137 / Q141). Commits already on staging or main are not checked again, so a
# promotion from staging to main has nothing new and passes. Prints one line per commit that fails (short SHA and what
# is wrong, never the address found) and a total. Exit 0: every new commit passes; 1: a commit fails; 2: usage.
set -euo pipefail
OWNER=263109723+klexical@users.noreply.github.com
GITHUB_MERGER=noreply@github.com
[ $# -ge 2 ] || { echo "usage: check-commit-identity.sh <base sha> <head sha> [<also-exclude sha>...]" >&2; exit 2; }
for s in "$@"; do
  [[ $s =~ ^[0-9a-f]{40}$ ]] || { echo "check-commit-identity: every argument a 40-hex SHA" >&2; exit 2; }
  git cat-file -e "$s^{commit}" 2>/dev/null || { echo "check-commit-identity: ${s:0:12} is not a commit here (fetch the history first)" >&2; exit 2; }
done
head=$2; base=$1; shift 2
commits=$(git rev-list "$head" --not "$base" "$@")
[ -n "$commits" ] || { echo "commit identity: no new commit (every commit is already on a base branch): nothing to check"; exit 0; }
n=0; bad=0
for c in $commits; do
  n=$((n+1)); why=()
  [ "$(git log -1 --format=%ae "$c")" = "$OWNER" ] || why+=("author is not the owner's noreply address")
  committer=$(git log -1 --format=%ce "$c")
  parents=$(git log -1 --format=%P "$c" | wc -w)
  if [ "$committer" != "$OWNER" ] && ! { [ "$parents" -gt 1 ] && [ "$committer" = "$GITHUB_MERGER" ]; }; then
    why+=("committer is not the owner's noreply address")
  fi
  msg=$(git log -1 --format=%B "$c")        # a variable, not a pipe: with pipefail an early grep exit could hide a match
  if grep -q -i -E '^[[:space:]]*claude-session[[:space:]]*:' <<<"$msg"; then
    why+=("carries a Claude-Session: line")
  fi
  if [ ${#why[@]} -gt 0 ]; then
    bad=$((bad+1)); printf 'FAIL %s: %s\n' "${c:0:12}" "$(IFS=';'; echo "${why[*]}" | sed 's/;/; /g')"
  fi
done
echo "commit identity: $n new commit(s), $bad failing"
[ "$bad" -eq 0 ]
