#!/usr/bin/env bash
# check-commit-identity.sh <base sha> <head sha> — every commit in base..head carries the owner's noreply address as both
# author and committer, and none carries a `Claude-Session:` line (owner Q141; the trailer key in any case, in any
# paragraph). Run from the repository. Prints one line per commit that fails (short SHA and what is wrong, never the
# address found) and a total. Exit 0: every commit passes; 1: a commit fails or the range is empty; 2: usage.
set -euo pipefail
OWNER=263109723+klexical@users.noreply.github.com
[ $# -eq 2 ] || { echo "usage: check-commit-identity.sh <base sha> <head sha>" >&2; exit 2; }
for s in "$1" "$2"; do
  [[ $s =~ ^[0-9a-f]{40}$ ]] || { echo "check-commit-identity: a base and a head as 40-hex SHAs" >&2; exit 2; }
done
git cat-file -e "$1^{commit}" 2>/dev/null && git cat-file -e "$2^{commit}" 2>/dev/null \
  || { echo "check-commit-identity: base or head is not a commit here (fetch the history first)" >&2; exit 2; }
commits=$(git rev-list "$1..$2")
[ -n "$commits" ] || { echo "check-commit-identity: no commit in the range: nothing checked"; exit 1; }
n=0; bad=0
for c in $commits; do
  n=$((n+1)); why=()
  [ "$(git log -1 --format=%ae "$c")" = "$OWNER" ] || why+=("author is not the owner's noreply address")
  [ "$(git log -1 --format=%ce "$c")" = "$OWNER" ] || why+=("committer is not the owner's noreply address")
  msg=$(git log -1 --format=%B "$c")        # a variable, not a pipe: with pipefail an early grep exit could hide a match
  if grep -q -i -E '^[[:space:]]*claude-session[[:space:]]*:' <<<"$msg"; then
    why+=("carries a Claude-Session: line")
  fi
  if [ ${#why[@]} -gt 0 ]; then
    bad=$((bad+1)); printf 'FAIL %s: %s\n' "${c:0:12}" "$(IFS=';'; echo "${why[*]}" | sed 's/;/; /g')"
  fi
done
echo "commit identity: $n commit(s), $bad failing"
[ "$bad" -eq 0 ]
