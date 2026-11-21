#!/usr/bin/env bash
# privacy-scan.sh <base sha> <head sha> [<also-exclude sha>...] — the pull request's NEW commits (as
# check-commit-identity.sh picks them: reachable from head, not from base nor from any other sha given; the workflow
# passes staging's and main's tips) and the pull request's own text, against the identifier list and gitleaks.
#
# Reads from the environment:
#   PRIVACY_IDENTIFIERS  the identifier list (the privacy-scan environment's secret of that name): one literal per
#                        line, matched anywhere in a line, ignoring case; blank lines and lines starting with # are
#                        skipped; every entry 4 characters or more. Missing, empty, or holding a shorter entry: exit 2
#                        (fails closed). A MAC- or VIN-shaped entry matches with any separator.
#   GITLEAKS             the path of a gitleaks binary (8.x). Missing or not executable: exit 2 (fails closed).
#   GITHUB_EVENT_PATH    the run's event file: the pull request's title, description, head branch name and any label
#                        event are read from it here, never passed through a step's env (the runner prints a step's
#                        env in the public log). Missing or not a pull request event: exit 2 (fails closed).
#
# Against the list: every line each new commit adds (a merge: only the lines its own resolution adds); every path a new
# commit touches; each new commit's author and committer names and addresses and its message; the pull request's
# title, description and branch name. A file git judges binary on either side of a commit (--numstat "-" "-"), or
# whose new content holds a NUL byte, is read whole, as bytes and as UTF-16 or UTF-32 in either byte order. A ZIP is
# unpacked and each member read whole and its name scanned; any other compressed file (gzip, PNG, JPEG, PDF, 7z and
# the like), a compressed ZIP member or a Git LFS pointer is a finding until a reviewer's OK: the label
# privacy-ok-compressed being added to the pull request (that label event's run only, so a later push needs a new
# OK). Base64 on any scanned line
# (or wrapped over several lines) is decoded: a list match inside is a finding; a run that decodes to a compressed
# file of 100 bytes or more (an embedded image) needs the same OK. A MAC- or VIN-shaped entry is also matched with
# every separator and %XX escape removed, from the entry and the scanned text alike.
# gitleaks, default rules only (its config is the scan's own), base64 decoded, its inline allow marker ignored, twice:
# over the new commits' patches, on an empty source folder whose .git file points at the repository, so no
# .gitleaksignore or .gitleaks.toml applies; and over the scan's own folder of copies of what the patches do not
# hold (the commit messages, the pull request's text, ZIP members and every file read whole, archives opened).
# A new commit that touches either ignore file anywhere is itself a finding, as is an added line carrying the allow
# marker. gitleaks reads a merge's diff against its first parent (--diff-merges=first-parent), so a secret added in a
# merge's own resolution is found; a merge of the base into the branch is read whole, which can re-flag a base commit
# (none should hold a secret). gitleaks' default path allowlist (lock files, images) stands; the list still reads
# those files.
# No .gitattributes applies (GIT_ATTR_SOURCE is the empty tree, for git under gitleaks too; git 2.40 or later is
# required): a -diff, binary or textconv rule cannot hide a file's lines.
# Paths are read NUL-separated, so a name that git would quote is scanned like any other.
#
# Prints where, never what: the number of list entries, one line per finding (commit, then file:line or the field; a
# path that itself matches the list is withheld) and a total. Never a matched text, a list entry or a scanned line.
# Exit 0: nothing found; 1: a finding; 2: usage, or a missing or unusable list or scanner (fails closed).
set -eEuo pipefail
# any failing command exits 2, never 1 (a finding): the 0 / 1 / 2 contract holds
trap 'echo "privacy scan: a step failed (line $LINENO): fails closed" >&2; exit 2' ERR
# the list leaves the environment before any other command runs: no child process ever gets it
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
# One Python helper for what the shell cannot do safely: the MAC / VIN spellings, matching a file read whole, the
# compression formats, and base64 runs. It is written to the scan's own folder and run isolated (-I).
H=$T/helper.py
cat > "$H" <<'PY'
import base64, re, sys

MAGIC = [(b"PK\x03\x04", "zip"), (b"PK\x05\x06", "zip"), (b"PK\x07\x08", "zip"), (b"\x1f\x8b", "gzip"),
         (b"\x89PNG", "PNG"), (b"BZh", "bzip2"), (b"\xfd7zXZ\x00", "xz"), (b"\x28\xb5\x2f\xfd", "zstd"),
         (b"7z\xbc\xaf\x27\x1c", "7z"), (b"Rar!", "RAR"), (b"\xff\xd8\xff", "JPEG"), (b"GIF8", "GIF"),
         (b"%PDF", "PDF"), (b"LZIP", "lzip"), (b"\x04\x22\x4d\x18", "LZ4")]

def kind(data):
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "WebP"
    for m, k in MAGIC:
        if data.startswith(m):
            return k
    return ""

def entries(path):
    return [l.rstrip("\n").casefold() for l in open(path, encoding="utf-8") if l.strip()]

def bare(text):  # letters and digits only (a %XX escape dropped): a MAC or VIN with any separator reads the same
    return re.sub(r"[^0-9a-z]", "", re.sub(r"%[0-9a-f]{2}", "", text))

def hits(data, ents, bares):
    texts = [data.decode("utf-8", errors="replace")]
    texts += [data[off:].decode(enc, errors="replace") for enc in ("utf-16-le", "utf-16-be") for off in (0, 1)]
    texts += [data[off:].decode(enc, errors="replace") for enc in ("utf-32-le", "utf-32-be") for off in range(4)]
    for t in texts:
        t = t.casefold()
        if any(e in t for e in ents):
            return True
        if bares and any(b in bare(t) for b in bares):
            return True
    return False

def expand(path, barepath):
    # a MAC- or VIN-shaped entry also matches its usual spellings literally, and once more with every separator
    # removed, from the entry and the scanned text alike (any separator, or none)
    ents = [l.rstrip("\n") for l in open(path, encoding="utf-8")]
    out, bares = list(ents), []
    for e in ents:
        b = re.sub(r"[-:. _]", "", e)
        if re.fullmatch(r"([0-9A-Fa-f]{2}[-: _]?){5}[0-9A-Fa-f]{2}|[0-9A-Fa-f]{4}\.[0-9A-Fa-f]{4}\.[0-9A-Fa-f]{4}", e):
            p = [b[i:i + 2] for i in range(0, 12, 2)]
            out += [s.join(p) for s in (":", "-", " ", "")] + [".".join(b[i:i + 4] for i in range(0, 12, 4))]
            bares.append(b.casefold())
        elif re.fullmatch(r"[A-HJ-NPR-Za-hj-npr-z0-9]{17}", b):
            out += [b, b[:3] + "-" + b[3:9] + "-" + b[9:], b[:3] + " " + b[3:9] + " " + b[9:]]
            bares.append(b.casefold())
    seen, final = set(), []
    for e in out:
        if e.casefold() not in seen:
            seen.add(e.casefold()); final.append(e)
    open(path, "w", encoding="utf-8").write("".join(x + "\n" for x in final))
    open(barepath, "w", encoding="utf-8").write("".join(x + "\n" for x in sorted(set(bares))))

def b64runs(textpath):
    # base64 runs of TEXT, by line: a run of 20 or more base64 characters inside a line, or lines that are base64
    # and nothing else, joined (wrapped base64); each reported at its first line
    runs, cur = [], None
    for n, line in enumerate(open(textpath, "rb").read().split(b"\n"), 1):
        t = line.strip()
        if re.fullmatch(rb"[A-Za-z0-9+/_-]{16,}={0,2}", t):
            if cur is None:
                cur = [n, b""]; runs.append(cur)
            cur[1] += t
            if t.endswith(b"="):
                cur = None
            continue
        cur = None
        runs += [[n, m.group()] for m in re.finditer(rb"[A-Za-z0-9+/_-]{20,}={0,2}", line)]
    return runs

def decodings(run):
    for urlsafe in (False, True):
        r = run.rstrip(b"=")
        r = r.replace(b"-", b"+").replace(b"_", b"/") if urlsafe else r
        for off in range(4):
            c = r[off:]
            c = c[:len(c) - len(c) % 4]
            if len(c) >= 16:
                try:
                    yield base64.b64decode(c, validate=True)
                except ValueError:
                    pass

cmd = sys.argv[1]
if cmd == "expand":
    expand(sys.argv[2], sys.argv[3])
elif cmd == "kind":
    print(kind(open(sys.argv[2], "rb").read(16)))
elif cmd == "blob":   # blob <list> <bare list> <file>: 1 when the file holds a match, else 0; never what matched
    print(1 if hits(open(sys.argv[4], "rb").read(), entries(sys.argv[2]), entries(sys.argv[3])) else 0)
elif cmd == "b64":    # b64 <list> <bare list> <text>: "<line>\thit" or "<line>\tcompressed <kind>" per base64 run
    ents, bares = entries(sys.argv[2]), entries(sys.argv[3])
    for n, run in b64runs(sys.argv[4]):
        found = set()
        for d in decodings(run):
            if "hit" not in found and hits(d, ents, bares):
                found.add("hit"); print(f"{n}\thit")
            k = kind(d) if len(d) >= 100 else ""   # a screenshot is larger; hashes and short tokens are not
            if k and "comp" not in found:
                found.add("comp"); print(f"{n}\tcompressed {k}")
PY
python3 -I "$H" expand "$LIST" "$T/listbare"

base=$1; head=$2; shift 2
commits=$(git rev-list "$head" --not "$base" "$@")

# Every scanned line goes to TEXT; the same line number of WHERE says where it came from (commit, what, path, line),
# tab-separated. Matching runs once over TEXT; only WHERE lines are ever printed.
TEXT=$T/text; WHERE=$T/where; : > "$TEXT"; : > "$WHERE"
add() { printf '%s\n' "$4" >> "$TEXT"; printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "" >> "$WHERE"; }   # add <c12> <what> <path> <text>
ALLOW=gitleaks; ALLOW="$ALLOW:allow"         # gitleaks' inline marker, spelled so this file does not carry it

# in_binpaths <path>: git judged the path binary on either side of this commit
in_binpaths() { python3 -I -c 'import sys; sys.exit(0 if sys.argv[1].encode("utf-8", "surrogateescape") in open(sys.argv[2], "rb").read().split(b"\0") else 1)' "$1" "$T/binpaths"; }
# blob_hits <file>: 1 when a file read whole holds one of the list's matches: as bytes (UTF-8 and ASCII), as UTF-16 or
# UTF-32 in either byte order at every alignment, or a MAC or VIN with any separator; 0 otherwise, never what matched
blob_hits() {
  local h; h=$({ grep -c -a -i -F -f "$LIST" "$1" || true; })
  if [ "$h" -gt 0 ]; then echo 1; else python3 -I "$H" blob "$LIST" "$T/listbare" "$1"; fi
}
# compressed_kind <file>: the compression format its first bytes name, or nothing
compressed_kind() { python3 -I "$H" kind "$1"; }
# keep <file> <c12> <what> <path>: a numbered copy for the second gitleaks run, which reads files whole; whole.idx's
# line N says where copy N came from
WHOLE=$T/whole; mkdir "$WHOLE"; : > "$T/whole.idx"; nw=0
keep() { nw=$((nw+1)); cp "$1" "$WHOLE/$nw"; printf '%s\t%s\t%s\n' "$2" "$3" "$4" >> "$T/whole.idx"; }
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
    keep "$d/$idx" "$c12" "in ZIP member #$idx" "$sp"
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
  git log -1 --format=%B "$c" > "$T/msg"; keep "$T/msg" "$c12" "in the message" ""
  awk -v c="$c12" '{ printf "%s\tmessage line %d\t\t\n", c, NR }' "$T/msg" >> "$WHERE"; cat "$T/msg" >> "$TEXT"
  [ -z "$(tail -c1 "$T/msg")" ] || echo >> "$TEXT"      # keep TEXT and WHERE line for line
  # the paths the commit touches (a merge: the paths its resolution changes), each scanned as a line of its own
  # NUL-separated, so no name is quoted; as text, a line break inside a name becomes a space
  git diff-tree -z --cc --name-only --no-commit-id --root -r "$c" > "$T/paths.z"
  tr '\n\000' ' \n' < "$T/paths.z" > "$T/paths"
  awk -v c="$c12" '{ printf "%s\ta changed path (#%d of the commit)\t\t\n", c, NR }' "$T/paths" >> "$WHERE"
  cat "$T/paths" >> "$TEXT"
  # the lines the commit adds, with each line's number in the new file; -U0: added lines only, no context; a merge:
  # only lines added against every parent ("++"), the lines its own resolution adds
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
    # a Git LFS pointer: the file it stands for goes to LFS storage, never into these git objects, so nothing here
    # can read it; it waits for a reviewer's OK like a compressed file
    if [ "$(head -c 40 "$T/blob")" = "version https://git-lfs.github.com/spec/" ]; then
      printf '%s\ta Git LFS pointer (its file is not in the pull request'"'"'s git objects)\t%s\t\n' "$c12" "$sp" >> "$T/compressed"
    fi
    if [ "$kind" = zip ]; then
      scan_zip "$c12" "$sp" "$T/blob"
    elif [ -n "$kind" ]; then
      printf '%s\tcompressed file (%s)\t%s\t\n' "$c12" "$kind" "$sp" >> "$T/compressed"
    fi
    nul=$(head -c 8000 "$T/blob" | LC_ALL=C tr -d -c '\000' | wc -c)   # its own step: a failing stage stops the scan
    [ "$nul" -gt 0 ] || [ -n "$kind" ] || in_binpaths "$p" || continue
    keep "$T/blob" "$c12" "in a file read whole" "$sp"
    hit=$(blob_hits "$T/blob")
    [ "$hit" -eq 0 ] || printf '%s\tbinary content\t%s\t\n' "$c12" "$sp" >> "$T/binhits"
  done < "$T/paths.z"
done
add "" "pull request title" "" "$(jq -r '.pull_request.title // ""' "$GITHUB_EVENT_PATH" | tr '\n' ' ')"
add "" "pull request branch name" "" "$(jq -r '.pull_request.head.ref // ""' "$GITHUB_EVENT_PATH" | tr '\n' ' ')"
jq -r '.pull_request.body // ""' "$GITHUB_EVENT_PATH" | tr -d '\r' > "$T/body"; keep "$T/body" "" "in the pull request description" ""
jq -r '(.pull_request.title // ""), (.pull_request.head.ref // "")' "$GITHUB_EVENT_PATH" > "$T/prtext"
keep "$T/prtext" "" "in the pull request title or branch name" ""
awk '{ printf "\tpull request description line %d\t\t\n", NR }' "$T/body" >> "$WHERE"; cat "$T/body" >> "$TEXT"
[ "$(wc -l < "$TEXT")" -eq "$(wc -l < "$WHERE")" ] || die "internal: the scanned lines and their places are out of step"

# line numbers of TEXT that match the list, and of added lines that carry gitleaks' allow marker
rc=0; grep -n -a -i -F -f "$LIST" -- "$TEXT" > "$T/raw" || rc=$?; [ "$rc" -le 1 ] || die "grep failed (exit $rc)"
cut -d: -f1 "$T/raw" > "$T/hitlines"; rm -f "$T/raw"
if [ -s "$T/listbare" ]; then   # a MAC or VIN with any separator: the same lines with every separator and %XX escape removed
  LC_ALL=C sed -e 's/%[0-9A-Fa-f][0-9A-Fa-f]//g' -e 's/[^A-Za-z0-9]//g' "$TEXT" > "$T/textbare"
  rc=0; grep -n -a -i -F -f "$T/listbare" -- "$T/textbare" > "$T/raw" || rc=$?; [ "$rc" -le 1 ] || die "grep failed (exit $rc)"
  cut -d: -f1 "$T/raw" >> "$T/hitlines"; rm -f "$T/raw" "$T/textbare"
fi
rc=0; grep -n -a -i -F -e "$ALLOW" -- "$TEXT" > "$T/raw" || rc=$?; [ "$rc" -le 1 ] || die "grep failed (exit $rc)"
cut -d: -f1 "$T/raw" > "$T/allowlines"; rm -f "$T/raw"

# a path that itself matches the list is never printed
withhold() { # withhold <tsv file>: column 3 (a path) replaced where it matches the list
  cut -f3 "$1" > "$T/wp"
  rc=0; grep -n -a -i -F -f "$LIST" -- "$T/wp" > "$T/raw" || rc=$?; [ "$rc" -le 1 ] || die "grep failed (exit $rc)"
  cut -d: -f1 "$T/raw" > "$T/wl"; rm -f "$T/raw"
  if [ -s "$T/listbare" ]; then
    LC_ALL=C sed -e 's/%[0-9A-Fa-f][0-9A-Fa-f]//g' -e 's/[^A-Za-z0-9]//g' "$T/wp" > "$T/wpbare"
    rc=0; grep -n -a -i -F -f "$T/listbare" -- "$T/wpbare" > "$T/raw" || rc=$?; [ "$rc" -le 1 ] || die "grep failed (exit $rc)"
    cut -d: -f1 "$T/raw" >> "$T/wl"; rm -f "$T/raw" "$T/wpbare"
  fi
  awk -F'\t' -v OFS='\t' 'FILENAME == ARGV[1] { w[$1] = 1; next } { if (FNR in w) $3 = "(a path that matches the list, withheld)"; print }' "$T/wl" "$1"
}
fmt() { # one finding per line: commit, what, path:line
  awk -F'\t' '{ s = ($1 == "" ? "pull request" : $1) ": " $2; if ($3 != "") s = s " " $3 ($4 != "" ? ":" $4 : ""); print "FINDING " s }'
}
withhold "$WHERE" > "$T/where.safe"
# base64 runs on the scanned lines: one that decodes to a list match is a finding; one that decodes to a compressed
# file (an embedded image, say) joins the compressed files below, for a reviewer's OK
python3 -I "$H" b64 "$LIST" "$T/listbare" "$TEXT" > "$T/b64"
touch "$T/binhits" "$T/compressed"
awk -F'\t' -v OFS='\t' -v bin="$T/binhits" -v comp="$T/compressed" '
  FILENAME == ARGV[1] { k[$1] = (k[$1] == "" ? $2 : k[$1] "\n" $2); next }
  FNR in k { on = ($2 == "added line" ? "an added line" : $2); n = split(k[FNR], ks, "\n")
    for (i = 1; i <= n; i++) {
      if (ks[i] == "hit") print $1, "base64 on " on ", holding a list match", $3, $4 >> bin
      else { sub(/^compressed /, "", ks[i]); print $1, "base64 on " on ", decoding to a compressed file (" ks[i] ")", $3, $4 >> comp } } }' \
  "$T/b64" "$T/where.safe"
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
: > "$T/out.gl"; : > "$T/glhits"
if [ "$n" -gt 0 ]; then
  rc=0
  # the source is an empty folder whose .git file points at this repository: gitleaks finds no .gitleaksignore or
  # .gitleaks.toml there (8.28 loads the source's .gitleaksignore whatever --gitleaks-ignore-path says)
  mkdir "$T/glsrc"; printf 'gitdir: %s\n' "$(git rev-parse --absolute-git-dir)" > "$T/glsrc/.git"
  "$GITLEAKS" git --no-banner --redact --exit-code 0 --log-level error --config "$T/gitleaks.toml" \
    --gitleaks-ignore-path "$T/gitleaksignore" --ignore-gitleaks-allow --max-decode-depth 2 \
    --report-format json --report-path "$T/gl.json" \
    --log-opts "--diff-merges=first-parent $head --not $base $*" "$T/glsrc" > "$T/gl.log" 2>&1 || rc=$?
  [ "$rc" -eq 0 ] || die "gitleaks failed (exit $rc; its output is not shown): fails closed"
  jq -e 'type == "array"' "$T/gl.json" > /dev/null 2>&1 || die "gitleaks wrote no readable report: fails closed"
  jq -r '.[] | [(.Commit // "" | .[0:12]), ("gitleaks rule " + (.RuleID // "?")), (.File // ""), ((.StartLine // "") | tostring)] | @tsv' \
    "$T/gl.json" > "$T/glhits"
fi
# the second run reads what the git run cannot: the messages, the pull request's text, ZIP members and every file
# read whole (binary or compressed; gitleaks opens archives itself), from the scan's own folder of numbered copies
rc=0
"$GITLEAKS" dir --no-banner --redact --exit-code 0 --log-level error --config "$T/gitleaks.toml" \
  --gitleaks-ignore-path "$T/gitleaksignore" --ignore-gitleaks-allow --max-decode-depth 2 --max-archive-depth 3 \
  --report-format json --report-path "$T/gld.json" "$WHOLE" > "$T/gld.log" 2>&1 || rc=$?
[ "$rc" -eq 0 ] || die "gitleaks failed (exit $rc; its output is not shown): fails closed"
jq -e 'type == "array"' "$T/gld.json" > /dev/null 2>&1 || die "gitleaks wrote no readable report: fails closed"
# a finding's File is its copy's number in the folder (an archive member's after a "!"); whole.idx gives its place
jq -r --arg w "$WHOLE/" '.[] | [((.File // "") | ltrimstr($w) | split("!")[0] | split("/")[0]), (.RuleID // "?"),
    ((.StartLine // "") | tostring)] | @tsv' "$T/gld.json" \
  | awk -F'\t' -v OFS='\t' 'FILENAME == ARGV[1] { c[FNR] = $1; w[FNR] = $2; p[FNR] = $3; next }
      { n = $1 + 0; what = "gitleaks rule " $2 " " (n in w ? w[n] : "in a file of the scan"); ln = $3
        if (p[n] == "" && ln != "") { what = what ":" ln; ln = "" }
        print c[n], what, p[n], ln }' "$T/whole.idx" - >> "$T/glhits"
glcount=$(wc -l < "$T/glhits")
[ "$glcount" -eq 0 ] || withhold "$T/glhits" | fmt > "$T/out.gl"

cat "$T/out.list" "$T/out.bin" "$T/out.allow" "$T/out.gl" "$T/out.comp" "$T/out.reviewed"
total=$(cat "$T/out.list" "$T/out.bin" "$T/out.allow" "$T/out.gl" "$T/out.comp" | wc -l)
echo "privacy scan: $n new commit(s); identifier matches $(($(wc -l < "$T/out.list") + $(wc -l < "$T/out.bin"))), gitleaks findings $glcount, gitleaks suppressions $(wc -l < "$T/out.allow"), compressed files $(wc -l < "$T/out.comp") unreviewed and $(wc -l < "$T/out.reviewed") reviewed; $total finding(s) in all"
[ "$total" -eq 0 ] || exit 1
