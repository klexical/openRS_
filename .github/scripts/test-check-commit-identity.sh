#!/usr/bin/env bash
# test-check-commit-identity.sh — check-commit-identity.sh against throwaway repositories built here (owner Q141).
#   bash .github/scripts/test-check-commit-identity.sh            (CHECK=<path> tests another copy: red first)
# Each case makes commits with set identities and messages in a temporary repository, then runs the check on base..head.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
CHECK=${CHECK:-$HERE/check-commit-identity.sh}
OWNER=263109723+klexical@users.noreply.github.com
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
pass=0; fail=0
repo() {   # a fresh repository with one base commit by the owner; prints nothing
  rm -rf "$T/r"; git init -q "$T/r"
  git -C "$T/r" -c user.name=o -c user.email=$OWNER -c commit.gpgsign=false commit -q --allow-empty -m base
}
commit() { # commit <author email> <committer email> <message>
  GIT_AUTHOR_NAME=a GIT_AUTHOR_EMAIL=$1 GIT_COMMITTER_NAME=c GIT_COMMITTER_EMAIL=$2 \
    git -C "$T/r" -c commit.gpgsign=false commit -q --allow-empty -m "$3"
}
expect() { # expect <0|1> <case name>: runs the check on the base commit..HEAD
  local want=$1 name=$2 base head out rc
  base=$(git -C "$T/r" rev-list --max-parents=0 HEAD); head=$(git -C "$T/r" rev-parse HEAD)
  out=$(cd "$T/r" && bash "$CHECK" "$base" "$head" 2>&1); rc=$?
  if [ "$rc" -eq "$want" ]; then pass=$((pass+1)); echo "PASS $name"
  else fail=$((fail+1)); echo "FAIL $name (exit $rc, wanted $want): $out"; fi
}

repo; commit $OWNER $OWNER "one: the owner's commit"; commit $OWNER $OWNER "two"
expect 0 "every commit by the owner's noreply address passes"
repo; commit someone@example.com $OWNER "author elsewhere"
expect 1 "an author email other than the owner's fails"
repo; commit $OWNER noreply@github.com "committer elsewhere"
expect 1 "a committer email other than the owner's fails"
repo; commit $OWNER $OWNER "fine"; commit 263109723+KLEXICAL@users.noreply.github.com $OWNER "case"
expect 1 "the address is compared exactly, case included"
repo; commit $OWNER $OWNER "subject

body

Claude-Session: https://claude.ai/code/session_x"
expect 1 "a Claude-Session: trailer fails"
repo; commit $OWNER $OWNER "subject

claude-session: x"
expect 1 "the trailer key in any case fails"
repo; commit $OWNER $OWNER "subject

Claude-Session: x

later paragraph"
expect 1 "a Claude-Session: line outside the last paragraph fails too"
repo; commit $OWNER $OWNER "subject mentions Claude-Session: in the middle of a line"
expect 0 "the words inside a line are not a trailer"
repo; commit $OWNER $OWNER "ok"; commit bad@example.com $OWNER "the middle one is bad"; commit $OWNER $OWNER "ok"
expect 1 "one bad commit among good ones fails"
repo
expect 1 "an empty range fails (nothing to check is not a pass)"
# usage: missing or malformed SHAs
out=$(bash "$CHECK" 2>&1); rc=$?
if [ "$rc" -eq 2 ]; then pass=$((pass+1)); echo "PASS no arguments is a usage error (exit 2)"; else fail=$((fail+1)); echo "FAIL no arguments: exit $rc"; fi
out=$(cd "$T/r" && bash "$CHECK" "main;id" HEAD 2>&1); rc=$?
if [ "$rc" -eq 2 ]; then pass=$((pass+1)); echo "PASS a base that is not a 40-hex SHA is a usage error"; else fail=$((fail+1)); echo "FAIL non-SHA base: exit $rc"; fi

repo; commit $OWNER $OWNER "ok"
out=$(cd "$T/r" && bash "$CHECK" "$(git -C "$T/r" rev-list --max-parents=0 HEAD)" HEAD 2>&1); rc=$?
if [ "$rc" -eq 2 ]; then pass=$((pass+1)); echo "PASS a ref name (HEAD) is not taken for a SHA"; else fail=$((fail+1)); echo "FAIL a ref name as head: exit $rc"; fi

echo "commit identity check: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
