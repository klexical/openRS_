#!/usr/bin/env bash
# test-check-commit-identity.sh — check-commit-identity.sh against throwaway repositories built here (owner Q141; the
# integrator's four details of Q137 / Q141).
#   bash .github/scripts/test-check-commit-identity.sh            (CHECK=<path> tests another copy: red first)
# Each case builds commits and branches with set identities and messages, then runs the check as the workflow does:
# base, head, then staging's and main's tips as further exclusions where the case has them.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
CHECK=${CHECK:-$HERE/check-commit-identity.sh}
OWNER=263109723+klexical@users.noreply.github.com
GH=noreply@github.com
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
pass=0; fail=0
g() { git -C "$T/r" -c commit.gpgsign=false "$@"; }
repo() {   # a fresh repository on branch main with one base commit by the owner
  rm -rf "$T/r"; git init -q -b main "$T/r"
  GIT_AUTHOR_NAME=o GIT_AUTHOR_EMAIL=$OWNER GIT_COMMITTER_NAME=o GIT_COMMITTER_EMAIL=$OWNER g commit -q --allow-empty -m base
}
commit() { # commit <author email> <committer email> <message>
  GIT_AUTHOR_NAME=a GIT_AUTHOR_EMAIL=$1 GIT_COMMITTER_NAME=c GIT_COMMITTER_EMAIL=$2 g commit -q --allow-empty -m "$3"
}
merge() {  # merge <author email> <committer email> <branch>: a --no-ff merge of <branch> into the current branch
  GIT_AUTHOR_NAME=a GIT_AUTHOR_EMAIL=$1 GIT_COMMITTER_NAME=c GIT_COMMITTER_EMAIL=$2 g merge -q --no-ff -m "Merge $3" "$3"
}
sha() { g rev-parse "$1"; }
run() { # run <want> <case name> <args...>
  local want=$1 name=$2 out rc; shift 2
  out=$(cd "$T/r" && bash "$CHECK" "$@" 2>&1); rc=$?
  if [ "$rc" -eq "$want" ]; then pass=$((pass+1)); echo "PASS $name"
  else fail=$((fail+1)); echo "FAIL $name (exit $rc, wanted $want): $out"; fi
}
range() { run "$1" "$2" "$(g rev-list --max-parents=0 HEAD)" "$(sha HEAD)"; }   # base commit .. HEAD

# ── one branch: every commit in base..head ───────────────────────────────────────────────────────────────────────────
repo; commit $OWNER $OWNER "one: the owner's commit"; commit $OWNER $OWNER "two"
range 0 "every commit by the owner's noreply address passes"
repo; commit someone@example.com $OWNER "author elsewhere"
range 1 "an author email other than the owner's fails"
repo; commit $OWNER someone@example.com "committer elsewhere"
range 1 "a committer email other than the owner's fails"
repo; commit $OWNER $GH "a web edit"
range 1 "GitHub's committer on a commit that is not a merge fails"
repo; commit $OWNER $OWNER "fine"; commit 263109723+KLEXICAL@users.noreply.github.com $OWNER "case"
range 1 "the address is compared exactly, case included"
repo; commit $OWNER $OWNER "subject

body

Claude-Session: https://claude.ai/code/session_x"
range 1 "a Claude-Session: trailer fails"
repo; commit $OWNER $OWNER "subject

claude-session: x"
range 1 "the trailer key in any case fails"
repo; commit $OWNER $OWNER "subject

Claude-Session: x

later paragraph"
range 1 "a Claude-Session: line outside the last paragraph fails too"
repo; commit $OWNER $OWNER "subject mentions Claude-Session: in the middle of a line"
range 0 "the words inside a line are not a trailer"
repo; commit $OWNER $OWNER "ok"; commit bad@example.com $OWNER "the middle one is bad"; commit $OWNER $OWNER "ok"
range 1 "one bad commit among good ones fails"
repo
range 0 "no new commit passes (nothing new to check)"

# ── branch shapes as on GitHub: staging and main carry old history and GitHub's merges ───────────────────────────────
history() { # main: base, an old commit by another address, a PR merged on GitHub; staging: main plus the same kinds
  repo
  commit old@example.com old@example.com "old history, before the noreply rule"
  g checkout -q -b pr1; commit $OWNER $OWNER "pr1 work"; g checkout -q main; merge $OWNER $GH pr1
  g checkout -q -b staging; commit old@example.com old@example.com "more old history"
  g checkout -q -b pr2; commit $OWNER $OWNER "pr2 work"; g checkout -q staging; merge $OWNER $GH pr2
}
pr() { # pr <want> <case name> <base branch> <head branch>: the workflow's call. TWO_SHAS=1 passes base and head only, the
  # earlier script's interface, so a red run against it shows behaviour, not a usage error
  if [ "${TWO_SHAS:-}" = 1 ]; then run "$1" "$2" "$(sha "$3")" "$(sha "$4")"
  else run "$1" "$2" "$(sha "$3")" "$(sha "$4")" "$(sha staging)" "$(sha main)"; fi
}
history; g checkout -q -b feature staging
for k in 1 2 3 4 5; do commit $OWNER $OWNER "feature $k"; done
pr 0 "a feature PR of five owner commits to staging passes (the #430 shape)" staging feature
history; g checkout -q -b fix main; commit $OWNER $OWNER "one fix"
pr 0 "a one-commit PR to main passes (the #431 shape)" main fix
history
pr 0 "a promotion from staging to main passes: every commit is already on staging" main staging
history; g checkout -q -b feature staging; commit $OWNER $OWNER "ok"; commit other@example.com $OWNER "new, another author"
pr 1 "a new commit by another author fails" staging feature
history; g checkout -q -b feature staging; commit $OWNER $OWNER "subject

Claude-Session: x"
pr 1 "a new commit with the trailer fails" staging feature
history; g checkout -q -b feature staging; commit $OWNER $OWNER "work"
g checkout -q -b side staging; commit $OWNER $OWNER "side"; g checkout -q feature; merge $OWNER $GH side
pr 0 "a new merge with GitHub's committer and the owner's author passes" staging feature
history; g checkout -q -b feature staging; commit $OWNER $OWNER "work"
g checkout -q -b side staging; commit $OWNER $OWNER "side"; g checkout -q feature; merge other@example.com $GH side
pr 1 "a new merge with GitHub's committer and another author fails" staging feature
history; g checkout -q -b feature staging; commit $OWNER $OWNER "work"
g checkout -q -b side staging; commit $OWNER $OWNER "side"; g checkout -q feature; merge $OWNER other@example.com side
pr 1 "a new merge with another committer fails" staging feature

# ── usage ────────────────────────────────────────────────────────────────────────────────────────────────────────────
out=$(bash "$CHECK" 2>&1); rc=$?
if [ "$rc" -eq 2 ]; then pass=$((pass+1)); echo "PASS no arguments is a usage error (exit 2)"; else fail=$((fail+1)); echo "FAIL no arguments: exit $rc"; fi
repo; commit $OWNER $OWNER "ok"
run 2 "a base that is not a 40-hex SHA is a usage error" "main;id" "$(sha HEAD)"
run 2 "a ref name (HEAD) is not taken for a SHA" "$(g rev-list --max-parents=0 HEAD)" HEAD
run 2 "an exclusion that is not a 40-hex SHA is a usage error" "$(g rev-list --max-parents=0 HEAD)" "$(sha HEAD)" main

echo "commit identity check: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
