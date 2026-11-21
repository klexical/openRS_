#!/usr/bin/env bash
# privacy-scan.sh <base sha> <head sha> [<also-exclude sha>...] — the pull request's NEW commits (as
# check-commit-identity.sh picks them: reachable from head, not from base nor from any other sha given; the workflow
# passes staging's and main's tips) and the pull request's own text, against the identifier list and gitleaks.
#
# Reads from the environment:
#   PRIVACY_IDENTIFIERS  the identifier list (the privacy-scan environment's secret of that name, Q179): one literal per
#                        line, matched anywhere in a line, ignoring case; blank lines and lines starting with # are
#                        skipped; every entry 4 characters or more. Missing, empty, or holding a shorter entry: exit 2
#                        (fails closed). A MAC- or VIN-shaped entry also matches its other spellings (Q4).
#   GITLEAKS             the path of a gitleaks binary (8.x). Missing or not executable: exit 2 (fails closed).
#   GITHUB_EVENT_PATH    the run's event file: the pull request's title, description, head branch name and any label
#                        event are read from it here, never passed through a step's env (the runner prints a step's
#                        env in the public log). Missing or not a pull request event: exit 2 (fails closed).
#
# Against the list: every line each new commit adds (a merge: only the lines its own resolution adds); every path a new
# commit touches; each new commit's author and committer names and addresses and its message; the pull request's
# title, description and branch name. A file git judges binary on either side of a commit (--numstat "-" "-", G1), or
# whose new content holds a NUL byte, is read whole, as bytes and as UTF-16 in either byte order (G3). A ZIP is
# unpacked and each member read whole and its name scanned; any other compressed file (gzip, PNG, JPEG, PDF, 7z and
# the like), or a compressed ZIP member, is a finding until a reviewer's OK: the label privacy-ok-compressed being
# added to the pull request (Q180; that label event's run only, so a later push needs a new OK).
# gitleaks: the new commits' patches, with gitleaks' default rules only (its config is the scan's own), run on an
# empty source folder whose .git file points at the repository, so no .gitleaksignore or .gitleaks.toml applies (G2);
# a new commit that touches either file anywhere is itself a finding, as is an added line carrying gitleaks' inline
# allow marker. gitleaks reads a merge's diff against its first parent (--diff-merges=first-parent), so a secret added
# in a merge's own resolution is found; a merge of the base into the branch is read whole, which can re-flag a base
# commit (none should hold a secret). gitleaks' default path allowlist (lock files, images) stands; the list still
# reads those files (Q1).
# No .gitattributes applies (GIT_ATTR_SOURCE is the empty tree, for git under gitleaks too; git 2.40 or later is
# required): a -diff, binary or textconv rule cannot hide a file's lines.
# Paths are read NUL-separated, so a name that git would quote is scanned like any other.
#
# Prints where, never what: the number of list entries, one line per finding (commit, then file:line or the field; a
# path that itself matches the list is withheld) and a total. Never a matched text, a list entry or a scanned line.
# Exit 0: nothing found; 1: a finding; 2: usage, or a missing or unusable list or scanner (fails closed).
set -eEuo pipefail
# any failing command exits 2, never 1 (a finding): the 0 / 1 / 2 contract holds (N4)
trap 'echo "privacy scan: a step failed (line $LINENO): fails closed" >&2; exit 2' ERR
# the list leaves the environment before any other command runs: no child process ever gets it (N5)
LISTRAW=${PRIVACY_IDENTIFIERS-}; unset PRIVACY_IDENTIFIERS
export LC_ALL=C.UTF-8
export GIT_ATTR_SOURCE=4b825dc642cb6eb9a060e54bf8d69288fbee4904   # the empty tree: no .gitattributes applies
die() { echo "privacy scan: $1" >&2; exit 2; }
[ $# -ge 2 ] || die "usage: privacy-scan.sh <base sha> <head sha> [<also-exclude sha>...]"
# git 2.40 or later: an older git ignores GIT_ATTR_SOURCE without a word, and a .gitattributes rule could hide lines again
gv=$(git version | sed -n 's/^git version \([0-9][0-9]*\)\.\([0-9][0-9]*\).*/\1 \2/p')
[ -n "$gv" ] || die "cannot read git's version: fails closed"
set -- $gv "$@"; [ "$1" -gt 2 ] || { [ "$1" -eq 2 ] && [ "$2" -ge 40 ]; } || die "git $1.$2 is older than 2.40 (GIT_ATTR_SOURCE): fails closed"
shift 2
for s in "$@"; do
  [[ $s =~ ^[0-9a-f]{40}$ ]] || die "every argument a 40-hex SHA"
  git cat-file -e "$s^{commit}" 2>/dev/null || die "${s:0:12} is not a commit here (fetch the history first)"
done
[ -n "$LISTRAW" ] || die "the identifier list (secret PRIVACY_IDENTIFIERS) is missing or empty: fails closed"
{ [ -n "${GITLEAKS:-}" ] && [ -x "$GITLEAKS" ]; } || die "GITLEAKS does not name an executable gitleaks: fails closed"
{ [ -n "${GITHUB_EVENT_PATH:-}" ] && jq -e '.pull_request | type == "object"' "$GITHUB_EVENT_PATH" > /dev/null 2>&1; } \
  || die "GITHUB_EVENT_PATH does not name a pull request event: fails closed"

umask 077
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
LIST=$T/list
printf '%s\n' "$LISTRAW" | tr -d '\r' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
  | { grep -v -e '^$' -e '^#' || true; } > "$LIST"
LISTRAW=
entries=$(wc -l < "$LIST")
[ "$entries" -gt 0 ] || die "the identifier list has no entry (only blank or # lines): fails closed"
short=$(while IFS= read -r e; do [ "${#e}" -ge 4 ] || echo; done < "$LIST" | wc -l)   # characters, not bytes
[ "$short" -eq 0 ] || die "$short list entr(y/ies) shorter than 4 characters: fails closed"
echo "identifier list: $entries entr(y/ies)"
# a MAC- or VIN-shaped entry also matches its other spellings (Q4): a MAC as six pairs joined by colons, dashes, spaces
# or nothing, or three quads joined by dots; a VIN bare, or as 3-6-8 groups joined by a dash or a space
python3 -I - "$LIST" <<'PY'
import re, sys
path = sys.argv[1]
entries = [l.rstrip("\n") for l in open(path, encoding="utf-8")]
out = list(entries)
for e in entries:
    bare = re.sub(r"[-:. ]", "", e)
    if re.fullmatch(r"([0-9A-Fa-f]{2}[-: ]?){5}[0-9A-Fa-f]{2}|[0-9A-Fa-f]{4}\.[0-9A-Fa-f]{4}\.[0-9A-Fa-f]{4}", e):
        p = [bare[i:i + 2] for i in range(0, 12, 2)]
        out += [s.join(p) for s in (":", "-", " ", "")] + [".".join(bare[i:i + 4] for i in range(0, 12, 4))]
    elif re.fullmatch(r"[A-HJ-NPR-Za-hj-npr-z0-9]{17}", bare):
        out += [bare, bare[:3] + "-" + bare[3:9] + "-" + bare[9:], bare[:3] + " " + bare[3:9] + " " + bare[9:]]
seen, final = set(), []
for e in out:
    if e.lower() not in seen:
        seen.add(e.lower()); final.append(e)
open(path, "w", encoding="utf-8").write("".join(x + "\n" for x in final))
PY

base=$1; head=$2; shift 2
commits=$(git rev-list "$head" --not "$base" "$@")

# Every scanned line goes to TEXT; the same line number of WHERE says where it came from (commit, what, path, line),
# tab-separated. Matching runs once over TEXT; only WHERE lines are ever printed.
TEXT=$T/text; WHERE=$T/where; : > "$TEXT"; : > "$WHERE"
add() { printf '%s\n' "$4" >> "$TEXT"; printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "" >> "$WHERE"; }   # add <c12> <what> <path> <text>
ALLOW=gitleaks; ALLOW="$ALLOW:allow"         # gitleaks' inline marker, spelled so this file does not carry it

# in_binpaths <path>: git judged the path binary on either side of this commit
in_binpaths() { python3 -I -c 'import sys; sys.exit(0 if sys.argv[1].encode("utf-8", "surrogateescape") in open(sys.argv[2], "rb").read().split(b"\0") else 1)' "$1" "$T/binpaths"; }
# blob_hits <file>: how many of the list's matches a file read whole holds: as bytes (UTF-8 and ASCII), or as UTF-16 in
# either byte order at either alignment (G3); a count, never what matched
blob_hits() {
  local h; h=$({ grep -c -a -i -F -f "$LIST" "$1" || true; })
  [ "$h" -gt 0 ] && { echo "$h"; return; }
  python3 -I - "$LIST" "$1" <<'PY'
import sys
entries = [l.rstrip("\n").casefold() for l in open(sys.argv[1], encoding="utf-8") if l.strip()]
data = open(sys.argv[2], "rb").read()
hit = 0
for enc in ("utf-16-le", "utf-16-be"):
    for off in (0, 1):
        text = data[off:].decode(enc, errors="replace").casefold()
        if any(e in text for e in entries): hit = 1
print(hit)
PY
}
# compressed_kind <file>: the compression format its first bytes name, or nothing
compressed_kind() {
  local m; m=$(head -c 12 "$1" | od -An -tx1 -v | tr -d ' \n')
  case "$m" in
    504b0304*|504b0506*|504b0708*) echo zip ;;
    1f8b*) echo gzip ;;            89504e47*) echo PNG ;;         425a68*) echo bzip2 ;;
    fd377a585a00*) echo xz ;;      28b52ffd*) echo zstd ;;        377abcaf271c*) echo 7z ;;
    52617221*) echo RAR ;;         ffd8ff*) echo JPEG ;;          47494638*) echo GIF ;;
    25504446*) echo PDF ;;         4c5a4950*) echo lzip ;;        04224d18*) echo LZ4 ;;
    52494646????????57454250*) echo WebP ;;
  esac
}
# scan_zip <c12> <path> <file>: each member's name and content against the list (by number, never by name); a member
# that is itself compressed is listed for a reviewer's OK; an encrypted, oversized or unreadable one is a finding
scan_zip() {
  local c12=$1 sp=$2 z=$3 d=$T/zip idx status name
  rm -rf "$d"; mkdir "$d"
  if ! python3 -I - "$z" "$d" > "$d/index" 2> /dev/null <<'PY'
import sys, zipfile
z, d = sys.argv[1], sys.argv[2]
LIMIT_TOTAL, LIMIT_COUNT = 256 * 1024 * 1024, 10000
total = 0
with zipfile.ZipFile(z) as zf:
    infos = [i for i in zf.infolist() if not i.is_dir()]
    if len(infos) > LIMIT_COUNT:
        print("0\ttoo many members\t"); sys.exit(0)
    for n, info in enumerate(infos, 1):
        name = info.filename.replace("\t", " ").replace("\n", " ").replace("\r", " ")
        if info.flag_bits & 0x1:
            print(f"{n}\tencrypted\t{name}"); continue
        try:
            with zf.open(info) as src, open(f"{d}/{n}", "wb") as out:
                while True:
                    chunk = src.read(1 << 20)
                    if not chunk: break
                    total += len(chunk)
                    if total > LIMIT_TOTAL: raise OverflowError
                    out.write(chunk)
        except OverflowError:
            print(f"{n}\ttoo large to unpack\t{name}"); sys.exit(0)
        except Exception:
            print(f"{n}\tunreadable\t{name}"); continue
        print(f"{n}\tok\t{name}")
PY
  then
    printf '%s\ta ZIP that cannot be read\t%s\t\n' "$c12" "$sp" >> "$T/zipbad"; return
  fi
  while IFS=$'\t' read -r idx status name; do
    if [ "$idx" != 0 ]; then
      add "$c12" "ZIP member name (#$idx of the ZIP)" "$sp" "$name"
    fi
    if [ "$status" != ok ]; then
      printf '%s\tZIP member #%s %s\t%s\t\n' "$c12" "$idx" "$status" "$sp" >> "$T/zipbad"; continue
    fi
    local mk; mk=$(compressed_kind "$d/$idx")
    [ -z "$mk" ] || printf '%s\tcompressed ZIP member #%s (%s)\t%s\t\n' "$c12" "$idx" "$mk" "$sp" >> "$T/compressed"
    hit=$(blob_hits "$d/$idx")
    [ "$hit" -eq 0 ] || printf '%s\tZIP member #%s content\t%s\t\n' "$c12" "$idx" "$sp" >> "$T/binhits"
  done < "$d/index"
  rm -rf "$d"
}
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
  # the lines the commit adds, with each line's number in the new file; -U0: added lines only, no context; a merge:
  # only lines added against every parent ("++"), the lines its own resolution adds (N2)
  git -c core.quotePath=false diff-tree -p --cc -U0 --no-color --no-ext-diff --no-renames --no-commit-id --root -r "$c" \
    | awk -v c="$c12" -v text="$TEXT" -v where="$WHERE" -v plus="++++++++++++++++" '
        /^diff / { inhunk = 0; path = ""; next }
        !inhunk && /^\+\+\+ / { p = substr($0, 5); if (p == "/dev/null") path = ""; else { sub(/^"?b\//, "", p); sub(/\t$/, "", p); path = p }; next }
        /^@@/ { match($0, /^@+/); np = RLENGTH - 1; i = index($0, " +"); split(substr($0, i + 2), a, /[, ]/); ln = a[1] + 0; inhunk = 1; next }
        !inhunk || path == "" || /^\\/ { next }
        { pre = substr($0, 1, np)
          if (index(pre, "-") == 0) {
            if (pre == substr(plus, 1, np)) { print substr($0, np + 1) >> text; printf "%s\tadded line\t%s\t%d\n", c, path, ln >> where }
            ln++ } }'
  # git's own binary decision, against each parent (--root: a commit with none): "-" "-" in --numstat
  : > "$T/binpaths"
  parents=$(git log -1 --format=%P "$c")
  for pp in ${parents:-ROOT}; do
    if [ "$pp" = ROOT ]; then git diff-tree --numstat -z --no-renames --root -r "$c"; else git diff-tree --numstat -z --no-renames -r "$pp" "$c"; fi \
      | python3 -I -c 'import sys
d = sys.stdin.buffer.read().split(b"\0")
for r in d:
    if r.startswith(b"-\t-\t"): sys.stdout.buffer.write(r[4:] + b"\0")' >> "$T/binpaths"
  done
  # each file the commit adds or changes: a ZIP is unpacked and each member scanned; any other compressed file is
  # listed for a reviewer's OK; a binary file's whole content is counted against the list, never printed
  while IFS= read -r -d '' p; do
    sp=${p//[$'\t\n']/ }
    case "/$p" in */.gitleaksignore|*/.gitleaks.toml)
      printf '%s\ta change to a gitleaks ignore or config file\t%s\t\n' "$c12" "$sp" >> "$T/glctl" ;; esac
    [ "$(git cat-file -t "$c:$p" 2>/dev/null)" = blob ] || continue
    git cat-file blob "$c:$p" > "$T/blob"
    kind=$(compressed_kind "$T/blob")
    if [ "$kind" = zip ]; then
      scan_zip "$c12" "$sp" "$T/blob"
    elif [ -n "$kind" ]; then
      printf '%s\tcompressed file (%s)\t%s\t\n' "$c12" "$kind" "$sp" >> "$T/compressed"
    fi
    nul=$(head -c 8000 "$T/blob" | LC_ALL=C tr -d -c '\000' | wc -c)   # its own step: a failing stage stops the scan (N3)
    [ "$nul" -gt 0 ] || [ -n "$kind" ] || in_binpaths "$p" || continue
    hit=$(blob_hits "$T/blob")
    [ "$hit" -eq 0 ] || printf '%s\tbinary content\t%s\t\n' "$c12" "$sp" >> "$T/binhits"
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
if [ -s "$T/zipbad" ]; then withhold "$T/zipbad" | fmt >> "$T/out.bin"; fi
# compressed files other than a ZIP's readable members: a finding, unless this run is a reviewer's OK, the label
# privacy-ok-compressed being added to the pull request (that label event's run only: any later push runs again
# without it, so an OK covers the head it was given on)
reviewed=$(jq -r '(.action == "labeled") and (.label.name? == "privacy-ok-compressed")' "$GITHUB_EVENT_PATH")
: > "$T/out.comp"; : > "$T/out.reviewed"
if [ -s "$T/compressed" ]; then
  if [ "$reviewed" = true ]; then withhold "$T/compressed" | fmt | sed 's/^FINDING /REVIEWED /' > "$T/out.reviewed"
  else withhold "$T/compressed" | fmt | sed 's/$/ (needs a reviewer'"'"'s OK: the label privacy-ok-compressed)/' > "$T/out.comp"; fi
fi

# gitleaks over the new commits' patches, default rules only, findings redacted, its own output never shown
printf '[extend]\nuseDefault = true\n' > "$T/gitleaks.toml"; : > "$T/gitleaksignore"
: > "$T/out.gl"; glcount=0
if [ "$n" -gt 0 ]; then
  rc=0
  # the source is an empty folder whose .git file points at this repository: gitleaks finds no .gitleaksignore or
  # .gitleaks.toml there (8.28 loads the source's .gitleaksignore whatever --gitleaks-ignore-path says, G2)
  mkdir "$T/glsrc"; printf 'gitdir: %s\n' "$(git rev-parse --absolute-git-dir)" > "$T/glsrc/.git"
  "$GITLEAKS" git --no-banner --redact --exit-code 0 --log-level error --config "$T/gitleaks.toml" \
    --gitleaks-ignore-path "$T/gitleaksignore" --report-format json --report-path "$T/gl.json" \
    --log-opts "--diff-merges=first-parent $head --not $base $*" "$T/glsrc" > "$T/gl.log" 2>&1 || rc=$?
  [ "$rc" -eq 0 ] || die "gitleaks failed (exit $rc; its output is not shown): fails closed"
  jq -e 'type == "array"' "$T/gl.json" > /dev/null 2>&1 || die "gitleaks wrote no readable report: fails closed"
  jq -r '.[] | [(.Commit // "" | .[0:12]), ("gitleaks rule " + (.RuleID // "?")), (.File // ""), ((.StartLine // "") | tostring)] | @tsv' \
    "$T/gl.json" > "$T/glhits"
  glcount=$(wc -l < "$T/glhits")
  [ "$glcount" -eq 0 ] || withhold "$T/glhits" | fmt > "$T/out.gl"
fi

cat "$T/out.list" "$T/out.bin" "$T/out.allow" "$T/out.gl" "$T/out.comp" "$T/out.reviewed"
total=$(cat "$T/out.list" "$T/out.bin" "$T/out.allow" "$T/out.gl" "$T/out.comp" | wc -l)
echo "privacy scan: $n new commit(s); identifier matches $(($(wc -l < "$T/out.list") + $(wc -l < "$T/out.bin"))), gitleaks findings $glcount, gitleaks suppressions $(wc -l < "$T/out.allow"), compressed files $(wc -l < "$T/out.comp") unreviewed and $(wc -l < "$T/out.reviewed") reviewed; $total finding(s) in all"
[ "$total" -eq 0 ] || exit 1
