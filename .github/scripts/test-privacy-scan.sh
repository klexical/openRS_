#!/usr/bin/env bash
# test-privacy-scan.sh — privacy-scan.sh against throwaway repositories built here, with a seeded identifier list.
#   bash .github/scripts/test-privacy-scan.sh                (SCAN=<path> tests another copy: red first)
# gitleaks is a stub here, which records its arguments and writes the report a case gives it; with GITLEAKS_REAL=<path>
# of a real gitleaks binary (the workflow sets it), the last cases also run the real scanner on a seeded fake key.
# Every case also checks that no seeded identifier, in any case, and no seeded secret reaches the output.
# Needs bash 4 or later and GNU tools (Linux, as CI runs it): on macOS, run it in a Linux container.
set -uo pipefail
[ "${BASH_VERSINFO[0]}" -ge 4 ] || { echo "test-privacy-scan.sh needs bash 4 or later (GNU tools; CI runs it on Linux)"; exit 2; }
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
# "gitleaks git" records into $STUB_ARGS and reports $STUB_REPORT; "gitleaks dir" into $STUB_ARGS.dir, $STUB_REPORT_DIR
if [ "$1" = dir ]; then A=$STUB_ARGS.dir; R=${STUB_REPORT_DIR:-}; else A=$STUB_ARGS; R=$STUB_REPORT; fi
printf '%s\n' "$@" > "$A"; printf '%s\n' "${GIT_ATTR_SOURCE:-}" > "$A.attr"
src=${!#}; { cd "$src" && pwd -P; [ -e "$src/.gitleaksignore" ] && echo ignore-file || echo no-ignore-file; } > "$A.src" 2>&1
[ "$1" != dir ] || ls -A "$src" > "$A.files" 2>/dev/null
while [ $# -gt 0 ]; do [ "$1" = --report-path ] && { shift; if [ -n "$R" ]; then cp "$R" "$1"; else echo '[]' > "$1"; fi; }; shift; done
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
  jq -n --arg t "${TITLE:-}" --arg b "${BODY:-}" --arg r "${BRANCH:-feature/x}" --arg a "${ACTION:-synchronize}" \
    --arg l "${LABEL:-}" '{action: $a, pull_request: {title: $t, body: $b, head: {ref: $r}}}
      + (if $l == "" then {} else {label: {name: $l}} end)' > "$T/event.json"
  (cd "$T/r" && PRIVACY_IDENTIFIERS=${LIST-$LISTV} GITLEAKS=${GL-$STUB} GITHUB_EVENT_PATH=${EVENT-$T/event.json} \
    bash "$SCAN" "$base" "$(sha HEAD)" "$(sha staging)" "$(sha main)") > "$OUT" 2>&1
}
MAC=02:5A:1E:7C:3D:9B; VIN=1ZZFXTEST7A000001   # synthetic MAC- and VIN-shaped entries
leaks() { grep -q -i -F -e "$ID1" -e "$ID2" -e 7C3D9B -e 7c:3d:9b -e 7C-3D-9B -e TEST7A000001 "$OUT" \
  || LC_ALL=C sed 's/[^A-Za-z0-9]//g' "$OUT" | grep -q -i -F -e 1E7C3D9B -e FXTEST7A000001 \
  || { [ -n "${PLANTED:-}" ] && grep -q -F -e "$PLANTED" "$OUT"; }; }   # also with every separator removed
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
run 1 "a clean binary file needs a reviewer's OK (the scan cannot show it holds nothing compressed)" \
  "FINDING $(c12): a binary file, which may hold compressed data img.bin (needs a reviewer's OK"
ACTION=labeled LABEL=privacy-ok-compressed run 0 "the OK label passes a clean binary file" "REVIEWED $(c12): a binary file"
repo; g checkout -q -b feature; commit "work"
TITLE="Fix $ID1"; run 1 "the PR title fails" "FINDING pull request: pull request title"; TITLE=
BODY=$(printf 'Summary\r\n\r\nseen on %s\r\n' "$ID2"); run 1 "the PR description fails at its line" \
  "FINDING pull request: pull request description line 3"; BODY=
BRANCH="feature/${ID1,,}"; run 1 "the branch name fails" "FINDING pull request: pull request branch name"; BRANCH=
ALLOW=gitleaks; ALLOW="$ALLOW:allow"
repo; g checkout -q -b feature; put f.txt "one\ntwo # $ALLOW\nthree\n"; commit "a marker"
run 1 "an added line with gitleaks' inline allow marker is a finding" \
  "FINDING $(c12): added line with a gitleaks allow marker f.txt:2"

# ── what a .gitattributes or an awkward file name could hide (review of #449) ─────────────────────────────────────────
for attr in -diff binary "diff=nope"; do
  repo; g checkout -q -b feature; put .gitattributes "notes.txt $attr\n"; put notes.txt "$ID1\n"; commit "an attribute"
  run 1 "a .gitattributes rule ($attr) in the PR does not hide a file's added lines" "added line notes.txt:1"
done
repo; put .gitattributes 'notes.txt -diff\n'; commit "an attribute already on main"; g checkout -q -b feature
put notes.txt "x\n$ID2\n"; commit "a file the attribute names"
run 1 "a .gitattributes rule already on the base does not hide added lines either" "added line notes.txt:2"
for name in 'a"b.bin' 'back\\slash.bin' 'tab\there.bin' 'new\nline.bin'; do
  nm=$(printf '%b' "$name")
  repo; g checkout -q -b feature; printf '\000\001PNG\000%s\000' "$ID2" > "$T/r/$nm"; g add -- "$nm"; commit "an awkward name"
  run 1 "a binary file named ${name} is scanned" "binary content"
done
# a tab in a name must not split the printed row: the part after it would escape withholding (re-review of #449)
repo; g checkout -q -b feature; nm=$(printf 'a\t%s.bin' "$ID1"); printf '\000\001%s\000' "$ID2" > "$T/r/$nm"
g add -- "$nm"; commit "a tab and an identifier in one name"
run 1 "a binary file whose name holds a tab and an identifier has its path withheld" \
  "binary content (a path that matches the list, withheld)"
repo; g checkout -q -b feature; mkdir -p "$T/r/$(printf 'x\t%s' "$ID2")"; put "$(printf 'x\t%s' "$ID2")/.gitleaksignore" 'y\n'
commit "a tab and an identifier in a suppression file's folder"
run 1 "a gitleaks ignore file whose path holds a tab and an identifier has its path withheld" \
  "a change to a gitleaks ignore or config file (a path that matches the list, withheld)"
repo; g checkout -q -b feature; printf 'x\000%s\n' "$ID1" > "$T/r/b.dat"; g add b.dat; commit "binary"; g rm -q b.dat; commit "removed"
run 1 "a binary file a later commit removes is still scanned in the commit that added it" "binary content b.dat"

for f in .gitleaksignore sub/.gitleaksignore .gitleaks.toml; do
  repo; g checkout -q -b feature; put "$f" '0123456789abcdef:conf.txt:aws-access-token:2\n'; commit "a suppression"
  run 1 "a new commit touching $f is a finding (gitleaks reads it from the checkout)" \
    "FINDING $(c12): a change to a gitleaks ignore or config file $f"
done
repo; put .gitleaksignore 'x\n'; commit "on main"; g checkout -q -b feature; g rm -q .gitleaksignore; commit "removed"
run 1 "removing a .gitleaksignore is a finding too" "a change to a gitleaks ignore or config file .gitleaksignore"

# ── compressed files: ZIPs unpacked, the rest need a reviewer's OK ────────────────────────────────────────────────
mkzip() { # mkzip <zip path> <member name> <member content (printf %b)> [<member name> <content>]...
  local z=$1; shift; python3 -I - "$T/r/$z" "$@" <<'PY'
import sys, zipfile
z, rest = sys.argv[1], sys.argv[2:]
with zipfile.ZipFile(z, "w", zipfile.ZIP_DEFLATED) as zf:
    for i in range(0, len(rest), 2):
        zf.writestr(rest[i], rest[i + 1].encode().decode("unicode_escape").encode("latin-1"))
PY
  g add -- "$z"
}
repo; g checkout -q -b feature; mkzip a.zip doc.txt 'clean text\n' sub/two.txt 'more\n'; commit "a clean ZIP"
run 0 "a clean ZIP passes: its members are unpacked and scanned"
repo; g checkout -q -b feature; mkzip a.zip doc.txt 'clean\n' notes.txt "deflated $ID1 inside\n"; commit "a ZIP"
run 1 "a ZIP member's deflated content fails, by member number" "FINDING $(c12): ZIP member #2 content a.zip"
repo; g checkout -q -b feature; mkzip a.zip "exports/$ID2.csv" 'x\n'; commit "a ZIP"
run 1 "a ZIP member's name fails, by number, never printed" "FINDING $(c12): ZIP member name (#1 of the ZIP) a.zip"
repo; g checkout -q -b feature; printf 'inner %s\n' "$ID1" | gzip -c > "$T/inner.gz"
python3 -I -c 'import sys,zipfile; zipfile.ZipFile(sys.argv[1],"w").write(sys.argv[2],"inner.gz")' "$T/r/b.zip" "$T/inner.gz"
g add b.zip; commit "a ZIP holding a gzip"
run 1 "a compressed ZIP member needs a reviewer's OK" "FINDING $(c12): compressed ZIP member #1 (gzip) b.zip (needs a reviewer's OK"
repo; g checkout -q -b feature; printf 'x\n' > "$T/s.txt"; (cd "$T" && zip -q -P pw "$T/r/e.zip" s.txt); g add e.zip; commit "encrypted"
run 1 "an encrypted ZIP member is a finding (it cannot be scanned)" "FINDING $(c12): ZIP member #1 encrypted e.zip"
repo; g checkout -q -b feature; put bad.zip 'PK\x03\x04 not really a zip'; commit "a broken ZIP"
run 1 "a ZIP that cannot be read is a finding" "FINDING $(c12): a ZIP that cannot be read bad.zip"
repo; g checkout -q -b feature; printf 'hello\n' | gzip -c > "$T/r/data.gz"; g add data.gz; commit "a gzip"
run 1 "a gzip file needs a reviewer's OK" "FINDING $(c12): compressed file (gzip) data.gz (needs a reviewer's OK: the label privacy-ok-compressed)"
ACTION=labeled LABEL=privacy-ok-compressed run 0 "the label privacy-ok-compressed being added is the OK (that run passes)" \
  "REVIEWED $(c12): compressed file (gzip) data.gz" "0 finding(s) in all"
ACTION=labeled LABEL=bug run 1 "another label being added is no OK" "compressed file (gzip) data.gz (needs a reviewer's OK"
ACTION=synchronize LABEL=privacy-ok-compressed run 1 "a later push's run is no OK, even with the label on the event" \
  "compressed file (gzip) data.gz (needs a reviewer's OK"
repo; g checkout -q -b feature; put icon.png '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR'; commit "a PNG"
run 1 "a PNG needs a reviewer's OK" "FINDING $(c12): compressed file (PNG) icon.png"
repo; g checkout -q -b feature; put "$ID1.gz" '\x1f\x8b\x08\x00'; commit "a gzip with an identifier in its name"
run 1 "a compressed file's path that matches the list is withheld" \
  "compressed file (gzip) (a path that matches the list, withheld)"

# ── binary to text, UTF-16, MAC and VIN spellings ──────────────────────────────────────────────────────────────────
repo; put b.dat 'x\000y\n'; commit "binary on main"; g checkout -q -b feature; put b.dat "now text with $ID1\n"; commit "to text"
run 1 "a file that turns from binary to text is read whole (git sees binary on either side)" "FINDING $(c12): binary content b.dat"
repo; put t.txt "plain\n"; commit "text on main"; g checkout -q -b feature; put t.txt "x\000$ID1\n"; commit "to binary"
run 1 "a file that turns from text to binary is read whole" "FINDING $(c12): binary content t.txt"
u16() { python3 -I -c 'import sys; sys.stdout.buffer.write(sys.argv[2].encode(sys.argv[1]))' "$@"; }
repo; g checkout -q -b feature; { printf '\xff\xfe'; u16 utf-16-le "notes: $ID1 here"; } > "$T/r/u16le.txt"; g add u16le.txt; commit "UTF-16LE"
run 1 "a UTF-16LE file fails" "FINDING $(c12): binary content u16le.txt"
repo; g checkout -q -b feature; { printf '\xfe\xff'; u16 utf-16-be "odd ${ID2,,}"; } > "$T/r/u16be.txt"; g add u16be.txt; commit "UTF-16BE"
run 1 "a UTF-16BE file fails, ignoring case" "FINDING $(c12): binary content u16be.txt"
repo; g checkout -q -b feature; { printf 'x'; u16 utf-16-le "$ID1"; } > "$T/r/odd.bin"; g add odd.bin; commit "UTF-16 at an odd offset"
run 1 "UTF-16 text at an odd byte offset fails" "FINDING $(c12): binary content odd.bin"
repo; g checkout -q -b feature; u16 utf-16-le "member $ID2" > "$T/m.txt"
python3 -I -c 'import sys,zipfile; zipfile.ZipFile(sys.argv[1],"w",zipfile.ZIP_DEFLATED).write(sys.argv[2],"m.txt")' "$T/r/u.zip" "$T/m.txt"
g add u.zip; commit "a ZIP of UTF-16"
run 1 "a deflated UTF-16 ZIP member fails" "FINDING $(c12): ZIP member #1 content u.zip"
for spelled in "${MAC,,}" "${MAC//:/-}" "${MAC//:/ }" "${MAC//:/}" "025a.1e7c.3d9b"; do
  repo; g checkout -q -b feature; put f.txt "one\nadapter $spelled seen\nthree\n"; commit "a MAC"
  LIST=$(printf '%s\n%s\n' "$ID1" "$MAC") run 1 "a MAC-shaped entry matches the spelling ${spelled//[0-9a-fA-F]/h}" "FINDING $(c12): added line f.txt:2"
done
repo; g checkout -q -b feature; put f.txt "one\nadapter ${MAC//:/}\nthree\n"; commit "a MAC"
LIST=$(printf '%s\n%s\n' "$ID1" "${MAC//:/-}") run 1 "a MAC-shaped entry written with dashes matches the bare spelling" "added line f.txt:2"
for spelled in "$VIN" "${VIN,,}" "1ZZ-FXTEST-7A000001" "1ZZ FXTEST 7A000001"; do
  repo; g checkout -q -b feature; put f.txt "one\nvin $spelled\nthree\n"; commit "a VIN"
  LIST=$(printf '%s\n%s\n' "$ID1" "$VIN") run 1 "a VIN-shaped entry matches the spelling ${spelled//[0-9A-Za-z]/x}" "FINDING $(c12): added line f.txt:2"
done
repo; g checkout -q -b feature; put f.txt 'one\nnothing here 1ZZ\nthree\n'; commit "no VIN"
LIST=$(printf '%s\n%s\n%s\n' "$ID1" "$VIN" "$MAC") run 0 "MAC- and VIN-shaped entries add no false match on unrelated text" "identifier list: 3 entr(y/ies)"

# ── what a separator, an encoding or a second scanner could still let past ────────────────────────────────────────────
for spelled in "${MAC//:/_}" "${MAC//:/%3A}" "${MAC//:/ : }" "02:5a-1e_7c 3d.9b"; do
  repo; g checkout -q -b feature; put f.txt "one\nadapter $spelled seen\nthree\n"; commit "a MAC"
  LIST=$(printf '%s\n%s\n' "$ID1" "$MAC") run 1 "a MAC-shaped entry matches any separator: ${spelled//[0-9a-fA-F]/h}" "FINDING $(c12): added line f.txt:2"
done
repo; g checkout -q -b feature; put f.txt "one\nvin 1ZZ.FXTEST.7A000001 and 1ZZ_FXTEST_7A000001\nthree\n"; commit "a VIN"
LIST=$(printf '%s\n%s\n' "$ID1" "$VIN") run 1 "a VIN-shaped entry matches any separator" "FINDING $(c12): added line f.txt:2"
repo; g checkout -q -b feature; { printf '\xff\xfe'; u16 utf-16-le "mac ${MAC//:/_}"; } > "$T/r/m16.txt"; g add m16.txt; commit "UTF-16 MAC"
LIST=$(printf '%s\n%s\n' "$ID1" "$MAC") run 1 "a MAC with any separator in a file read whole fails" "FINDING $(c12): binary content m16.txt"
repo; g checkout -q -b feature; put "${MAC//:/_}.csv" "a\n$ID1\n"; commit "a file named after a MAC, underscores"
LIST=$(printf '%s\n%s\n' "$ID1" "$MAC") run 1 "a path holding a MAC with any separator is withheld" \
  "FINDING $(c12): added line (a path that matches the list, withheld):2"
repo; g checkout -q -b feature; u16 utf-32-le "notes: $ID1 here" > "$T/r/u32.txt"; g add u32.txt; commit "UTF-32LE"
run 1 "a UTF-32 file fails" "FINDING $(c12): binary content u32.txt"
repo; g checkout -q -b feature; { printf 'xyz'; u16 utf-32-be "${ID2,,}"; } > "$T/r/u32b.bin"; g add u32b.bin; commit "UTF-32BE, odd offset"
run 1 "UTF-32BE text at an odd byte offset fails" "FINDING $(c12): binary content u32b.bin"
repo; g checkout -q -b feature; put shots/drive.png 'version https://git-lfs.github.com/spec/v1\noid sha256:4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393\nsize 12345\n'; commit "an LFS pointer"
run 1 "a Git LFS pointer needs a reviewer's OK (its content is not in the git objects)" \
  "FINDING $(c12): a Git LFS pointer (its file is not in the pull request's git objects) shots/drive.png (needs a reviewer's OK"
b64() { printf '%b' "$1" | base64 -w0; }
repo; g checkout -q -b feature; put notes.md "one\nsee data:text/plain;base64,$(b64 "the adapter is $ID1 here")\n"; commit "base64"
run 1 "an identifier inside base64 on an added line fails at its line" "FINDING $(c12): base64 on an added line, holding a list match notes.md:2"
repo; g checkout -q -b feature; printf 'header\n%s\nfooter\n' "$(printf 'padding %.0s' {1..12}; printf 'then %s at the end of a long text' "$ID2")" > "$T/b.txt"
{ printf 'one\n-----BEGIN DATA-----\n'; base64 -w 40 "$T/b.txt"; printf -- '-----END DATA-----\n'; } > "$T/r/wrapped.txt"; g add wrapped.txt; commit "wrapped base64"
run 1 "an identifier inside base64 wrapped over several lines fails" "FINDING $(c12): base64 on an added line, holding a list match wrapped.txt:3"
repo; g checkout -q -b feature; { printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR'; head -c 300 /dev/zero; } > "$T/shot.png"
put shot.svg "<svg><image href=\"data:image/png;base64,$(base64 -w0 "$T/shot.png")\"/></svg>\n"; commit "an embedded image"
run 1 "a PNG embedded as base64 in a text file needs a reviewer's OK" \
  "FINDING $(c12): base64 on an added line, decoding to a compressed file (PNG) shot.svg:1 (needs a reviewer's OK"
ACTION=labeled LABEL=privacy-ok-compressed run 0 "the OK label covers an embedded image too" "REVIEWED $(c12): base64 on an added line, decoding to a compressed file (PNG) shot.svg:1"
repo; g checkout -q -b feature; put body.txt "x\n"; commit "work"
BODY="![shot](data:image/png;base64,$(base64 -w0 "$T/shot.png"))" run 1 "an image embedded as base64 in the PR description needs a reviewer's OK" \
  "FINDING pull request: base64 on pull request description line 1, decoding to a compressed file (PNG)"
repo; g checkout -q -b feature; for i in 1 2 3 4 5 6 7 8; do head -c 64 /dev/urandom > "$T/h"; printf '"integrity": "sha512-%s",\n' "$(base64 -w0 "$T/h")"; done > "$T/r/lock.json"
printf '"short": "%s",\n' "$(printf '\x1f\x8b\x08\x00abcdefgh' | base64 -w0)" >> "$T/r/lock.json"; g add lock.json; commit "a lockfile"
run 0 "short base64 (hashes, a few bytes after a magic) is no compressed-file finding"

# ── the review of the rebuild: base64 tails, late NULs, hidden compression, the raw commit, images, variants ──────────
repo; g checkout -q -b feature; put f.txt "one\nvin $(b64 "$VIN")\n"; commit "a VIN in base64 alone"
LIST=$(printf '%s\n%s\n' "$ID1" "$VIN") run 1 "base64 of an entry alone (its last bytes in the padding) fails" "base64 on an added line, holding a list match f.txt:2"
for pre in "" "a" "ab" "abc"; do
  repo; g checkout -q -b feature; put f.txt "one\nx=$(b64 "$pre$ID1")\n"; commit "an entry at the end of base64"
  run 1 "an entry at the very end of base64 (prefix of ${#pre}) fails" "base64 on an added line, holding a list match f.txt:2"
done
repo; g checkout -q -b feature; head -c 40 /dev/zero | tr '\0' 'f' > "$T/w.txt"; printf '%s' "$ID2" >> "$T/w.txt"
{ printf 'one\n'; base64 -w 76 "$T/w.txt"; } > "$T/r/w.txt"; [ "$(tail -n1 "$T/r/w.txt" | wc -c)" -le 16 ] || echo "SETUP: the last line is not short"
g add w.txt; commit "wrapped base64 with a short last line"
run 1 "wrapped base64 whose last line is short fails" "base64 on an added line, holding a list match w.txt:2"
repo; g checkout -q -b feature; MACB=$(printf '\x02\x5a\x1e\x7c\x3d\x9b' | base64 -w0); put f.txt "one\nmac = \"$MACB\"\n"; commit "a MAC's bytes in base64"
LIST=$(printf '%s\n%s\n' "$ID1" "$MAC") run 1 "base64 of a MAC's six bytes fails" "base64 on an added line, holding a list match f.txt:2"
repo; g checkout -q -b feature; put f.txt "one\nx = $(printf '%s' "the $ID1 here" | base64 -w0 | base64 -w0)\n"; commit "double base64"
run 1 "double base64 fails" "base64 on an added line, holding a list match f.txt:2"
repo; g checkout -q -b feature; put f.txt "one\nblob = $(printf 'tiny %s' "$ID2" | gzip -c | base64 -w0)\n"; commit "a small gzip in base64"
run 1 "a small gzip in base64 needs the OK, and its content is read" \
  "base64 on an added line, decoding to a compressed file (gzip) f.txt:2" "base64 on an added line, holding a list match f.txt:2"
repo; g checkout -q -b feature; { head -c 8100 /dev/zero | tr '\0' 'a'; u16 utf-16-le "$ID1"; } > "$T/r/late.txt"; g add late.txt; commit "UTF-16 after byte 8000"
run 1 "UTF-16 text after the first 8000 bytes is read" "FINDING $(c12): binary content late.txt"
repo; g checkout -q -b feature; { head -c 8100 /dev/zero | tr '\0' 'a'; u16 utf-32-le "$ID2"; } > "$T/r/late32.txt"; g add late32.txt; commit "UTF-32 after byte 8000"
run 1 "UTF-32 text after the first 8000 bytes is read" "FINDING $(c12): binary content late32.txt"
pyc() { python3 -I -c "$1" "$T/r/$2"; g add -- "$2"; }
repo; g checkout -q -b feature; pyc 'import sys,zlib; open(sys.argv[1],"wb").write(zlib.compress(b"hidden text "*20))' z.dat; commit "zlib"
run 1 "a zlib stream needs the OK" "FINDING $(c12): a binary file, which may hold compressed data z.dat (needs a reviewer's OK"
repo; g checkout -q -b feature; pyc 'import sys,zlib; c=zlib.compressobj(9,zlib.DEFLATED,-15); open(sys.argv[1],"wb").write(c.compress(b"hidden text "*20)+c.flush())' d.dat; commit "raw deflate"
run 1 "raw deflate needs the OK" "a binary file, which may hold compressed data d.dat"
repo; g checkout -q -b feature; pyc 'import sys,lzma; open(sys.argv[1],"wb").write(lzma.compress(b"hidden text "*20, format=lzma.FORMAT_ALONE))' l.dat; commit "lzma"
run 1 "lzma-alone needs the OK" "a binary file, which may hold compressed data l.dat"
repo; g checkout -q -b feature; pyc 'import sys,gzip; open(sys.argv[1],"wb").write(b"PREFIX:"+gzip.compress(b"hidden"))' p.dat; commit "gzip after a prefix"
run 1 "gzip after a prefix needs the OK" "a binary file, which may hold compressed data p.dat"
repo; g checkout -q -b feature; python3 -I -c 'import sys,zipfile,io
b=io.BytesIO(); z=zipfile.ZipFile(b,"w",zipfile.ZIP_DEFLATED); z.writestr("m.txt", sys.argv[2]); z.close()
open(sys.argv[1],"wb").write(b"HEADER"+b.getvalue())' "$T/r/pz.dat" "member $ID1 text"; g add pz.dat; commit "a ZIP after a prefix"
run 1 "a ZIP after a prefix is unpacked and its member read" "FINDING $(c12): ZIP member #1 content pz.dat"
repo; g checkout -q -b feature; put latin.txt 'caf\xe9 au lait\n'; commit "not UTF-8"
run 1 "a file that is not UTF-8 is read whole and needs the OK" "a binary file, which may hold compressed data latin.txt"
# the raw commit object: headers, signatures and message bytes that the formatted fields leave out
rawcommit() { # rawcommit <python expression of the object's bytes, given t (tree), p (parent), o (identity)>
  local obj; obj=$(python3 -I -c 'import sys
t,p,o=sys.argv[1],sys.argv[2],sys.argv[3]; ID=sys.argv[4]
sys.stdout.buffer.write(eval(sys.argv[5]))' "$(g rev-parse HEAD^{tree})" "$(g rev-parse HEAD)" "o <$OWNER> 1700000000 +0000" "$ID1" "$1" \
    | git -C "$T/r" hash-object -t commit -w --literally --stdin); g update-ref refs/heads/feature "$obj"; }
for e in \
  'f"tree {t}\nparent {p}\nauthor {o}\ncommitter {o}\nx-extra {ID}\n\nsubject\n".encode()' \
  'f"tree {t}\nparent {p}\nauthor {o}\ncommitter {o}\ngpgsig -----BEGIN PGP SIGNATURE-----\n {ID}\n -----END PGP SIGNATURE-----\n\nsubject\n".encode()' \
  'f"tree {t}\nparent {p}\nauthor {o}\ncommitter {o}\nmergetag object {p}\n type commit\n tag {ID}\n\nsubject\n".encode()' \
  'f"tree {t}\nparent {p}\nauthor {o}\ncommitter {o}\n\nsubject\n".encode()+b"\0"+ID.encode()+b"\n"' \
  'f"tree {t}\nparent {p}\nauthor {o}\ncommitter {o}\nencoding UTF-16LE\n\n".encode()+("subject "+ID).encode("utf-16-le")' \
  'f"tree {t}\nparent {p}\nauthor {o} {ID}\ncommitter {o}\n\nsubject\n".encode()'; do
  repo; g checkout -q -b feature; commit "work"; rawcommit "$e"
  run 1 "a crafted commit object is read raw: ${e:40:28}…" "FINDING $(c12): commit object"
done
repo; g checkout -q -b feature; commit "work"
BODY='Summary\n\n![shot](https://github.com/user-attachments/assets/0f0f0f0f-aaaa-bbbb-cccc-000000000000)' \
  run 1 "an image in the PR description needs the OK" "FINDING pull request: an image or attachment in the pull request description (needs a reviewer's OK"
BODY='before <img width="300" src="x.png"> after' run 1 "an <img> in the PR description needs the OK" "an image or attachment in the pull request description"
BODY='see the [notes](https://example.com/page) and ![]()' run 1 "even an empty markdown image needs the OK" "an image or attachment in the pull request description"
BODY='a [plain link](https://example.com/page) only' run 0 "a plain link in the PR description is fine"
# every entry, not only a MAC or VIN, also matches in letters and digits only (8 or more), after decoding %XX, +,
# HTML entities and \u escapes
for spelled in "Wexmoor%20Fixture%20Lane" "Wexmoor+Fixture+Lane" "wexmoor_fixture_lane" "WexmoorFixtureLane" \
    "Wexmoor  Fixture Lane" "Wexmoor&#32;Fixture&#x20;Lane" 'Wexmoor Fixture Lane' "$(printf 'Wexmoor\xc2\xa0Fixture Lane')" \
    "%57%65%78%6d%6f%6f%72%20%46%69%78%74%75%72%65%20%4c%61%6e%65"; do
  repo; g checkout -q -b feature; put f.txt "one\nat $spelled today\n" 2>/dev/null || { printf 'one\nat %s today\n' "$spelled" > "$T/r/f.txt"; g add f.txt; }
  printf 'one\nat %s today\n' "$spelled" > "$T/r/f.txt"; g add f.txt; commit "a place spelled another way"
  run 1 "a place entry matches spelled ${spelled:0:12}…" "FINDING $(c12): added line f.txt:2"
done
for spelled in "0x02, 0x5A, 0x1E, 0x7C, 0x3D, 0x9B" "9B:3D:7C:1E:5A:02" "{0x9b,0x3d,0x7c,0x1e,0x5a,0x02}"; do
  repo; g checkout -q -b feature; printf 'one\nconst mac = %s;\n' "$spelled" > "$T/r/f.txt"; g add f.txt; commit "a MAC in code"
  LIST=$(printf '%s\n%s\n' "$ID1" "$MAC") run 1 "a MAC entry matches written as ${spelled:0:10}…" "FINDING $(c12): added line f.txt:2"
done
repo; g checkout -q -b feature; printf '\x00\x9b\x3d\x7c\x1e\x5a\x02\x00' > "$T/r/adv.bin"; g add adv.bin; commit "a MAC's bytes, reversed"
LIST=$(printf '%s\n%s\n' "$ID1" "$MAC") run 1 "a MAC's six bytes in a binary file fail, in either order" "FINDING $(c12): binary content adv.bin"
repo; g checkout -q -b feature; put f.txt 'one\nWexmoor is a word, and Fixture Lane another, far apart: Wex moor\n'; commit "near but not the entry"
run 0 "letters-and-digits matching adds no match for words that are not the entry"
# a workflow that names the check could stand in for it: the reviewer's OK, and the merge step checks the source
repo; g checkout -q -b feature; put .github/workflows/x.yml 'on: pull_request\njobs:\n  privacy-scan:\n    if: false\n    runs-on: ubuntu-latest\n    steps: [{run: "true"}]\n'; commit "a stand-in"
run 1 "a workflow that names privacy-scan needs the OK" "FINDING $(c12): a workflow file that names the privacy-scan check .github/workflows/x.yml (needs a reviewer's OK"

# ── names with spaces, failing helpers, the list's reach ───────────────────────────────────────────────────────────────────────────
repo; g checkout -q -b feature; put "with space.txt" "one\n$ID1\n"; commit "a name with a space"
run 1 "a name with a space keeps its line number" "FINDING $(c12): added line with space.txt:2"
mkdir -p "$T/badpy"; printf '#!/usr/bin/env bash\nexit 1\n' > "$T/badpy/python3"; chmod +x "$T/badpy/python3"
repo; g checkout -q -b feature; commit "work"
p0=$PATH; PATH=$T/badpy:$PATH; scan; rc=$?; PATH=$p0
expect 2 "a failing helper exits 2 (fails closed), never 1 (a finding)" $rc "fails closed"
mkdir -p "$T/spygit"; REALGIT=$(command -v git)
printf '#!/usr/bin/env bash\n[ -z "${PRIVACY_IDENTIFIERS+x}" ] || echo seen >> "%s"\nexec "%s" "$@"\n' "$T/spy.log" "$REALGIT" > "$T/spygit/git"
chmod +x "$T/spygit/git"; rm -f "$T/spy.log"
repo; g checkout -q -b feature; put f.txt "one\n$ID2\n"; commit "work"
p0=$PATH; PATH=$T/spygit:$PATH; scan; rc=$?; PATH=$p0
expect 1 "git never runs with the list in its environment" $rc "added line f.txt:2"
if [ -e "$T/spy.log" ]; then fail=$((fail+1)); echo "FAIL git saw PRIVACY_IDENTIFIERS $(wc -l < "$T/spy.log") time(s)"
else pass=$((pass+1)); echo "PASS git saw PRIVACY_IDENTIFIERS 0 times"; fi

# ── merges ───────────────────────────────────────────────────────────────────────────────────────────────────────────
# merged <case>: the case's HEAD must be a real merge (two parents): a merge that fails quietly (on a runner with no
# git identity, say) would leave an ordinary commit and the merge case would test nothing
merged() { if g rev-parse -q --verify HEAD^2 > /dev/null; then pass=$((pass+1)); echo "PASS $1: HEAD is a merge"
           else fail=$((fail+1)); echo "FAIL $1: HEAD is not a merge (the merge failed)"; fi; }
merge_case() { # a feature branch and a side branch that both change f.txt line 2; the merge resolves it to <resolution>
  repo; g checkout -q -b feature; put f.txt 'one\nfeature\nthree\n'; commit "feature"
  g checkout -q -b side main; put f.txt 'one\nside\nthree\n'; commit "side"
  g checkout -q feature; GIT_COMMITTER_NAME=o GIT_COMMITTER_EMAIL=$OWNER GIT_AUTHOR_NAME=o GIT_AUTHOR_EMAIL=$OWNER g merge -q side > /dev/null 2>&1; put f.txt "$1"; commit "merge side"
  merged "merge_case"
}
merge_case 'one\nfeature and side\nthree\n'
run 0 "a merge whose resolution is clean passes"
merge_case "one\nfeature and $ID2\nthree\n"
run 1 "a line a merge's own resolution adds fails" "FINDING $(c12): added line f.txt:2"
repo; g checkout -q -b side; put g.txt "$ID1\n"; commit "an identifier on a side branch"; g checkout -q main
g checkout -q -b feature; commit "work"
GIT_AUTHOR_NAME=o GIT_AUTHOR_EMAIL=$OWNER GIT_COMMITTER_NAME=o GIT_COMMITTER_EMAIL=$OWNER g merge -q --no-ff -m "merge side" side
merged "a merged side branch"
run 1 "a merged branch's own new commit is scanned (the merge itself adds nothing)" "added line g.txt:1" "1 finding(s) in all"
# a resolution line taken from the second parent (on the base already) is not the merge's own line
repo; g checkout -q -b feature; put f.txt 'one\nfeat\nthree\n'; commit "feature"
g checkout -q main; put f.txt "one\n$ID1 on main\nthree\n"; commit "main already has it"; g checkout -q feature
GIT_COMMITTER_NAME=o GIT_COMMITTER_EMAIL=$OWNER GIT_AUTHOR_NAME=o GIT_AUTHOR_EMAIL=$OWNER g merge -q main > /dev/null 2>&1; put f.txt "one\nfeat\n$ID1 on main\nthree\n"; commit "merge main"
merged "a merge of main"
run 0 "a merge line that only the base side had (a ' +' or '+ ' line) is not the merge's own"

# ── fails closed ─────────────────────────────────────────────────────────────────────────────────────────────────────
repo; g checkout -q -b feature; commit "work"
LIST=""; run 2 "a missing or empty list fails closed" "missing or empty: fails closed"
LIST=$'# only a comment\n\n'; run 2 "a list of comments and blanks only fails closed" "has no entry"
LIST=$'QZXV-FIXTURE-7731\nab'; run 2 "an entry under 4 characters fails closed" "shorter than 4 characters"
LIST=$'QZXV-FIXTURE-7731\n\u00e4\u00f6\u00fc'; run 2 "the minimum counts characters, not bytes (3 non-ASCII letters fail)" "shorter than 4 characters"
unset LIST
GL=""; run 2 "no gitleaks fails closed" "does not name an executable gitleaks"
GL=$T/nowhere; run 2 "a gitleaks path that is not executable fails closed" "does not name an executable gitleaks"; unset GL
mkdir -p "$T/oldgit"; REALGIT=$(command -v git)
for v in "git version 2.39.5" "git version 1.9.1" "not a version"; do
  printf '#!/usr/bin/env bash\n[ "$1" = version ] && { echo "%s"; exit 0; }\nexec "%s" "$@"\n' "$v" "$REALGIT" > "$T/oldgit/git"
  chmod +x "$T/oldgit/git"
  scan_old() { local p=$PATH; PATH=$T/oldgit:$PATH; scan; local rc=$?; PATH=$p; return $rc; }
  scan_old; expect 2 "a git that reports \"$v\" fails closed (GIT_ATTR_SOURCE needs 2.40)" $? "fails closed"
done
EVENT=""; run 2 "no event file fails closed" "does not name a pull request event"
echo '{"push": {}}' > "$T/push.json"; EVENT=$T/push.json; run 2 "an event that is not a pull request fails closed" \
  "does not name a pull request event"; unset EVENT
out=$(bash "$SCAN" 2>&1); rc=$?
if [ "$rc" -eq 2 ]; then pass=$((pass+1)); echo "PASS no arguments is a usage error (exit 2)"; else fail=$((fail+1)); echo "FAIL no arguments: exit $rc"; fi
(cd "$T/r" && PRIVACY_IDENTIFIERS=$LISTV GITLEAKS=$STUB GITHUB_EVENT_PATH=$T/event.json bash "$SCAN" main "$(sha HEAD)") > "$OUT" 2>&1
expect 2 "a ref name is not taken for a SHA" $? "every argument a 40-hex SHA"

# ── gitleaks, stubbed: what it is given, and what of its report is printed ──────────────────────────────────────────
PLANTED=stub-planted-value-9f3e
repo; g checkout -q -b feature; put f.txt 'one\ntwo\nthree\nfour\n'; commit "work"; echo 'x' > "$T/r/.gitleaksignore"
run 0 "gitleaks with an empty report passes"; rm -f "$T/r/.gitleaksignore"
args=$(cat "$STUB_ARGS")
cfg=$(printf '%s\n' "$args" | awk 'p { print; exit } $0 == "--config" { p = 1 }')
ok=1
for a in git --redact --exit-code --report-format json --gitleaks-ignore-path --no-banner; do
  printf '%s\n' "$args" | grep -q -x -F -e "$a" || ok=0
done
printf '%s\n' "$args" | grep -q -x -F -e "--diff-merges=first-parent $(sha HEAD) --not $(sha main) $(sha staging) $(sha main)" || ok=0
case "$cfg" in "$T/r/"*|"") ok=0 ;; esac    # the config is the scan's own, never a file of the checkout
# the source gitleaks reads: a folder with no .gitleaksignore, never the working tree (which holds one in this case)
[ "$(sed -n 2p "$STUB_ARGS.src")" = no-ignore-file ] && [ "$(sed -n 1p "$STUB_ARGS.src")" != "$(cd "$T/r" && pwd -P)" ] || ok=0
[ "$(cat "$STUB_ARGS.attr")" = 4b825dc642cb6eb9a060e54bf8d69288fbee4904 ] || ok=0   # git under gitleaks reads no .gitattributes
if [ "$ok" = 1 ]; then pass=$((pass+1)); echo "PASS gitleaks gets the new commits only (merges against their first parent), redaction, its own config and ignore file, and no attributes"
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
# the second gitleaks run reads what the first cannot: messages, the PR's text, ZIP members and files read whole
repo; g checkout -q -b feature; mkzip a.zip doc.txt 'member text\n'; put img.bin '\x00\x01bin\x00'; commit "subject

body"
BODY="the description" ACTION=labeled LABEL=privacy-ok-compressed run 0 "gitleaks dir is given the messages, the PR text, ZIP members and files read whole"
dargs=$(cat "$STUB_ARGS.dir" 2>/dev/null); ok=1
for a in dir --redact --exit-code --report-format json --gitleaks-ignore-path --no-banner --ignore-gitleaks-allow --max-decode-depth --max-archive-depth; do
  printf '%s\n' "$dargs" | grep -q -x -F -e "$a" || ok=0
done
[ "$(sed -n 2p "$STUB_ARGS.dir.src" 2>/dev/null)" = no-ignore-file ] || ok=0
[ "$(wc -l < "$STUB_ARGS.dir.files" 2>/dev/null || echo 0)" -ge 4 ] || ok=0    # a message, the PR text, a member, a binary
grep -q -x -F -e --ignore-gitleaks-allow "$STUB_ARGS" || ok=0
grep -q -x -F -e --max-decode-depth "$STUB_ARGS" || ok=0
if [ "$ok" = 1 ]; then pass=$((pass+1)); echo "PASS gitleaks dir reads the scan's own folder of messages, PR text, members and binaries; both runs decode and ignore allow markers"
else fail=$((fail+1)); echo "FAIL gitleaks dir arguments or folder: $(printf '%s ' "$dargs" | tr '\n' ' ') files: $(cat "$STUB_ARGS.dir.files" 2>/dev/null | wc -l)"; fi
PLANTED=stub-planted-value-9f3e
f1=$(head -n1 "$STUB_ARGS.dir.files")
printf '[{"RuleID":"generic-api-key","File":"%s/%s","StartLine":3,"Secret":"%s"}]\n' "$(sed -n 1p "$STUB_ARGS.dir.src")" "$f1" "$PLANTED" > "$T/dir-report"
STUB_REPORT_DIR=$T/dir-report run 1 "a gitleaks dir finding is printed by rule and place, never its secret or the scan's file name" \
  "gitleaks rule generic-api-key" "gitleaks findings 1"
grep -q -F -e "$(sed -n 1p "$STUB_ARGS.dir.src")" -e "/$f1" "$OUT" && { fail=$((fail+1)); echo "FAIL the dir finding printed the scan's own file name"; } || { pass=$((pass+1)); echo "PASS the dir finding names its place, not the scan's file"; }
PLANTED=

# ── gitleaks, the real binary (the workflow runs this part) ─────────────────────────────────────────────────────────
if [ -n "${GITLEAKS_REAL:-}" ]; then
  # a fake access key in AWS's shape, made at run time so no file of this repository holds one
  PLANTED="AKIA$(LC_ALL=C tr -dc 'A-Z2-7' < /dev/urandom | head -c 16)"
  repo; g checkout -q -b feature; put conf.txt "one\nkey = $PLANTED\n"; commit "a seeded key"
  GL=$GITLEAKS_REAL run 1 "the real gitleaks finds the seeded key, printed by rule and file:line only" \
    "FINDING $(c12): gitleaks rule aws-access-token conf.txt:2"
  repo; g checkout -q -b feature; put f.txt 'one\ntwo\nthree\nfour\n'; commit "work"
  GL=$GITLEAKS_REAL run 0 "the real gitleaks passes a clean commit"
  repo; g checkout -q -b feature; put conf.txt "one\nkey = $PLANTED\n"; commit "a seeded key"
  printf '%s:conf.txt:aws-access-token:2\n' "$(sha HEAD)" > "$T/r/.gitleaksignore"    # in the working tree, as on a base
  GL=$GITLEAKS_REAL run 1 "the real gitleaks ignores a .gitleaksignore in the working tree" \
    "FINDING $(c12): gitleaks rule aws-access-token conf.txt:2"
  merge_case "one\nkey = $PLANTED\nthree\n"
  GL=$GITLEAKS_REAL run 1 "the real gitleaks finds a key added in a merge's own resolution" \
    "FINDING $(c12): gitleaks rule aws-access-token f.txt:2"
  repo; g checkout -q -b feature; mkzip k.zip doc.txt 'clean\n' conf.txt "key = $PLANTED\n"; commit "a key in a ZIP member"
  GL=$GITLEAKS_REAL run 1 "the real gitleaks finds a key inside a ZIP member" "FINDING $(c12): gitleaks rule aws-access-token in ZIP member #2 k.zip"
  repo; g checkout -q -b feature; commit "subject

key = $PLANTED"
  GL=$GITLEAKS_REAL run 1 "the real gitleaks finds a key in a commit message" "FINDING $(c12): gitleaks rule aws-access-token in the commit object:"
  repo; g checkout -q -b feature; commit "work"
  BODY=$(printf 'Summary\n\nkey = %s\n' "$PLANTED") GL=$GITLEAKS_REAL run 1 "the real gitleaks finds a key in the PR description" \
    "FINDING pull request: gitleaks rule aws-access-token in the pull request description:3"
  repo; g checkout -q -b feature; printf 'key = %s\n' "$PLANTED" | gzip -c > "$T/r/k.gz"; g add k.gz; commit "a key in a gzip"
  ACTION=labeled LABEL=privacy-ok-compressed GL=$GITLEAKS_REAL run 1 "the real gitleaks finds a key inside a gzip, OK label or not" \
    "FINDING $(c12): gitleaks rule aws-access-token in a file read whole k.gz"
  repo; g checkout -q -b feature; printf 'key = %s\n' "$PLANTED" > "$T/conf.txt"; tar -C "$T" -cf "$T/r/k.tar" conf.txt; g add k.tar; commit "a key in a tar"
  GL=$GITLEAKS_REAL run 1 "the real gitleaks finds a key inside a tar, which also needs the OK" \
    "gitleaks rule aws-access-token in a file read whole k.tar" "compressed file (tar) k.tar (needs a reviewer's OK"
  repo; g checkout -q -b feature; { printf '\xff\xfe'; u16 utf-16-le "key = $PLANTED"; } > "$T/r/k16.txt"; g add k16.txt; commit "a key in UTF-16"
  GL=$GITLEAKS_REAL run 1 "the real gitleaks finds a key in a UTF-16 file" "gitleaks rule aws-access-token in a file read whole k16.txt"
  repo; g checkout -q -b feature; put conf.txt "one\nkey = $PLANTED # $ALLOW\n"; commit "a key with the allow marker"
  GL=$GITLEAKS_REAL run 1 "the real gitleaks still reports a key that carries the allow marker" \
    "FINDING $(c12): gitleaks rule aws-access-token conf.txt:2" "added line with a gitleaks allow marker conf.txt:2"
  PLANTED=
else
  echo "SKIP the real gitleaks cases (GITLEAKS_REAL is not set; the workflow sets it)"
fi

echo "privacy scan tests: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
