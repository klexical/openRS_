#!/usr/bin/env bash
# test-privacy-scan.sh — privacy-scan.sh against throwaway repositories built here, with a seeded identifier list.
#   bash .github/scripts/test-privacy-scan.sh                (SCAN=<path> tests another copy: red first)
# gitleaks is a stub here, which records its arguments and writes the report a case gives it; with GITLEAKS_REAL=<path>
# of a real gitleaks binary (the workflow sets it), the last cases also run the real scanner on a seeded fake key.
# Every case also checks that no seeded identifier, in any case, and no seeded secret reaches the output.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
SCAN=${SCAN:-$HERE/privacy-scan.sh}
OWNER=263109723+klexical@users.noreply.github.com
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
pass=0; fail=0
# The seeded identifiers: synthetic, made for this test. The list has a comment, a blank line and padding to skip.
ID1=QZXV-FIXTURE-7731; ID2=Wexmoor-Fixture-Lane
LISTV=$(printf '# a comment line\n\n  %s  \r\n%s\n' "$ID1" "$ID2")
STUB=$T/gitleaks-stub
cat > "$STUB" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$STUB_ARGS"
while [ $# -gt 0 ]; do [ "$1" = --report-path ] && { shift; cp "$STUB_REPORT" "$1"; }; shift; done
exit "${STUB_RC:-0}"
EOF
chmod +x "$STUB"
export STUB_ARGS=$T/stub-args STUB_REPORT=$T/stub-report
echo '[]' > "$STUB_REPORT"

g() { git -C "$T/r" -c commit.gpgsign=false "$@"; }
repo() {   # a fresh repository on main with one base commit by the owner, and a staging branch at it
  rm -rf "$T/r"; git init -q -b main "$T/r"
  printf 'one\ntwo\nthree\n' > "$T/r/f.txt"; g add f.txt
  GIT_AUTHOR_NAME=o GIT_AUTHOR_EMAIL=$OWNER GIT_COMMITTER_NAME=o GIT_COMMITTER_EMAIL=$OWNER g commit -q -m base
  g branch staging
}
commit() { # commit <message> [<author name>]: commits whatever is staged (or nothing) as the owner
  GIT_AUTHOR_NAME=${2:-o} GIT_AUTHOR_EMAIL=$OWNER GIT_COMMITTER_NAME=o GIT_COMMITTER_EMAIL=$OWNER \
    g commit -q --allow-empty -m "$1"
}
put() { mkdir -p "$(dirname "$T/r/$1")"; printf '%b' "$2" > "$T/r/$1"; g add -- "$1"; }   # put <path> <content>
sha() { g rev-parse "$1"; }
OUT=$T/out
scan() { # scan [<base>]: runs the check as the workflow does; base defaults to main's tip, staging's and main's tips excluded
  local base; base=$(sha "${1:-main}")
  (cd "$T/r" && PRIVACY_IDENTIFIERS=${LIST-$LISTV} GITLEAKS=${GL-$STUB} PR_TITLE=${TITLE:-} PR_BODY=${BODY:-} \
    PR_BRANCH=${BRANCH:-feature/x} bash "$SCAN" "$base" "$(sha HEAD)" "$(sha staging)" "$(sha main)") > "$OUT" 2>&1
}
leaks() { grep -q -i -F -e "$ID1" -e "$ID2" "$OUT" || { [ -n "${PLANTED:-}" ] && grep -q -F -e "$PLANTED" "$OUT"; }; }
expect() { # expect <exit> <case name> [<text the output must hold>...]
  local want=$1 name=$2 rc=$3; shift 3; local why=""
  [ "$rc" -eq "$want" ] || why="exit $rc, wanted $want"
  for s in "$@"; do grep -q -F -e "$s" "$OUT" || why="$why; no line holding: $s"; done
  leaks && why="$why; the output holds a seeded identifier or secret"
  if [ -z "$why" ]; then pass=$((pass+1)); echo "PASS $name"
  else fail=$((fail+1)); echo "FAIL $name ($why)"; sed 's/^/    /' "$OUT" | grep -v -i -F -e "$ID1" -e "$ID2"; fi
}
run() { local want=$1 name=$2; shift 2; scan; expect "$want" "$name" $? "$@"; }
c12() { sha HEAD | cut -c1-12; }

# ── clean pull requests ──────────────────────────────────────────────────────────────────────────────────────────────
repo; g checkout -q -b feature; put f.txt 'one\ntwo\nthree\nfour\n'; commit "add four"
run 0 "a clean commit passes, and the list's entries are counted (comments, blanks and padding skipped)" \
  "identifier list: 2 entr(y/ies)" "privacy scan: 1 new commit(s)"
repo; g checkout -q -b feature
run 0 "no new commit passes (the PR text is still scanned)" "privacy scan: 0 new commit(s)"
repo; put f.txt "one\n$ID1 two\nthree\n"; commit "on main already"; g checkout -q -b feature; commit "work"
run 0 "a commit already on main or staging is not scanned again"

# ── the identifier list on each scanned place ────────────────────────────────────────────────────────────────────────
repo; g checkout -q -b feature; put f.txt "one\ntwo\nthree\nfour\nfive has ${ID1,,} inside\n"; commit "work"
run 1 "an added line fails, ignoring case, at its file and line" "FINDING $(c12): added line f.txt:5" "1 finding(s) in all"
repo; g checkout -q -b feature; put f.txt "one\n${ID2^^}\nthree\n"; commit "change line two"
run 1 "a changed line fails at its new line number" "FINDING $(c12): added line f.txt:2"
repo; put f.txt "one\n$ID1\nthree\n"; commit "on main"; g checkout -q -b feature; put f.txt 'one\nthree\n'; commit "remove it"
run 0 "a removed line is not a finding (only what a commit adds)"
repo; g checkout -q -b feature; commit "subject

body names $ID2 here"
run 1 "the commit message fails at its line" "FINDING $(c12): message line 3"
repo; g checkout -q -b feature; commit "subject" "$ID1"
run 1 "the author name fails" "FINDING $(c12): author name"
repo; g checkout -q -b feature; put "exports/$ID1.csv" 'a\nb\n'; commit "a file named after an identifier"
run 1 "a path fails, and the path is withheld wherever it is printed" "FINDING $(c12): a changed path (#1 of the commit)"
put "exports/$ID1.csv" "a\n$ID2\n"; commit "and its content"
run 1 "an added line in such a file prints the withheld path with the line" \
  "FINDING $(c12): added line (a path that matches the list, withheld):2"
repo; g checkout -q -b feature; put img.bin "\x00\x01PNG\x00$ID2\x00\xff"; commit "a binary file"
run 1 "a binary file's content fails, counted, never printed" "FINDING $(c12): binary content img.bin"
repo; g checkout -q -b feature; put img.bin '\x00\x01PNG\x00clean\x00\xff'; commit "a clean binary file"
run 0 "a clean binary file passes"
repo; g checkout -q -b feature; commit "work"
TITLE="Fix $ID1"; run 1 "the PR title fails" "FINDING pull request: pull request title"; TITLE=
BODY=$(printf 'Summary\r\n\r\nseen on %s\r\n' "$ID2"); run 1 "the PR description fails at its line" \
  "FINDING pull request: pull request description line 3"; BODY=
BRANCH="feature/${ID1,,}"; run 1 "the branch name fails" "FINDING pull request: pull request branch name"; BRANCH=
ALLOW=gitleaks; ALLOW="$ALLOW:allow"
repo; g checkout -q -b feature; put f.txt "one\ntwo # $ALLOW\nthree\n"; commit "a marker"
run 1 "an added line with gitleaks' inline allow marker is a finding" \
  "FINDING $(c12): added line with a gitleaks allow marker f.txt:2"

# ── merges ───────────────────────────────────────────────────────────────────────────────────────────────────────────
merge_case() { # a feature branch and a side branch that both change f.txt line 2; the merge resolves it to <resolution>
  repo; g checkout -q -b feature; put f.txt 'one\nfeature\nthree\n'; commit "feature"
  g checkout -q -b side main; put f.txt 'one\nside\nthree\n'; commit "side"
  g checkout -q feature; g merge -q side > /dev/null 2>&1; put f.txt "$1"; commit "merge side"
}
merge_case 'one\nfeature and side\nthree\n'
run 0 "a merge whose resolution is clean passes"
merge_case "one\nfeature and $ID2\nthree\n"
run 1 "a line a merge's own resolution adds fails" "FINDING $(c12): added line f.txt:2"
repo; g checkout -q -b side; put g.txt "$ID1\n"; commit "an identifier on a side branch"; g checkout -q main
g checkout -q -b feature; commit "work"; g merge -q --no-ff -m "merge side" side
run 1 "a merged branch's own new commit is scanned (the merge itself adds nothing)" "added line g.txt:1" "1 finding(s) in all"

# ── fails closed ─────────────────────────────────────────────────────────────────────────────────────────────────────
repo; g checkout -q -b feature; commit "work"
LIST=""; run 2 "a missing or empty list fails closed" "missing or empty: fails closed"
LIST=$'# only a comment\n\n'; run 2 "a list of comments and blanks only fails closed" "has no entry"
LIST=$'QZXV-FIXTURE-7731\nab'; run 2 "an entry under 4 characters fails closed" "shorter than 4 characters"; unset LIST
GL=""; run 2 "no gitleaks fails closed" "does not name an executable gitleaks"
GL=$T/nowhere; run 2 "a gitleaks path that is not executable fails closed" "does not name an executable gitleaks"; unset GL
out=$(bash "$SCAN" 2>&1); rc=$?
if [ "$rc" -eq 2 ]; then pass=$((pass+1)); echo "PASS no arguments is a usage error (exit 2)"; else fail=$((fail+1)); echo "FAIL no arguments: exit $rc"; fi
(cd "$T/r" && PRIVACY_IDENTIFIERS=$LISTV GITLEAKS=$STUB bash "$SCAN" main "$(sha HEAD)") > "$OUT" 2>&1
expect 2 "a ref name is not taken for a SHA" $? "every argument a 40-hex SHA"

# ── gitleaks, stubbed: what it is given, and what of its report is printed ──────────────────────────────────────────
PLANTED=stub-planted-value-9f3e
repo; g checkout -q -b feature; put f.txt 'one\ntwo\nthree\nfour\n'; commit "work"
run 0 "gitleaks with an empty report passes"
args=$(cat "$STUB_ARGS")
cfg=$(printf '%s\n' "$args" | awk 'p { print; exit } $0 == "--config" { p = 1 }')
ok=1
for a in git --redact --exit-code --report-format json --gitleaks-ignore-path --no-banner; do
  printf '%s\n' "$args" | grep -q -x -F -e "$a" || ok=0
done
printf '%s\n' "$args" | grep -q -x -F -e "$(sha HEAD) --not $(sha main) $(sha staging) $(sha main)" || ok=0
case "$cfg" in "$T/r/"*|"") ok=0 ;; esac    # the config is the scan's own, never a file of the checkout
if [ "$ok" = 1 ]; then pass=$((pass+1)); echo "PASS gitleaks gets the new commits only, redaction, its own config and ignore file"
else fail=$((fail+1)); echo "FAIL gitleaks arguments: $(printf '%s ' "$args" | tr '\n' ' ')"; fi
printf '[{"RuleID":"aws-access-token","File":"f.txt","StartLine":4,"Commit":"%s","Secret":"%s","Match":"%s","Email":"%s"}]\n' \
  "$(sha HEAD)" "$PLANTED" "$PLANTED" "$OWNER" > "$STUB_REPORT"
run 1 "a gitleaks finding fails with its rule and file:line, never its secret" \
  "FINDING $(c12): gitleaks rule aws-access-token f.txt:4" "gitleaks findings 1"
printf '[{"RuleID":"generic-api-key","File":"exports/%s.csv","StartLine":1,"Commit":"%s","Secret":"%s"}]\n' \
  "$ID1" "$(sha HEAD)" "$PLANTED" > "$STUB_REPORT"
run 1 "a gitleaks finding in a path that matches the list withholds the path" \
  "gitleaks rule generic-api-key (a path that matches the list, withheld):1"
echo '[]' > "$STUB_REPORT"
STUB_RC=1 run 2 "gitleaks failing fails closed" "gitleaks failed (exit 1"
echo 'not json' > "$STUB_REPORT"; run 2 "an unreadable gitleaks report fails closed" "no readable report"
echo '[]' > "$STUB_REPORT"; PLANTED=

# ── gitleaks, the real binary (the workflow runs this part) ─────────────────────────────────────────────────────────
if [ -n "${GITLEAKS_REAL:-}" ]; then
  # a fake access key in AWS's shape, made at run time so no file of this repository holds one
  PLANTED="AKIA$(LC_ALL=C tr -dc 'A-Z2-7' < /dev/urandom | head -c 16)"
  repo; g checkout -q -b feature; put conf.txt "one\nkey = $PLANTED\n"; commit "a seeded key"
  GL=$GITLEAKS_REAL run 1 "the real gitleaks finds the seeded key, printed by rule and file:line only" \
    "FINDING $(c12): gitleaks rule aws-access-token conf.txt:2"
  repo; g checkout -q -b feature; put f.txt 'one\ntwo\nthree\nfour\n'; commit "work"
  GL=$GITLEAKS_REAL run 0 "the real gitleaks passes a clean commit"
  PLANTED=
else
  echo "SKIP the real gitleaks cases (GITLEAKS_REAL is not set; the workflow sets it)"
fi

echo "privacy scan tests: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
