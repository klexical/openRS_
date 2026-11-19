#!/usr/bin/env bash
# privacy-scan.sh <base sha> <head sha> [<also-exclude sha>...] — the pull request's NEW commits (as
# check-commit-identity.sh picks them: reachable from head, not from base nor from any other sha given; the workflow
# passes staging's and main's tips) and the pull request's own text, against the identifier list and gitleaks.
#
# Reads from the environment:
#   PRIVACY_IDENTIFIERS  the identifier list (the Actions secret of that name): one literal per line, matched anywhere in
#                        a line, ignoring case; blank lines and lines starting with # are skipped; every entry 4
#                        characters or more. Missing, empty, or holding a shorter entry: exit 2 (fails closed).
#   GITLEAKS             the path of a gitleaks binary (8.x). Missing or not executable: exit 2 (fails closed).
#   GITHUB_EVENT_PATH    the run's event file: the pull request's title, description and head branch name are read from
#                        it here, never passed through a step's env (the runner prints a step's env in the public log).
#                        Missing or not a pull request event: exit 2 (fails closed).
#
# Against the list: every line each new commit adds (a merge: only the lines its own resolution adds), the whole content
# of each binary file a new commit adds or changes, every path a new commit touches, each new commit's author and
# committer names and addresses and its message, and the pull request's title, description and branch name.
# gitleaks: the new commits' patches, with gitleaks' default rules only (its config is the scan's own). gitleaks still
# reads a .gitleaksignore from the checkout, so a new commit that touches a .gitleaksignore or .gitleaks.toml anywhere
# is itself a finding, as is an added line carrying gitleaks' inline allow marker. gitleaks reads no merge's diff; the
# list scan above covers a merge's own resolution lines.
# No .gitattributes applies (GIT_ATTR_SOURCE is the empty tree, for git under gitleaks too): a -diff, binary or textconv
# rule cannot hide a file's lines; a file is binary only by git's own content test, and its content is then scanned whole.
# Paths are read NUL-separated, so a name that git would quote is scanned like any other.
#
# Prints where, never what: the number of list entries, one line per finding (commit, then file:line or the field; a
# path that itself matches the list is withheld) and a total. Never a matched text, a list entry or a scanned line.
# Exit 0: nothing found; 1: a finding; 2: usage, or a missing or unusable list or scanner (fails closed).
set -euo pipefail
export LC_ALL=C.UTF-8
export GIT_ATTR_SOURCE=4b825dc642cb6eb9a060e54bf8d69288fbee4904   # the empty tree: no .gitattributes applies
die() { echo "privacy scan: $1" >&2; exit 2; }
[ $# -ge 2 ] || die "usage: privacy-scan.sh <base sha> <head sha> [<also-exclude sha>...]"
for s in "$@"; do
  [[ $s =~ ^[0-9a-f]{40}$ ]] || die "every argument a 40-hex SHA"
  git cat-file -e "$s^{commit}" 2>/dev/null || die "${s:0:12} is not a commit here (fetch the history first)"
done
[ -n "${PRIVACY_IDENTIFIERS:-}" ] || die "the identifier list (secret PRIVACY_IDENTIFIERS) is missing or empty: fails closed"
{ [ -n "${GITLEAKS:-}" ] && [ -x "$GITLEAKS" ]; } || die "GITLEAKS does not name an executable gitleaks: fails closed"
{ [ -n "${GITHUB_EVENT_PATH:-}" ] && jq -e '.pull_request | type == "object"' "$GITHUB_EVENT_PATH" > /dev/null 2>&1; } \
  || die "GITHUB_EVENT_PATH does not name a pull request event: fails closed"

umask 077
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
LIST=$T/list
printf '%s\n' "$PRIVACY_IDENTIFIERS" | tr -d '\r' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
  | { grep -v -e '^$' -e '^#' || true; } > "$LIST"
unset PRIVACY_IDENTIFIERS                    # git and gitleaks never see it
entries=$(wc -l < "$LIST")
[ "$entries" -gt 0 ] || die "the identifier list has no entry (only blank or # lines): fails closed"
short=$(while IFS= read -r e; do [ "${#e}" -ge 4 ] || echo; done < "$LIST" | wc -l)   # characters, not bytes
[ "$short" -eq 0 ] || die "$short list entr(y/ies) shorter than 4 characters: fails closed"
echo "identifier list: $entries entr(y/ies)"

base=$1; head=$2; shift 2
commits=$(git rev-list "$head" --not "$base" "$@")

# Every scanned line goes to TEXT; the same line number of WHERE says where it came from (commit, what, path, line),
# tab-separated. Matching runs once over TEXT; only WHERE lines are ever printed.
TEXT=$T/text; WHERE=$T/where; : > "$TEXT"; : > "$WHERE"
add() { printf '%s\n' "$4" >> "$TEXT"; printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "" >> "$WHERE"; }   # add <c12> <what> <path> <text>
ALLOW=gitleaks; ALLOW="$ALLOW:allow"         # gitleaks' inline marker, spelled so this file does not carry it
n=0
for c in $commits; do
  n=$((n+1)); c12=${c:0:12}
  add "$c12" "author name" "" "$(git log -1 --format=%an "$c")"
  add "$c12" "author address" "" "$(git log -1 --format=%ae "$c")"
  add "$c12" "committer name" "" "$(git log -1 --format=%cn "$c")"
  add "$c12" "committer address" "" "$(git log -1 --format=%ce "$c")"
  git log -1 --format=%B "$c" > "$T/msg"
  awk -v c="$c12" '{ printf "%s\tmessage line %d\t\t\n", c, NR }' "$T/msg" >> "$WHERE"; cat "$T/msg" >> "$TEXT"
  [ -z "$(tail -c1 "$T/msg")" ] || echo >> "$TEXT"      # keep TEXT and WHERE line for line
  # the paths the commit touches (a merge: the paths its resolution changes), each scanned as a line of its own
  # NUL-separated, so no name is quoted; as text, a line break inside a name becomes a space
  git diff-tree -z --cc --name-only --no-commit-id --root -r "$c" > "$T/paths.z"
  tr '\n\000' ' \n' < "$T/paths.z" > "$T/paths"
  awk -v c="$c12" '{ printf "%s\ta changed path (#%d of the commit)\t\t\n", c, NR }' "$T/paths" >> "$WHERE"
  cat "$T/paths" >> "$TEXT"
  # the lines the commit adds, with each line's number in the new file; -U0: added lines only, no context
  git -c core.quotePath=false diff-tree -p --cc -U0 --no-color --no-ext-diff --no-renames --no-commit-id --root -r "$c" \
    | awk -v c="$c12" -v text="$TEXT" -v where="$WHERE" '
        /^diff / { inhunk = 0; path = ""; next }
        !inhunk && /^\+\+\+ / { p = substr($0, 5); if (p == "/dev/null") path = ""; else { sub(/^"?b\//, "", p); path = p }; next }
        /^@@/ { match($0, /^@+/); np = RLENGTH - 1; i = index($0, " +"); split(substr($0, i + 2), a, /[, ]/); ln = a[1] + 0; inhunk = 1; next }
        !inhunk || path == "" || /^\\/ { next }
        { pre = substr($0, 1, np)
          if (index(pre, "-") == 0) {
            if (index(pre, "+") > 0) { print substr($0, np + 1) >> text; printf "%s\tadded line\t%s\t%d\n", c, path, ln >> where }
            ln++ } }'
  # binary files the commit adds or changes: their whole content, counted, never printed
  while IFS= read -r -d '' p; do
    case "/$p" in */.gitleaksignore|*/.gitleaks.toml)
      printf '%s\ta change to a gitleaks ignore or config file\t%s\t\n' "$c12" "${p//$'\n'/ }" >> "$T/glctl" ;; esac
    [ "$(git cat-file -t "$c:$p" 2>/dev/null)" = blob ] || continue
    [ "$(git cat-file blob "$c:$p" | head -c 8000 | tr -d -c '\000' | wc -c)" -gt 0 ] || continue
    hit=$(git cat-file blob "$c:$p" | { grep -c -a -i -F -f "$LIST" || true; })
    [ "$hit" -eq 0 ] || printf '%s\tbinary content\t%s\t\n' "$c12" "${p//$'\n'/ }" >> "$T/binhits"
  done < "$T/paths.z"
done
add "" "pull request title" "" "$(jq -r '.pull_request.title // ""' "$GITHUB_EVENT_PATH" | tr '\n' ' ')"
add "" "pull request branch name" "" "$(jq -r '.pull_request.head.ref // ""' "$GITHUB_EVENT_PATH" | tr '\n' ' ')"
jq -r '.pull_request.body // ""' "$GITHUB_EVENT_PATH" | tr -d '\r' > "$T/body"
awk '{ printf "\tpull request description line %d\t\t\n", NR }' "$T/body" >> "$WHERE"; cat "$T/body" >> "$TEXT"
[ "$(wc -l < "$TEXT")" -eq "$(wc -l < "$WHERE")" ] || die "internal: the scanned lines and their places are out of step"

# line numbers of TEXT that match the list, and of added lines that carry gitleaks' allow marker
rc=0; grep -n -a -i -F -f "$LIST" -- "$TEXT" > "$T/raw" || rc=$?; [ "$rc" -le 1 ] || die "grep failed (exit $rc)"
cut -d: -f1 "$T/raw" > "$T/hitlines"; rm -f "$T/raw"
rc=0; grep -n -a -i -F -e "$ALLOW" -- "$TEXT" > "$T/raw" || rc=$?; [ "$rc" -le 1 ] || die "grep failed (exit $rc)"
cut -d: -f1 "$T/raw" > "$T/allowlines"; rm -f "$T/raw"

# a path that itself matches the list is never printed
withhold() { # withhold <tsv file>: column 3 (a path) replaced where it matches the list
  cut -f3 "$1" > "$T/wp"
  rc=0; grep -n -a -i -F -f "$LIST" -- "$T/wp" > "$T/raw" || rc=$?; [ "$rc" -le 1 ] || die "grep failed (exit $rc)"
  cut -d: -f1 "$T/raw" > "$T/wl"; rm -f "$T/raw"
  awk -F'\t' -v OFS='\t' 'FILENAME == ARGV[1] { w[$1] = 1; next } { if (FNR in w) $3 = "(a path that matches the list, withheld)"; print }' "$T/wl" "$1"
}
fmt() { # one finding per line: commit, what, path:line
  awk -F'\t' '{ s = ($1 == "" ? "pull request" : $1) ": " $2; if ($3 != "") s = s " " $3 ($4 != "" ? ":" $4 : ""); print "FINDING " s }'
}
withhold "$WHERE" > "$T/where.safe"
awk 'FILENAME == ARGV[1] { h[$1] = 1; next } FNR in h' "$T/hitlines" "$T/where.safe" | fmt > "$T/out.list"
awk -F'\t' 'FILENAME == ARGV[1] { h[$1] = 1; next } (FNR in h) && $2 == "added line"' "$T/allowlines" "$T/where.safe" \
  | awk -F'\t' -v OFS='\t' '{ $2 = "added line with a gitleaks allow marker"; print }' | fmt > "$T/out.allow"
if [ -s "$T/binhits" ]; then withhold "$T/binhits" | fmt > "$T/out.bin"; else : > "$T/out.bin"; fi
if [ -s "$T/glctl" ]; then withhold "$T/glctl" | fmt >> "$T/out.allow"; fi

# gitleaks over the new commits' patches, default rules only, findings redacted, its own output never shown
printf '[extend]\nuseDefault = true\n' > "$T/gitleaks.toml"; : > "$T/gitleaksignore"
: > "$T/out.gl"; glcount=0
if [ "$n" -gt 0 ]; then
  rc=0
  "$GITLEAKS" git --no-banner --redact --exit-code 0 --log-level error --config "$T/gitleaks.toml" \
    --gitleaks-ignore-path "$T/gitleaksignore" --report-format json --report-path "$T/gl.json" \
    --log-opts "$head --not $base $*" . > "$T/gl.log" 2>&1 || rc=$?
  [ "$rc" -eq 0 ] || die "gitleaks failed (exit $rc; its output is not shown): fails closed"
  jq -e 'type == "array"' "$T/gl.json" > /dev/null 2>&1 || die "gitleaks wrote no readable report: fails closed"
  jq -r '.[] | [(.Commit // "" | .[0:12]), ("gitleaks rule " + (.RuleID // "?")), (.File // ""), ((.StartLine // "") | tostring)] | @tsv' \
    "$T/gl.json" > "$T/glhits"
  glcount=$(wc -l < "$T/glhits")
  [ "$glcount" -eq 0 ] || withhold "$T/glhits" | fmt > "$T/out.gl"
fi

cat "$T/out.list" "$T/out.bin" "$T/out.allow" "$T/out.gl"
total=$(cat "$T/out.list" "$T/out.bin" "$T/out.allow" "$T/out.gl" | wc -l)
echo "privacy scan: $n new commit(s); identifier matches $(($(wc -l < "$T/out.list") + $(wc -l < "$T/out.bin"))), gitleaks findings $glcount, gitleaks suppressions $(wc -l < "$T/out.allow"); $total finding(s) in all"
[ "$total" -eq 0 ]
