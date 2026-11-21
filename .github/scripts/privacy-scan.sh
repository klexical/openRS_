#!/usr/bin/env bash
# privacy-scan.sh <base sha> <head sha> [<also-exclude sha>...] — the pull request's NEW commits (as
# check-commit-identity.sh picks them: reachable from head, not from base nor from any other sha given; the workflow
# passes staging's and main's tips) and the pull request's own text, against the identifier list and gitleaks.
# privacy-scan.sh --history <tip sha>... — the one-time full-history scan: every commit reachable from the tips, with
# no pull request text and no event file (privacy-history.yml, run by hand on main).
#
# Reads from the environment:
#   PRIVACY_IDENTIFIERS  the identifier list (the privacy-scan environment's secret of that name): one literal per
#                        line, matched anywhere in a line, ignoring case; blank lines and lines starting with # are
#                        skipped; every entry 4 characters or more. Missing, empty, or holding a shorter entry: exit 2
#                        (fails closed). A MAC- or VIN-shaped entry matches with any separator, and a MAC also in
#                        reversed byte order and as raw bytes in a file read whole.
#   GITLEAKS             the path of a gitleaks binary (8.x). Missing or not executable: exit 2 (fails closed).
#   GITHUB_EVENT_PATH    the run's event file: the pull request's number, title, description, head branch name and
#                        any label event are read from it here, never passed through a step's env (the runner prints
#                        a step's env in the public log). Missing, not a pull request event, or without a whole
#                        number for the pull request: exit 2 (fails closed).
#
# Against the list: every line each new commit adds (a merge: only the lines its own resolution adds); every path a new
# commit touches; each new commit's raw commit object (every header line, the author and committer names, addresses
# and dates, the encoding, any extra header, and the message; an object that is binary or names an encoding is also
# read whole); the pull request's title, description and branch name. Every entry of 8 letters and digits or more (and
# every MAC- or VIN-shaped entry) is also matched "bare": with \uXXXX, \xXX, %XX, + and HTML entities decoded, NFKD
# applied and accents dropped (full-width and accented letters read as plain ones), every character but letters and
# digits removed and then a 0x before a byte dropped, from the entry and the scanned line alike.
# A file git judges binary on either side of a commit (--numstat "-" "-"), whose new content holds a NUL byte anywhere,
# or that is not UTF-8, is read whole, as bytes and as UTF-16 or UTF-32 in either byte order; a binary file that is not
# a ZIP is also a finding until a reviewer's OK, as it may hold compressed data. A ZIP (found by its structure, at any
# offset) is unpacked and each member read whole and its name scanned. Any other compressed file (gzip, bzip2,
# xz, zstd, tar, PNG, JPEG, PDF, 7z and the like, found by its magic at the start), a compressed or binary ZIP member,
# a Git LFS pointer, a workflow or action file that names the privacy-scan check, and an image or attachment in the
# pull request's title or description are each a finding until a reviewer's OK: the label privacy-ok-compressed being
# added to the pull request (that label event's run only, so a later push needs a new OK).
# Base64 on any scanned line, a run of 8 characters or more (or wrapped over several lines, the last one short), is
# decoded, padded rather than cut, and decoded again if it is base64 itself; gzip, zlib, bzip2, xz and lzma found in it
# by their checksummed formats are decompressed. A list match in any of these is a finding; a run that decodes to a
# compressed file of 100 bytes or more (an embedded image) needs the same OK. Not covered: raw deflate inside base64,
# base32, ascii85, uuencode, rot13, quoted-printable, hex dumps, homoglyphs, an identifier split across lines, a
# shorter entry spelled another way, what an OK'd binary holds inside (SQLite, PDF and the like), and a check name the
# workflow-file OK misses (built by an expression, a YAML escape or an escaped line break, or a check run made through
# the API); the merge step confirms the check's source, see the PR text.
# gitleaks, default rules only (its config is the scan's own), base64 decoded, its inline allow marker ignored, twice:
# over the new commits' patches, on an empty source folder whose .git file points at the repository, so no
# .gitleaksignore or .gitleaks.toml applies; and over the scan's own folder of copies of what the patches do not
# hold (the raw commit objects, the pull request's text, ZIP members, every file read whole and its UTF-16 or UTF-32
# readings, archives opened, each copy named by its kind's extension so gitleaks opens it).
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
# path that itself matches the list is withheld) and a summary line of counts (identifier matches, gitleaks findings and
# suppressions, files waiting for the OK and reviewed, images in the PR's text, the total) that names the pull request
# by number and the head it scanned, and says whether the privacy-ok-compressed label was needed (OK'd or not); a merge
# step reads it.
# Never a matched text, a list entry or a scanned line.
# Exit 0: nothing found; 1: a finding; 2: usage, or a missing or unusable list or scanner (fails closed).
set -eEuo pipefail
# any failing command exits 2, never 1 (a finding): the 0 / 1 / 2 contract holds
trap 'echo "privacy scan: a step failed (line $LINENO): fails closed" >&2; exit 2' ERR
# the list leaves the environment before any other command runs: no child process ever gets it
LISTRAW=${PRIVACY_IDENTIFIERS-}; unset PRIVACY_IDENTIFIERS
export LC_ALL=C.UTF-8
export GIT_ATTR_SOURCE=4b825dc642cb6eb9a060e54bf8d69288fbee4904   # the empty tree: no .gitattributes applies
die() { echo "privacy scan: $1" >&2; exit 2; }
# --history <tip sha>...: the one-time full-history scan (privacy-history.yml, by hand): every commit reachable from
# the tips, with no pull request and no event file
HISTORY=0; [ "${1:-}" != --history ] || { HISTORY=1; shift; }
if [ "$HISTORY" = 1 ]; then [ $# -ge 1 ] || die "usage: privacy-scan.sh --history <tip sha>..."
else [ $# -ge 2 ] || die "usage: privacy-scan.sh <base sha> <head sha> [<also-exclude sha>...]"; fi
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
# the pull request's number, a whole number: the summary names it, so a merge step can find every run of the PR
[ "$HISTORY" = 1 ] || { [ -n "${GITHUB_EVENT_PATH:-}" ] && jq -e '(.pull_request | type == "object") and (.pull_request.number | type == "number")
    and (.pull_request.number | . == floor and . > 0)' "$GITHUB_EVENT_PATH" > /dev/null 2>&1; } \
  || die "GITHUB_EVENT_PATH does not name a pull request event: fails closed"

umask 077
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
# the history scan has no pull request: an empty one stands in for it (no text, no label, so nothing is OK'd)
if [ "$HISTORY" = 1 ]; then
  echo '{"action": "history", "pull_request": {"title": "", "body": "", "head": {"ref": ""}}}' > "$T/event.json"
  GITHUB_EVENT_PATH=$T/event.json
fi
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
import re, sys

MAGIC = [(b"PK\x03\x04", "zip"), (b"PK\x05\x06", "zip"), (b"PK\x07\x08", "zip"), (b"\x1f\x8b", "gzip"),
         (b"\x89PNG", "PNG"), (b"BZh", "bzip2"), (b"\xfd7zXZ\x00", "xz"), (b"\x28\xb5\x2f\xfd", "zstd"),
         (b"7z\xbc\xaf\x27\x1c", "7z"), (b"Rar!", "RAR"), (b"\xff\xd8\xff", "JPEG"), (b"GIF8", "GIF"),
         (b"%PDF", "PDF"), (b"LZIP", "lzip"), (b"\x04\x22\x4d\x18", "LZ4")]

def kind(data):
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "WebP"
    if data[257:262] == b"ustar":
        return "tar"
    for m, k in MAGIC:
        if data.startswith(m):
            return k
    return ""

def is_binary(data):   # git's NUL test over the whole content, not its first 8000 bytes, or not UTF-8 at all
    if b"\0" in data:
        return True
    try:
        data.decode("utf-8")
        return False
    except UnicodeDecodeError:
        return True

def entries(path):
    return [l.rstrip("\n").casefold() for l in open(path, encoding="utf-8") if l.strip()]

U_ESC = re.compile(r"\\u([0-9a-fA-F]{4})|\\x([0-9a-fA-F]{2})")
def bare(text):
    # letters and digits only, after decoding what usually stands for a separator or a letter: %XX and +, HTML
    # entities, \uXXXX and \xXX escapes, and a 0x before a byte; any separator, or none, then reads the same
    t = U_ESC.sub(lambda m: chr(int(m.group(1) or m.group(2), 16)), text)
    t = html.unescape(urllib.parse.unquote_plus(t, errors="replace"))
    # full-width and other compatibility forms read as plain letters, and accents are dropped: NFKD splits a letter
    # from its accent (NFKC would join "e" and a combining accent into one letter that no entry holds)
    t = "".join(c for c in unicodedata.normalize("NFKD", t) if not unicodedata.combining(c))
    # a 0x before a byte goes after the separators, so whether it goes cannot depend on them
    return re.sub(r"0x(?=[0-9a-f]{2})", "", re.sub(r"[\W_]", "", t.casefold()))

def mac_bytes(bares):  # a MAC's six bytes, in either order, for files read whole
    out = []
    for b in bares:
        if re.fullmatch(r"[0-9a-f]{12}", b):
            raw = bytes.fromhex(b); out += [raw, raw[::-1]]
    return out

def hits(data, ents, bares):
    if any(m in data for m in mac_bytes(bares)):
        return True
    texts = [data.decode("utf-8", errors="replace")]
    if b"\0" in data:
        texts += [data[o:].decode(e, errors="replace") for e in ("utf-16-le", "utf-16-be") for o in (0, 1)]
        texts += [data[o:].decode(e, errors="replace") for e in ("utf-32-le", "utf-32-be") for o in range(4)]
    for t in texts:
        t = t.casefold()
        if any(e in t for e in ents):
            return True
        if bares:
            b = bare(t)
            if any(x in b for x in bares):
                return True
    return False

def decoded_texts(data):   # UTF-16 / UTF-32 readings of a file with NULs that look like text, for gitleaks
    out = []
    if b"\0" not in data:
        return out
    for e in ("utf-16-le", "utf-16-be", "utf-32-le", "utf-32-be"):
        for o in range(4 if "32" in e else 2):
            t = data[o:].decode(e, errors="replace")
            if t and sum(c.isprintable() or c in "\r\n\t" for c in t) >= 0.8 * len(t):
                out.append(t)
    return out

def expand(path, barepath):
    # a MAC- or VIN-shaped entry also matches its usual spellings literally; every entry of 8 or more letters and
    # digits (a MAC also reversed) is matched once more in letters-and-digits-only form, from the scanned text alike
    ents = [l.rstrip("\n") for l in open(path, encoding="utf-8")]
    out, bares = list(ents), []
    for e in ents:
        b = re.sub(r"[-:. _]", "", e)
        if re.fullmatch(r"([0-9A-Fa-f]{2}[-: _]?){5}[0-9A-Fa-f]{2}|[0-9A-Fa-f]{4}\.[0-9A-Fa-f]{4}\.[0-9A-Fa-f]{4}", e):
            p = [b[i:i + 2] for i in range(0, 12, 2)]
            out += [s.join(p) for s in (":", "-", " ", "")] + [".".join(b[i:i + 4] for i in range(0, 12, 4))]
            bares += [b.casefold(), "".join(reversed(p)).casefold()]
        elif re.fullmatch(r"[A-HJ-NPR-Za-hj-npr-z0-9]{17}", b):
            out += [b, b[:3] + "-" + b[3:9] + "-" + b[9:], b[:3] + " " + b[3:9] + " " + b[9:]]
        eb = bare(e)
        if len(eb) >= 8:
            bares.append(eb)
    seen, final = set(), []
    for e in out:
        if e.casefold() not in seen:
            seen.add(e.casefold()); final.append(e)
    open(path, "w", encoding="utf-8").write("".join(x + "\n" for x in final))
    open(barepath, "w", encoding="utf-8").write("".join(x + "\n" for x in sorted(set(bares))))

B64 = rb"[A-Za-z0-9+/_-]"
def b64runs(textpath):
    # base64 runs of TEXT, by line: a run inside a line (20 characters or more, or 8 or more that mix cases or hold a
    # digit or + /), or lines of base64 and nothing else joined (wrapped base64, its last line of any length);
    # each reported at its first line
    runs, cur = [], None
    for n, line in enumerate(open(textpath, "rb").read().split(b"\n"), 1):
        t = line.strip()
        if re.fullmatch(B64 + rb"{16,}={0,2}", t) or (cur is not None and re.fullmatch(B64 + rb"+={0,2}", t)):
            if cur is None:
                cur = [n, b""]; runs.append(cur)
            cur[1] += t
            if t.endswith(b"=") or len(t) < 16:
                cur = None
            continue
        cur = None
        for m in re.finditer(B64 + rb"{8,}={0,2}", line):
            r = m.group()
            if len(r) >= 20 or re.search(rb"[0-9+/]", r) or (re.search(rb"[a-z]", r) and re.search(rb"[A-Z]", r)):
                runs.append([n, r])
    return runs

def decodings(run, depth=0):
    for urlsafe in (False, True):
        r = run.rstrip(b"=")
        r = r.replace(b"-", b"+").replace(b"_", b"/") if urlsafe else r
        if not urlsafe and (b"-" in r or b"_" in r):
            continue
        for off in range(4):
            c = r[off:]
            if len(c) % 4 == 1:
                c = c[:-1]
            if len(c) < 8:
                continue
            try:
                d = base64.b64decode(c + b"=" * (-len(c) % 4), validate=True)
            except ValueError:
                continue
            yield d
            if depth == 0 and re.fullmatch(B64 + rb"{8,}={0,2}", d.strip()):
                yield from decodings(d.strip(), 1)   # base64 of base64

def unpack(d):
    # a compressed stream whose format carries a check (gzip, zlib, bzip2, xz) or a known header (lzma-alone):
    # (its kind, its content), or None. Raw deflate is not tried: random bytes too often read as valid deflate.
    tries = []
    if d[:3] == b"\x1f\x8b\x08": tries.append(("gzip", gzip.decompress))
    if len(d) > 2 and d[0] == 0x78 and (d[0] * 256 + d[1]) % 31 == 0: tries.append(("zlib", zlib.decompress))
    if d[:3] == b"BZh": tries.append(("bzip2", bz2.decompress))
    if d[:6] == b"\xfd7zXZ\x00": tries.append(("xz", lzma.decompress))
    if d[:1] == b"\x5d" and d[1:5] != b"\0\0\0\0": tries.append(("lzma", lambda x: lzma.decompress(x, format=lzma.FORMAT_ALONE)))
    for k, f in tries:
        try:
            return k, f(d)[:64 << 20]
        except Exception:
            pass
    return None

cmd = sys.argv[1]
# the other modules only where they are used: classify runs once per file, and its start-up time is most of the run
if cmd not in ("classify", "kind", "texts"):
    import base64, bz2, gzip, html, lzma, unicodedata, urllib.parse, zipfile, zlib
if cmd == "expand":
    expand(sys.argv[2], sys.argv[3])
elif cmd == "classify":   # classify <file>...: per file, "<kind or ->\t<binary 0/1>\t<a ZIP Python can open 0/1>"
    for f in sys.argv[2:]:
        data = open(f, "rb").read()
        zipok = False
        if b"PK\x05\x06" in data:   # is_zipfile needs this end record somewhere; most files skip the zipfile import
            import zipfile
            zipok = zipfile.is_zipfile(f)
        print(f"{kind(data) or '-'}\t{int(is_binary(data))}\t{int(zipok)}")
elif cmd == "kind":
    print(kind(open(sys.argv[2], "rb").read(512)))
elif cmd == "texts":      # texts <file> <out>: its UTF-16 / UTF-32 readings, if any, for gitleaks
    t = decoded_texts(open(sys.argv[2], "rb").read())
    if t:
        open(sys.argv[3], "w", encoding="utf-8", errors="replace").write("\n".join(t))
elif cmd == "blob":       # blob <list> <bare list> <file>: "<1 when the file holds a match, else 0>\t<the kind of a
    # compressed file a base64 run in it decodes to, or ->"; base64 is decoded as on a scanned line; never what matched
    ents, bares = entries(sys.argv[2]), entries(sys.argv[3])
    hit, comp = hits(open(sys.argv[4], "rb").read(), ents, bares), ""
    for n, run in b64runs(sys.argv[4]):
        for d in decodings(run):
            u = unpack(d)
            comp = comp or (u[0] if u else (kind(d) if len(d) >= 32 else ""))
            hit = hit or hits(d, ents, bares) or bool(u and hits(u[1], ents, bares))
            if hit and comp:
                break
        if hit and comp:
            break
    print(f"{int(hit)}\t{comp or '-'}")
elif cmd == "bare":       # bare <in> <out>: each line in letters-and-digits-only form, line for line
    with open(sys.argv[3], "w", encoding="utf-8") as out:
        for line in open(sys.argv[2], "rb").read().split(b"\n")[:-1]:
            out.write(bare(line.decode("utf-8", errors="replace")) + "\n")
elif cmd == "b64":        # b64 <list> <bare list> <text>: "<line>\thit" or "<line>\tcompressed <kind>" per base64 run
    ents, bares = entries(sys.argv[2]), entries(sys.argv[3])
    for n, run in b64runs(sys.argv[4]):
        found = set()
        for d in decodings(run):
            u = unpack(d)
            k = u[0] if u else (kind(d) if len(d) >= 32 else "")
            if k and "comp" not in found:
                found.add("comp"); print(f"{n}\tcompressed {k}")
            if "hit" not in found and (hits(d, ents, bares) or (u and hits(u[1], ents, bares))):
                found.add("hit"); print(f"{n}\thit")
            if len(found) == 2:
                break
PY
python3 -I "$H" expand "$LIST" "$T/listbare"

if [ "$HISTORY" = 1 ]; then tips="$*"; RANGE=("$@")
else base=$1; head=$2; shift 2; RANGE=("$head" --not "$base" "$@"); fi
commits=$(git rev-list "${RANGE[@]}")

# Every scanned line goes to TEXT; the same line number of WHERE says where it came from (commit, what, path, line),
# tab-separated. Matching runs once over TEXT; only WHERE lines are ever printed.
TEXT=$T/text; WHERE=$T/where; : > "$TEXT"; : > "$WHERE"
add() { printf '%s\n' "$4" >> "$TEXT"; printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "" >> "$WHERE"; }   # add <c12> <what> <path> <text>
ALLOW=gitleaks; ALLOW="$ALLOW:allow"         # gitleaks' inline marker, spelled so this file does not carry it

# in_binpaths <path>: git judged the path binary on either side of this commit
# (BINPATHS is filled once per commit, from --numstat)
declare -A BINPATHS
in_binpaths() { [ -n "${BINPATHS["$1"]+x}" ]; }
# blob_hits <file>: sets hit (1 when a file read whole holds one of the list's matches: as bytes (UTF-8 and ASCII), as
# UTF-16 or UTF-32 in either byte order at every alignment, a MAC or VIN with any separator, or inside a base64 run;
# 0 otherwise, never what matched) and b64comp (the kind of a compressed file a base64 run in it decodes to, or empty)
blob_hits() {
  local h r; h=$({ grep -c -a -i -F -f "$LIST" "$1" || true; })
  if [ "$h" -gt 0 ]; then hit=1; b64comp=""; return; fi
  r=$(python3 -I "$H" blob "$LIST" "$T/listbare" "$1"); IFS=$'\t' read -r hit b64comp <<< "$r"
  [ "$b64comp" != - ] || b64comp=""
  [[ $hit =~ ^[01]$ ]] || die "internal: the helper's blob answer is unreadable"
}
# classify <file>: sets kind (the format its first bytes name, or nothing), binary (1: a NUL anywhere, or not UTF-8)
# and zipok (1: Python opens it as a ZIP, a ZIP after a prefix included)
classify() { local r; r=$(python3 -I "$H" classify "$1"); setclass "$r"; }
setclass() { IFS=$'\t' read -r kind binary zipok <<< "$1"; [ "$kind" != - ] || kind=""; }
# keep <file> <c12> <what> <path> [<kind>]: a numbered copy for the second gitleaks run, which reads files whole;
# whole.idx's line N says where copy N came from. An archive's copy is named with its kind's extension, since
# gitleaks opens archives by name, and a file with NULs also gets its UTF-16 / UTF-32 reading as text
WHOLE=$T/whole; mkdir "$WHOLE"; : > "$T/whole.idx"; nw=0
keep() {
  local ext=""
  case "${5:-}" in zip) ext=.zip ;; gzip) ext=.gz ;; bzip2) ext=.bz2 ;; xz) ext=.xz ;; zstd) ext=.zst ;; 7z) ext=.7z ;;
    RAR) ext=.rar ;; tar) ext=.tar ;; esac     # the names gitleaks opens as archives
  nw=$((nw+1)); cp "$1" "$WHOLE/$nw$ext"; printf '%s\t%s\t%s\n' "$2" "$3" "$4" >> "$T/whole.idx"
  rm -f "$T/texts"
  # UTF-16 / UTF-32 readings exist only for a file with a NUL; the helper starts only then. The file is compared with
  # itself NUL-free, read to the end (no head to cut a pipe short, whose broken pipe could trip the ERR trap)
  if ! tr -d '\000' < "$1" | cmp -s - "$1"; then python3 -I "$H" texts "$1" "$T/texts"; fi
  if [ -s "$T/texts" ]; then nw=$((nw+1)); cp "$T/texts" "$WHOLE/$nw"; printf '%s\t%s\t%s\n' "$2" "$3" "$4" >> "$T/whole.idx"; fi
}
# scan_zip <c12> <path> <file>: each member's name and content against the list (by number, never by name); a member
# that is itself compressed is listed for a reviewer's OK; an encrypted, oversized or unreadable one is a finding. Sets
# zipread: 1 once its members are read, 0 for a ZIP that cannot be read (a finding of its own)
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
    printf '%s\ta ZIP that cannot be read\t%s\t\n' "$c12" "$sp" >> "$T/zipbad"; return 0
  fi
  while IFS=$'\t' read -r idx status name; do
    if [ "$idx" != 0 ]; then
      add "$c12" "ZIP member name (#$idx of the ZIP)" "$sp" "$name"
    fi
    if [ "$status" != ok ]; then
      printf '%s\tZIP member #%s %s\t%s\t\n' "$c12" "$idx" "$status" "$sp" >> "$T/zipbad"; continue
    fi
    classify "$d/$idx"
    keep "$d/$idx" "$c12" "in ZIP member #$idx" "$sp" "$kind"
    if [ -n "$kind" ]; then printf '%s\tcompressed ZIP member #%s (%s)\t%s\t\n' "$c12" "$idx" "$kind" "$sp" >> "$T/compressed"
    elif [ "$binary" = 1 ]; then printf '%s\tbinary ZIP member #%s, which may hold compressed data\t%s\t\n' "$c12" "$idx" "$sp" >> "$T/compressed"; fi
    blob_hits "$d/$idx"
    [ "$hit" -eq 0 ] || printf '%s\tZIP member #%s content\t%s\t\n' "$c12" "$idx" "$sp" >> "$T/binhits"
    [ -z "$b64comp" ] || printf '%s\tbase64 in ZIP member #%s, decoding to a compressed file (%s)\t%s\t\n' "$c12" "$idx" "$b64comp" "$sp" >> "$T/compressed"
  done < "$d/index"
  rm -rf "$d"; zipread=1
}
# raw_header <commit object>: its header lines as the formatted fields do not show them: every header, and for the
# author and committer lines only what follows the address (the name and address are scanned as fields)
raw_header() { awk '/^$/ { exit } /^(author|committer) / { i = index($0, ">"); print (i ? substr($0, i + 1) : $0); next } { print }' "$1"; }
n=0
for c in $commits; do
  n=$((n+1)); c12=${c:0:12}
  add "$c12" "author name" "" "$(git log -1 --format=%an "$c")"
  add "$c12" "author address" "" "$(git log -1 --format=%ae "$c")"
  add "$c12" "committer name" "" "$(git log -1 --format=%cn "$c")"
  add "$c12" "committer address" "" "$(git log -1 --format=%ce "$c")"
  git log -1 --format=%B "$c" > "$T/msg"
  # the raw commit object: extra headers, signatures, mergetags, bytes after a NUL or a re-encoded message, which the
  # formatted fields leave out; its headers are scanned as lines, and the whole object is read whole when it holds a
  # NUL, is not UTF-8 or names an encoding; gitleaks reads it all
  git cat-file commit "$c" > "$T/obj"; keep "$T/obj" "$c12" "in the commit object" ""
  raw_header "$T/obj" > "$T/hdr"
  awk -v c="$c12" '{ printf "%s\tcommit object header line %d\t\t\n", c, NR }' "$T/hdr" >> "$WHERE"; cat "$T/hdr" >> "$TEXT"
  [ -z "$(tail -c1 "$T/hdr")" ] || echo >> "$TEXT"
  classify "$T/obj"
  if [ "$binary" = 1 ] || raw_header "$T/obj" | grep -q '^encoding '; then
    blob_hits "$T/obj"; [ "$hit" -eq 0 ] || printf '%s\tcommit object (read whole)\t\t\n' "$c12" >> "$T/binhits"
  fi
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
  BINPATHS=()
  parents=$(git log -1 --format=%P "$c")
  for pp in ${parents:-ROOT}; do
    if [ "$pp" = ROOT ]; then git diff-tree --numstat -z --no-renames --root -r "$c"; else git diff-tree --numstat -z --no-renames -r "$pp" "$c"; fi > "$T/numstat"
    while IFS= read -r -d '' r; do
      case "$r" in $'-\t-\t'*) BINPATHS["${r:4}"]=1 ;; esac
    done < "$T/numstat"
  done
  # each file the commit adds or changes: a ZIP is unpacked and each member scanned; any other compressed file is
  # listed for a reviewer's OK; a binary file's whole content is counted against the list, never printed
  # first each blob is written out, then the helper classifies them all in one start-up, then each is scanned
  rm -rf "$T/b"; mkdir "$T/b"; BPATHS=()
  while IFS= read -r -d '' p; do
    case "/$p" in */.gitleaksignore|*/.gitleaks.toml)
      printf '%s\ta change to a gitleaks ignore or config file\t%s\t\n' "$c12" "${p//[$'\t\n']/ }" >> "$T/glctl" ;; esac
    [ "$(git cat-file -t "$c:$p" 2>/dev/null)" = blob ] || continue
    git cat-file blob "$c:$p" > "$T/b/${#BPATHS[@]}"; BPATHS+=("$p")
  done < "$T/paths.z"
  CLASSES=()
  if [ "${#BPATHS[@]}" -gt 0 ]; then
    BFILES=(); for i in "${!BPATHS[@]}"; do BFILES+=("$T/b/$i"); done
    python3 -I "$H" classify "${BFILES[@]}" > "$T/classes"
    mapfile -t CLASSES < "$T/classes"
    [ "${#CLASSES[@]}" -eq "${#BPATHS[@]}" ] || die "internal: the helper classified ${#CLASSES[@]} of ${#BPATHS[@]} files"
  fi
  for i in "${!BPATHS[@]}"; do
    p=${BPATHS[$i]}; sp=${p//[$'\t\n']/ }
    mv "$T/b/$i" "$T/blob"
    setclass "${CLASSES[$i]}"
    # a Git LFS pointer: the file it stands for goes to LFS storage, never into these git objects, so nothing here
    # can read it; it waits for a reviewer's OK like a compressed file
    if [ "$(head -c 40 "$T/blob" | tr -d '\000')" = "version https://git-lfs.github.com/spec/" ]; then
      printf '%s\ta Git LFS pointer (its file is not in the pull request'"'"'s git objects)\t%s\t\n' "$c12" "$sp" >> "$T/compressed"
    fi
    # a workflow or action that names the check could stand in for it (a job of that name, even a skipped one,
    # counts): a reviewer's OK, and the merge step confirms the passing check's source
    case "$p" in .github/workflows/*|.github/actions/*)
      if grep -q -a -i -E 'privacy[-_ ]?scan' "$T/blob"; then
        printf '%s\ta workflow file that names the privacy-scan check\t%s\t\n' "$c12" "$sp" >> "$T/compressed"
      fi ;; esac
    # scan_zip sets zipread; it is never a condition, since inside one bash ignores set -e and the ERR trap
    zipread=0
    if [ "$kind" = zip ] || [ "$zipok" = 1 ]; then scan_zip "$c12" "$sp" "$T/blob"; fi
    if [ -n "$kind" ] && [ "$kind" != zip ]; then
      printf '%s\tcompressed file (%s)\t%s\t\n' "$c12" "$kind" "$sp" >> "$T/compressed"
    elif [ "$binary" = 1 ] && [ "$zipread" = 0 ] && [ "$kind" != zip ]; then
      # a binary file the scan cannot open: compressed data needs no known header (zlib, raw deflate, lzma, data
      # after a prefix), so any binary file waits for the OK
      printf '%s\ta binary file, which may hold compressed data\t%s\t\n' "$c12" "$sp" >> "$T/compressed"
    fi
    [ "$binary" = 1 ] || [ -n "$kind" ] || [ "$zipok" = 1 ] || in_binpaths "$p" || continue
    keep "$T/blob" "$c12" "in a file read whole" "$sp" "$kind"
    blob_hits "$T/blob"
    [ "$hit" -eq 0 ] || printf '%s\tbinary content\t%s\t\n' "$c12" "$sp" >> "$T/binhits"
    [ -z "$b64comp" ] || printf '%s\tbase64 in a file read whole, decoding to a compressed file (%s)\t%s\t\n' "$c12" "$b64comp" "$sp" >> "$T/compressed"
  done
  rm -rf "$T/b"
done
add "" "pull request title" "" "$(jq -r '.pull_request.title // ""' "$GITHUB_EVENT_PATH" | tr '\n' ' ')"
add "" "pull request branch name" "" "$(jq -r '.pull_request.head.ref // ""' "$GITHUB_EVENT_PATH" | tr '\n' ' ')"
jq -r '.pull_request.body // ""' "$GITHUB_EVENT_PATH" | tr -d '\r' > "$T/body"; keep "$T/body" "" "in the pull request description" ""
jq -r '(.pull_request.title // ""), (.pull_request.head.ref // "")' "$GITHUB_EVENT_PATH" > "$T/prtext"
keep "$T/prtext" "" "in the pull request title or branch name" ""
awk '{ printf "\tpull request description line %d\t\t\n", NR }' "$T/body" >> "$WHERE"; cat "$T/body" >> "$TEXT"
# an image or attachment in the title or description: a screenshot, which nothing here can read; it waits for the OK.
# Any "![" counts: inline, reference-style (the link defined on a later line), collapsed and shortcut images alike
# An HTML tag's name may end its line, with the attributes and closing bracket on the next.
# The summary counts these on their own ("pull request images N": every image or attachment marker in the title and
# description, OK'd or not): unlike a file, a past revision of the description stays readable after an edit, so the
# merge step treats a past image like a past identifier match
IMG='!\[|<(img|video|picture|object|embed|svg)([[:space:]>/]|$)|user-attachments/|private-user-images|camo\.githubusercontent'
jq -r '.pull_request.title // ""' "$GITHUB_EVENT_PATH" > "$T/title"
imgb=$({ grep -o -a -i -E -e "$IMG" "$T/body" || true; } | wc -l)
imgt=$({ grep -o -a -i -E -e "$IMG" "$T/title" || true; } | wc -l)
primg=$((imgb + imgt))
[ "$imgb" -eq 0 ] || printf '\tan image or attachment in the pull request description\t\t\n' >> "$T/compressed"
[ "$imgt" -eq 0 ] || printf '\tan image or attachment in the pull request title\t\t\n' >> "$T/compressed"
[ "$(wc -l < "$TEXT")" -eq "$(wc -l < "$WHERE")" ] || die "internal: the scanned lines and their places are out of step"

# line numbers of TEXT that match the list, and of added lines that carry gitleaks' allow marker
rc=0; grep -n -a -i -F -f "$LIST" -- "$TEXT" > "$T/raw" || rc=$?; [ "$rc" -le 1 ] || die "grep failed (exit $rc)"
cut -d: -f1 "$T/raw" > "$T/hitlines"; rm -f "$T/raw"
if [ -s "$T/listbare" ]; then   # any spelling: the same lines in letters-and-digits-only form, escapes decoded
  python3 -I "$H" bare "$TEXT" "$T/textbare"
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
    python3 -I "$H" bare "$T/wp" "$T/wpbare"
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
    --log-opts "--diff-merges=first-parent ${RANGE[*]}" "$T/glsrc" > "$T/gl.log" 2>&1 || rc=$?
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

# a path that spells "privacy scan:" is printed with the colon masked, so no finding line can pose as the summary line,
# which a merge step reads; the summary is the last line printed
cat "$T/out.list" "$T/out.bin" "$T/out.allow" "$T/out.gl" "$T/out.comp" "$T/out.reviewed" | sed 's/privacy\([^[:alnum:]]*\)scan:/privacy\1scan[:]/Ig'
total=$(cat "$T/out.list" "$T/out.bin" "$T/out.allow" "$T/out.gl" "$T/out.comp" | wc -l)
if [ "$HISTORY" = 1 ]; then who="history of ${tips// /,}"
else
  prnum=$(jq -r '.pull_request.number' "$GITHUB_EVENT_PATH")
  [[ $prnum =~ ^[1-9][0-9]*$ ]] || die "the pull request's number is not a whole number: fails closed"
  who="pull request $prnum; head $head"
fi
okneed=no; [ ! -s "$T/compressed" ] || okneed=yes   # anything that waits for privacy-ok-compressed, OK'd or not
echo "privacy scan: $n new commit(s); $who; ok label needed $okneed; identifier matches $(($(wc -l < "$T/out.list") + $(wc -l < "$T/out.bin"))), gitleaks findings $glcount, gitleaks suppressions $(wc -l < "$T/out.allow"), compressed files $(wc -l < "$T/out.comp") unreviewed and $(wc -l < "$T/out.reviewed") reviewed; pull request images $primg; $total finding(s) in all"
[ "$total" -eq 0 ] || exit 1
