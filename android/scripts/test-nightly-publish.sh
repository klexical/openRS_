#!/usr/bin/env bash
# test-nightly-publish.sh — checks nightly-publish.sh and nightly-cleanup.sh, the publishing half of the nightly (#363):
#   - publish stops quietly, creating nothing, when today's tag is already published and checks out as step 5 checks it (a
#     pre-release, its tag at the recorded commit, not Latest, one asset with the digest); a draft for today's tag, a
#     published release that does not check out, or a tag ref with no release fails and deletes nothing; it fails closed
#     on a release list that is not a JSON list and on a tag lookup that fails with anything but 404;
#   - otherwise: notes from the generate-notes API from the gate's base tag (the list, then the SHA-256 line, then the
#     changelog link; on the first nightly the rc.8 line), a draft pre-release at the recorded commit with Latest off, the
#     one upload, exactly one asset of that name whose bytes match the digest, then published as a pre-release with Latest
#     off, its tag at the recorded commit and not the release marked Latest;
#   - clean-up deletes a draft only when it is this run attempt's: at the recorded commit, its notes holding the line that
#     names this run and attempt (GITHUB_RUN_ID, GITHUB_RUN_ATTEMPT); any tag ref must point at the recorded commit before
#     anything is deleted; then the ref is confirmed gone (only a 404 counts as gone); it never deletes a published release;
#   - the published notes do not carry that line: only the draft does.
# A fake gh keeps the releases and tag refs in a JSON state file, records every call, and fails on request. Names, dates
# and commits are invented test values. Run from anywhere: bash android/scripts/test-nightly-publish.sh
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
PUBLISH="$DIR/nightly-publish.sh"; CLEANUP="$DIR/nightly-cleanup.sh"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/nightly-publish-test.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
failures=0; ran=0
REPO=owner/repo
TAG=android-v2.2.7-nightly.20261110; TITLE="openRS_ v2.2.7-nightly.20261110"
SHA=1111111111111111111111111111111111111111; OTHER=2222222222222222222222222222222222222222
RC8=android-v2.2.7-rc.8; PREV=android-v2.2.7-nightly.20261108
RUN_ID=8642; RUN_ATT=1           # this run attempt (GITHUB_RUN_ID, GITHUB_RUN_ATTEMPT), set on every call: the runner's own never leak in
mark() { echo "<!-- nightly draft of run ${1:-$RUN_ID} attempt ${2:-$RUN_ATT} at ${3:-$SHA} -->"; }
mkdir -p "$WORK/apk"; FILE="$WORK/apk/openRS_v2.2.7-nightly.20261110.apk"; printf 'apk bytes\n' > "$FILE"
DIGEST=$( (sha256sum "$FILE" 2>/dev/null || shasum -a 256 "$FILE") | cut -d' ' -f1)

# ── Fake gh (Python, standard library): the state is $WORK/state.json; each call is one JSON line in $WORK/calls ──────
cat > "$WORK/fakegh.py" <<'PY'
import json, os, shutil, sys
W = os.environ["WORK"]; ST = os.path.join(W, "state.json"); a = sys.argv[1:]
with open(os.path.join(W, "calls"), "a") as f: f.write(json.dumps(a) + "\n")
s = json.load(open(ST))
def save(): json.dump(s, open(ST, "w"))
def fail(msg, code=1): sys.stderr.write(msg + "\n"); sys.exit(code)
def inject(key):
    if os.environ.get("FAKE_FAIL") == key: fail("HTTP 502: Bad Gateway (fake, at %s)" % key)
def opt(name):
    return a[a.index(name) + 1] if name in a else None
def rel(tag):
    return next((r for r in s["releases"] if r["tag"] == tag), None)
def not_found(): fail("gh: Not Found (HTTP 404)")
def jq_out(obj):
    q = opt("--jq")
    if q == ".object.sha": print(obj["object"]["sha"])
    elif q == ".body": print(obj["body"])
    elif q == ".tag_name": print(obj["tag_name"])
    else: print(json.dumps(obj))
if a[:2] == ["release", "list"]:
    inject("list")
    if "FAKE_LIST_RAW" in os.environ: print(os.environ["FAKE_LIST_RAW"]); sys.exit(0)
    print(json.dumps([{"tagName": r["tag"], "isDraft": r["draft"], "isPrerelease": r["prerelease"]} for r in s["releases"]]))
elif a[0] == "api":
    method = opt("--method") or "GET"
    path = next(x for x in a[1:] if x.startswith("repos/"))
    if path.startswith("repos/owner/repo/git/ref/tags/") and method == "GET":
        if os.environ.get("FAKE_TAGREF_502") == "1": fail("HTTP 502: Bad Gateway (fake, at tagref)")
        t = path.rsplit("/tags/", 1)[1]
        if t not in s["tags"]: not_found()
        jq_out({"ref": "refs/tags/" + t, "object": {"sha": s["tags"][t], "type": "commit"}})
    elif path.startswith("repos/owner/repo/git/refs/tags/") and method == "DELETE":
        inject("deleteref")
        t = path.rsplit("/tags/", 1)[1]
        if t not in s["tags"]: fail("gh: Reference does not exist (HTTP 422)")
        if os.environ.get("FAKE_DELETE_REF_NOOP") != "1": del s["tags"][t]; save()
    elif path == "repos/owner/repo/releases/generate-notes" and method == "POST":
        inject("notes")
        f = dict(x.split("=", 1) for x in [a[i + 1] for i, y in enumerate(a) if y == "-f"])
        open(os.path.join(W, "notes-args"), "w").write(json.dumps(f, sort_keys=True))
        jq_out({"name": f["tag_name"], "body": "## What's Changed\n* A merged pull request by @someone in #1\n\n**Full Changelog**: " + f.get("previous_tag_name", "") + "..." + f["tag_name"]})
    elif path == "repos/owner/repo/releases/latest" and method == "GET":
        inject("latest")
        if s.get("latest") is None: not_found()
        jq_out({"tag_name": s["latest"]})
    else: fail("fake gh: unexpected api " + " ".join(a), 2)
elif a[:2] == ["release", "create"]:
    inject("create")
    t = a[2]
    if rel(t): fail("a release with that tag exists", 1)
    r = {"tag": t, "draft": "--draft" in a, "prerelease": "--prerelease" in a, "latest_flag": "--latest=false" in a,
         "target": opt("--target"), "title": opt("--title"), "assets": [], "body": open(opt("--notes-file")).read()}
    shutil.copy(opt("--notes-file"), os.path.join(W, "notes-posted"))
    s["releases"].append(r)
    if os.environ.get("FAKE_TAG_ON_DRAFT") == "1": s["tags"][t] = r["target"]
    save()
elif a[:2] == ["release", "upload"]:
    inject("upload")
    r = rel(a[2]) or not_found()
    src = a[3]; name = os.path.basename(src)
    shutil.copy(src, os.path.join(W, "asset-" + name)); r["assets"].append({"name": name})
    if os.environ.get("FAKE_EXTRA_ASSET") == "1": r["assets"].append({"name": "extra.txt"})
    save()
elif a[:2] == ["release", "view"]:
    inject("view")
    r = rel(a[2]) or fail("release not found")
    print(json.dumps({"isDraft": r["draft"], "isPrerelease": r["prerelease"], "assets": r["assets"],
                      "targetCommitish": r["target"], "body": r.get("body", "")}))
elif a[:2] == ["release", "download"]:
    inject("download")
    r = rel(a[2]) or fail("release not found")
    name = opt("--pattern"); d = opt("--dir"); os.makedirs(d, exist_ok=True)
    if not any(x["name"] == name for x in r["assets"]): fail("no assets match the file pattern")
    shutil.copy(os.path.join(W, "asset-" + name), os.path.join(d, name))
    if os.environ.get("FAKE_CORRUPT") == "1": open(os.path.join(d, name), "a").write("x")
elif a[:2] == ["release", "edit"]:
    inject("edit")
    r = rel(a[2]) or fail("release not found")
    if "--draft=false" in a:
        r["draft"] = False
        s["tags"][r["tag"]] = os.environ.get("FAKE_EDIT_TAG_SHA") or r["target"]
    if "--notes-file" in a:
        r["body"] = open(opt("--notes-file")).read(); shutil.copy(opt("--notes-file"), os.path.join(W, "notes-edited"))
    r["prerelease"] = "--prerelease" in a or r["prerelease"]
    r["latest_flag"] = "--latest=false" in a
    if os.environ.get("FAKE_LATEST_SELF") == "1": s["latest"] = r["tag"]
    save()
elif a[:2] == ["release", "delete"]:
    inject("delete")
    r = rel(a[2]) or fail("release not found")
    s["releases"].remove(r)
    if "--cleanup-tag" in a and os.environ.get("FAKE_DELETE_KEEPS_TAG") != "1": s["tags"].pop(r["tag"], None)
    save()
else:
    fail("fake gh: unexpected " + " ".join(a), 2)
PY
mkdir -p "$WORK/bin"
printf '#!/usr/bin/env bash\nexec python3 -I "$WORK/fakegh.py" "$@"\n' > "$WORK/bin/gh"; chmod +x "$WORK/bin/gh"

# state '<releases JSON list>' '<tags JSON object>' [latest tag]: the fake's starting state
state() {
  python3 -I -c 'import json,sys; json.dump({"releases": json.loads(sys.argv[1]), "tags": json.loads(sys.argv[2]), "latest": sys.argv[3] or None}, open(sys.argv[4], "w"))' \
    "$1" "$2" "${3:-android-v2.2.6}" "$WORK/state.json"
}
rel_json() {   # rel_json <tag> <draft true|false> <prerelease true|false> [target] [body]
  python3 -I -c 'import json,sys; print(json.dumps({"tag": sys.argv[1], "draft": sys.argv[2] == "true", "prerelease": sys.argv[3] == "true",
    "latest_flag": True, "target": sys.argv[4], "title": "t", "assets": [], "body": sys.argv[5]}))' "$1" "$2" "$3" "${4:-$SHA}" "${5:-notes}"
}
q() { python3 -I -c 'import json,sys; s=json.load(open(sys.argv[1])); print(eval(sys.argv[2], {"s": s}))' "$WORK/state.json" "$1"; }
verbs() {   # the calls, one "verb" per call: "release create", "api GET …/git/ref/tags/…", and so on
  python3 -I - "$WORK/calls" <<'PY'
import json, sys
out = []
for l in open(sys.argv[1]) if __import__("os").path.exists(sys.argv[1]) else []:
    a = json.loads(l)
    if a[0] == "api":
        m = a[a.index("--method") + 1] if "--method" in a else "GET"
        p = next(x for x in a[1:] if x.startswith("repos/")).replace("repos/owner/repo/", "")
        p = p.split("/tags/")[0] + "/tags/*" if "/tags/" in p else p
        out.append("api %s %s" % (m, p))
    else:
        out.append(" ".join(a[:2]))
print("|".join(out))
PY
}
called() { grep -F -- "$1" "$WORK/calls" >/dev/null 2>&1; }

# run_publish [base tag] [env...] / run_cleanup [env...]: OUT, CODE
run_publish() {
  local base="${1:-$PREV}"; shift || true
  rm -f "$WORK/calls" "$WORK/notes-posted" "$WORK/notes-args"; : > "$WORK/summary"
  OUT=$(env PATH="$WORK/bin:$PATH" WORK="$WORK" GITHUB_REPOSITORY=$REPO GITHUB_STEP_SUMMARY="$WORK/summary" \
        GITHUB_RUN_ID=$RUN_ID GITHUB_RUN_ATTEMPT=$RUN_ATT "$@" \
        bash "$PUBLISH" "$TAG" "$TITLE" "$SHA" "$base" "$FILE" "$DIGEST" 2>&1); CODE=$?
}
run_cleanup() {
  rm -f "$WORK/calls"; : > "$WORK/summary"
  OUT=$(env PATH="$WORK/bin:$PATH" WORK="$WORK" GITHUB_REPOSITORY=$REPO GITHUB_STEP_SUMMARY="$WORK/summary" CLEANUP_SLEEP=0 \
        GITHUB_RUN_ID=$RUN_ID GITHUB_RUN_ATTEMPT=$RUN_ATT "$@" \
        bash "$CLEANUP" "$TAG" "$SHA" 2>&1); CODE=$?
}
check() {
  ran=$((ran + 1))
  if [ -z "$WHY" ]; then echo "PASS $1"; else echo "FAIL $1 — $WHY"; echo "$OUT" | sed 's/^/    /'; failures=$((failures + 1)); fi
}
why() { WHY="${WHY:+$WHY; }$*"; }
nothing_written() {   # no create, upload, edit or delete of any kind
  local v; v=$(verbs)
  case "|$v|" in *"release create"*|*"release upload"*|*"release edit"*|*"release delete"*|*"api DELETE"*|*"api POST"*) why "write calls made: $v";; esac
}
fail_closed() {   # fail_closed <message>
  [ "$CODE" -ne 0 ] || why "exit 0"
  [[ "$OUT" == *"::error::"* && "$OUT" == *"$1"* ]] || why "output lacks '::error::' and '$1'"
}
EXISTING="$(rel_json android-v2.2.7-nightly.20261108 false true),$(rel_json $RC8 false true),$(rel_json android-v2.2.6 false false)"

# ── nightly-publish.sh ──────────────────────────────────────────────────────────────────────────────────────────────
WHY=""; state "[$EXISTING]" '{}'; run_publish
[ "$CODE" -eq 0 ] || why "exit $CODE"
want="release list|api GET git/ref/tags/*|api POST releases/generate-notes|release create|api GET git/ref/tags/*|release upload|release view|release download|release edit|release view|release download|api GET git/ref/tags/*|api GET releases/latest"
[ "$(verbs)" = "$want" ] || why "calls '$(verbs)', expected '$want'"
[ "$(q "[r['draft'] for r in s['releases'] if r['tag']=='$TAG']")" = "[False]" ] || why "release state $(q "[r for r in s['releases'] if r['tag']=='$TAG']")"
[ "$(q "[(r['prerelease'], r['latest_flag'], r['target'], r['title']) for r in s['releases'] if r['tag']=='$TAG']")" = "[(True, True, '$SHA', '$TITLE')]" ] \
  || why "release flags $(q "[r for r in s['releases'] if r['tag']=='$TAG']")"
[ "$(q "[x['name'] for r in s['releases'] if r['tag']=='$TAG' for x in r['assets']]")" = "['openRS_v2.2.7-nightly.20261110.apk']" ] || why "assets wrong"
[ "$(q "s['tags'].get('$TAG')")" = "$SHA" ] || why "tag at $(q "s['tags'].get('$TAG')")"
check "publish: notes, a draft pre-release at the commit with Latest off, one upload, the asset checked, then published"

WHY=""
c=$(grep -F '"create"' "$WORK/calls")
for f in '"--draft"' '"--prerelease"' '"--latest=false"' "\"--target\", \"$SHA\"" "\"--title\", \"$TITLE\"" '"--notes-file"'; do
  [[ "$c" == *"$f"* ]] || why "release create lacks $f"
done
e=$(grep -F '"edit"' "$WORK/calls")
for f in '"--draft=false"' '"--prerelease"' '"--latest=false"'; do [[ "$e" == *"$f"* ]] || why "release edit lacks $f"; done
check "publish: the create and the edit pass the draft, pre-release and Latest flags explicitly"

WHY=""
[ "$(cat "$WORK/notes-args" 2>/dev/null)" = "{\"previous_tag_name\": \"$PREV\", \"tag_name\": \"$TAG\", \"target_commitish\": \"$SHA\"}" ] \
  || why "generate-notes got $(cat "$WORK/notes-args" 2>/dev/null)"
notes=$(cat "$WORK/notes-posted" 2>/dev/null)
i_list=$(grep -n "A merged pull request" <<<"$notes" | cut -d: -f1)
i_sha=$(grep -n -F "$DIGEST" <<<"$notes" | cut -d: -f1)
i_log=$(grep -n -F "https://github.com/$REPO/blob/$SHA/android/CHANGELOG.md" <<<"$notes" | cut -d: -f1)
[ -n "$i_list" ] && [ -n "$i_sha" ] && [ -n "$i_log" ] && [ "$i_list" -lt "$i_sha" ] && [ "$i_sha" -lt "$i_log" ] \
  || why "notes not in the order list, SHA-256, changelog: list $i_list, sha $i_sha, changelog $i_log"
[[ "$notes" != *"Update to rc.8 first"* ]] || why "the rc.8 line appears on a later nightly"
check "publish: notes from the base tag, in the order list, SHA-256 line, changelog link"

WHY=""
grep -qxF -- "$(mark)" "$WORK/notes-posted" 2>/dev/null || why "the draft's notes lack the line naming this run attempt: $(mark)"
[ -f "$WORK/notes-edited" ] || why "publishing did not set the notes again"
grep -qF -- "nightly draft of run" "$WORK/notes-edited" 2>/dev/null && why "the published notes still carry the draft's run line"
[ "$(grep -vxF -- "$(mark)" "$WORK/notes-posted" 2>/dev/null)" = "$(cat "$WORK/notes-edited" 2>/dev/null)" ] || why "the published notes are not the draft's without the run line"
check "publish: the draft's notes name this run attempt on a line of their own; the published notes do not"

WHY=""; state "[$(rel_json $RC8 false true)]" '{}'; run_publish "$RC8"
[ "$CODE" -eq 0 ] || why "exit $CODE"
[[ "$(cat "$WORK/notes-args" 2>/dev/null)" == *"\"previous_tag_name\": \"$RC8\""* ]] || why "generate-notes got $(cat "$WORK/notes-args" 2>/dev/null)"
grep -q -F 'On rc.2–rc.7? Update to rc.8 first to keep receiving builds.' "$WORK/notes-posted" 2>/dev/null || why "the first nightly's notes lack the rc.8 line"
check "publish: the first nightly's notes start from rc.8 and carry the rc.8 line"

# F2 (QA's FINAL on r4): a release for today's tag in step 1 is checked as step 5 checks it; a draft fails
published() {   # published <draft true|false> <prerelease true|false> [assets JSON]: today's tag, at SHA, with the APK
  local assets="${3:-}"
  cp "$FILE" "$WORK/asset-$(basename "$FILE")"
  [ -n "$assets" ] || assets=$(printf '[{"name": "%s"}]' "$(basename "$FILE")")
  python3 -I -c 'import json,sys; print(json.dumps({"tag": sys.argv[1], "draft": sys.argv[2] == "true", "prerelease": sys.argv[3] == "true",
    "latest_flag": True, "target": sys.argv[4], "title": "t", "assets": json.loads(sys.argv[5]), "body": "notes"}))' \
    "$TAG" "$1" "$2" "$SHA" "$assets"
}
WHY=""; state "[$EXISTING,$(published false true)]" "{\"$TAG\": \"$SHA\"}"; run_publish
[ "$CODE" -eq 0 ] || why "exit $CODE"
[[ "$OUT" == *"already published"* ]] || why "the log does not say why it stopped"
nothing_written
check "publish: today's tag already published and checking out as step 5 checks it: a quiet stop, nothing created or deleted"

for c in "a draft:true:true:$SHA:" "a published release that is not a pre-release:false:false:$SHA:" \
         "a pre-release whose tag is at another commit:false:true:$OTHER:" "a pre-release marked Latest:false:true:$SHA:latest" \
         "a pre-release with two assets:false:true:$SHA:two" "a pre-release whose asset's bytes differ:false:true:$SHA:corrupt"; do
  IFS=: read -r label d p tagsha extra <<<"$c"
  assets=""; [ "$extra" = two ] && assets="[{\"name\": \"$(basename "$FILE")\"}, {\"name\": \"extra.txt\"}]"
  WHY=""; state "[$EXISTING,$(published "$d" "$p" "$assets")]" "{\"$TAG\": \"$tagsha\"}" "$([ "$extra" = latest ] && echo "$TAG" || echo android-v2.2.6)"
  if [ "$extra" = corrupt ]; then run_publish "$PREV" FAKE_CORRUPT=1; else run_publish; fi
  [ "$CODE" -ne 0 ] || why "exit 0: a re-run would turn green over it"
  [[ "$OUT" == *"::error::"* ]] || why "no ::error:: line"
  [ "$d" = false ] || [[ "$OUT" == *"already has a draft"* ]] || why "the draft case does not say it found a draft"
  nothing_written
  [ "$(q "[r['tag'] for r in s['releases'] if r['tag']=='$TAG']")" = "['$TAG']" ] || why "the release was deleted"
  check "publish: today's tag already has $label: the step fails and deletes nothing"
done

# A tag with no release in step 1 is a leftover or made by hand (the gate skips a day with a tag): the step fails, so the
# clean-up runs; it deletes the tag only at the recorded commit, and the next "Re-run failed jobs" (attempt 2) publishes
WHY=""; state "[$EXISTING]" "{\"$TAG\": \"$SHA\"}"; run_publish
fail_closed "with no release"; nothing_written
[ "$(q "s['tags'].get('$TAG')")" = "$SHA" ] || why "the tag is now at $(q "s['tags'].get('$TAG')")"
check "publish: today's tag exists at the commit without a release: the step fails and deletes nothing"
WHY=""; run_cleanup
[ "$CODE" -eq 0 ] || why "clean-up exit $CODE"
[ "$(q "s['tags'].get('$TAG')")" = "None" ] || why "the clean-up left the tag at $(q "s['tags'].get('$TAG')")"
run_publish "$PREV" GITHUB_RUN_ATTEMPT=2
[ "$CODE" -eq 0 ] || why "the re-run's publish exit $CODE"
[ "$(q "[(r['draft'], r['prerelease'], r['target']) for r in s['releases'] if r['tag']=='$TAG']")" = "[(False, True, '$SHA')]" ] \
  || why "after the re-run: $(q "[r for r in s['releases'] if r['tag']=='$TAG']")"
check "publish then clean-up: the tag at the commit is deleted, and the next Re-run failed jobs publishes"

WHY=""; state "[$EXISTING]" "{\"$TAG\": \"$OTHER\"}"; run_publish
fail_closed "with no release"; nothing_written
run_cleanup
fail_closed "another commit"; nothing_written
[ "$(q "s['tags'].get('$TAG')")" = "$OTHER" ] || why "the tag at another commit was touched"
check "publish and clean-up: today's tag at another commit without a release: both fail, nothing is deleted"

for raw in "" "not json" '{"message":"Bad credentials"}'; do
  WHY=""; state "[$EXISTING]" '{}'; run_publish "$PREV" FAKE_LIST_RAW="$raw"
  fail_closed "not a JSON list"; nothing_written
  check "publish: a release list that is not a JSON list ('${raw:-empty}') fails closed"
done

WHY=""; state "[$EXISTING]" '{}'; run_publish "$PREV" FAKE_FAIL=list
fail_closed "release list"; nothing_written
check "publish: a failed release list fails closed"

WHY=""; state "[$EXISTING]" '{}'; run_publish "$PREV" FAKE_TAGREF_502=1
fail_closed "tag"; nothing_written
check "publish: a tag lookup failing with anything but 404 fails closed"

WHY=""; state "[$EXISTING]" '{}'; run_publish "$PREV" FAKE_FAIL=upload
fail_closed "upload"
called '"edit"' && why "the release was edited (published) after a failed upload"
[ "$(q "[r['draft'] for r in s['releases'] if r['tag']=='$TAG']")" = "[True]" ] || why "the release is not a draft"
check "publish: a failed upload fails before publishing; the draft stays for the clean-up"

WHY=""; state "[$EXISTING]" '{}'; run_publish "$PREV" FAKE_EXTRA_ASSET=1
fail_closed "one asset"; called '"edit"' && why "published with two assets"
check "publish: two assets on the draft fail the one-asset check before publishing"

WHY=""; state "[$EXISTING]" '{}'; run_publish "$PREV" FAKE_CORRUPT=1
fail_closed "digest"; called '"edit"' && why "published with a different file"
check "publish: an uploaded file whose bytes differ from the digest fails before publishing"

WHY=""; state "[$EXISTING]" '{}'; run_publish "$PREV" FAKE_FAIL=edit
fail_closed "publish"
[ "$(q "[r['draft'] for r in s['releases'] if r['tag']=='$TAG']")" = "[True]" ] || why "the release is not a draft"
check "publish: a failed publish leaves a draft for the clean-up"

WHY=""; state "[$EXISTING]" '{}'; run_publish "$PREV" FAKE_EDIT_TAG_SHA=$OTHER
fail_closed "tag"; called '"delete"' && why "a published release was deleted"
check "publish: a published tag at another commit fails the run and deletes nothing"

WHY=""; state "[$EXISTING]" '{}'; run_publish "$PREV" FAKE_LATEST_SELF=1
fail_closed "Latest"; called '"delete"' && why "a published release was deleted"
check "publish: a nightly marked Latest fails the run and deletes nothing"

# refused <script> <case> <args…>: exits non-zero with ::error:: and calls no gh
refused() {
  local script="$1" c="$2" o code; shift 2
  rm -f "$WORK/calls"
  o=$(env PATH="$WORK/bin:$PATH" WORK="$WORK" GITHUB_REPOSITORY=$REPO GITHUB_RUN_ID=$RUN_ID GITHUB_RUN_ATTEMPT=$RUN_ATT \
      bash "$script" "$@" 2>&1); code=$?
  [ "$code" -ne 0 ] && [[ "$o" == *"::error::"* ]] || why "accepted: $c"
  [ -s "$WORK/calls" ] && why "gh was called for: $c"
  OUT="$OUT$c: $o"$'\n'
}
WHY=""; state "[$EXISTING]" '{}'; OUT=""
refused "$PUBLISH" "an RC tag" "$RC8" "$TITLE" "$SHA" "$PREV" "$FILE" "$DIGEST"
refused "$PUBLISH" "a stable tag" android-v2.2.7 "$TITLE" "$SHA" "$PREV" "$FILE" "$DIGEST"
refused "$PUBLISH" "a base that is not a release tag" "$TAG" "$TITLE" "$SHA" "main" "$FILE" "$DIGEST"
refused "$PUBLISH" "a short commit" "$TAG" "$TITLE" abc "$PREV" "$FILE" "$DIGEST"
refused "$PUBLISH" "a short digest" "$TAG" "$TITLE" "$SHA" "$PREV" "$FILE" abc
refused "$PUBLISH" "a missing file" "$TAG" "$TITLE" "$SHA" "$PREV" "$WORK/missing.apk" "$DIGEST"
refused "$PUBLISH" "a file that does not match the digest" "$TAG" "$TITLE" "$SHA" "$PREV" "$FILE" "${DIGEST%?}0"
refused "$PUBLISH" "five arguments" "$TAG" "$TITLE" "$SHA" "$PREV" "$FILE"
check "publish: an RC or stable tag, a bad base, commit or digest, a missing or different file is refused before any call"

for unset in GITHUB_RUN_ID GITHUB_RUN_ATTEMPT; do   # the draft's run line needs both: refused before any gh call
  WHY=""; state "[$EXISTING]" '{}'; rm -f "$WORK/calls"
  OUT=$(env PATH="$WORK/bin:$PATH" WORK="$WORK" GITHUB_REPOSITORY=$REPO GITHUB_RUN_ID=$RUN_ID GITHUB_RUN_ATTEMPT=$RUN_ATT \
        bash -c 'unset '"$unset"'; exec bash "$0" "$@"' "$PUBLISH" "$TAG" "$TITLE" "$SHA" "$PREV" "$FILE" "$DIGEST" 2>&1); CODE=$?
  fail_closed "$unset"
  [ -s "$WORK/calls" ] && why "gh was called without $unset"
  check "publish: without $unset it is refused before any call"
done

# ── nightly-cleanup.sh ──────────────────────────────────────────────────────────────────────────────────────────────
WHY=""; state "[$EXISTING,$(rel_json "$TAG" true true "$SHA" "$(printf 'notes\n%s' "$(mark)")")]" "{\"$TAG\": \"$SHA\"}"; run_cleanup
[ "$CODE" -eq 0 ] || why "exit $CODE"
grep -F '"delete"' "$WORK/calls" | grep -q -F '"--yes"' || why "no release delete --yes"
grep -F '"delete"' "$WORK/calls" | grep -q -F -- '--cleanup-tag' && why "the release delete still takes the tag with it (--cleanup-tag): the tag goes through the commit check"
[ "$(q "[r['tag'] for r in s['releases'] if r['tag']=='$TAG']")" = "[]" ] || why "the draft remains"
[ "$(q "s['tags'].get('$TAG')")" = "None" ] || why "the tag remains"
[ "$(verbs | tr '|' '\n' | tail -1)" = "api GET git/ref/tags/*" ] || why "the tag ref is not read last: $(verbs)"
[ "$(q "len(s['releases'])")" = "3" ] || why "other releases were touched"
check "clean-up: this run attempt's draft is deleted, its tag at the recorded commit too, and the ref is confirmed gone"

WHY=""; state "[$EXISTING,$(rel_json "$TAG" true true "$SHA" "$(mark)")]" '{}'; run_cleanup
[ "$CODE" -eq 0 ] || why "exit $CODE"
[ "$(q "[r['tag'] for r in s['releases'] if r['tag']=='$TAG']")" = "[]" ] || why "the draft remains"
check "clean-up: this run attempt's draft whose tag GitHub has not created yet is deleted"

WHY=""; state "[$EXISTING,$(rel_json "$TAG" true true "$SHA" "$(mark)")]" "{\"$TAG\": \"$SHA\"}"; run_cleanup FAKE_DELETE_KEEPS_TAG=1
[ "$CODE" -eq 0 ] || why "exit $CODE"
called '"DELETE"' || why "the leftover tag ref at the commit was not deleted"
[ "$(q "s['tags'].get('$TAG')")" = "None" ] || why "the tag remains"
check "clean-up: a tag left behind by the delete, at the recorded commit, is deleted and confirmed gone"

for st in "pre-release:true" "published release:false"; do
  IFS=: read -r label p <<<"$st"
  WHY=""; state "[$EXISTING,$(rel_json "$TAG" false "$p")]" "{\"$TAG\": \"$SHA\"}"; run_cleanup
  fail_closed "not a draft"
  nothing_written
  [ "$(q "[r['tag'] for r in s['releases'] if r['tag']=='$TAG']")" = "['$TAG']" ] || why "the release was deleted"
  [ "$(q "s['tags'].get('$TAG')")" = "$SHA" ] || why "the tag was deleted"
  check "clean-up: a $label for the tag (publish failed after publishing) is never deleted, nor its tag"
done

WHY=""; state "[$EXISTING]" '{}'; run_cleanup
[ "$CODE" -eq 0 ] || why "exit $CODE"; nothing_written
[ "$(q "len(s['releases'])")" = "3" ] || why "releases were touched"
check "clean-up: no release and no tag: nothing to delete"

WHY=""; state "[$EXISTING]" "{\"$TAG\": \"$SHA\"}"; run_cleanup
[ "$CODE" -eq 0 ] || why "exit $CODE"
called '"DELETE"' || why "the tag ref was not deleted"
[ "$(q "s['tags'].get('$TAG')")" = "None" ] || why "the tag remains"
check "clean-up: a tag at the recorded commit with no release is deleted and confirmed gone"

WHY=""; state "[$EXISTING]" "{\"$TAG\": \"$OTHER\"}"; run_cleanup
fail_closed "another commit"; nothing_written
[ "$(q "s['tags'].get('$TAG')")" = "$OTHER" ] || why "the tag was deleted"
check "clean-up: a tag at another commit is not this run's and is never deleted"

WHY=""; state "[$EXISTING,$(rel_json android-v2.2.7-nightly.20261109 true true)]" '{}'; run_cleanup
[ "$CODE" -eq 0 ] || why "exit $CODE"; nothing_written
[ "$(q "len(s['releases'])")" = "4" ] || why "another date's draft was touched"
check "clean-up: another tag's draft is never touched"

WHY=""; state "[$EXISTING,$(rel_json "$TAG" true true)]" "{\"$TAG\": \"$SHA\"}"; run_cleanup FAKE_FAIL=list
fail_closed "release list"; nothing_written
check "clean-up: a failed release list fails without deleting"

WHY=""; state "[$EXISTING,$(rel_json "$TAG" true true)]" "{\"$TAG\": \"$SHA\"}"; run_cleanup FAKE_LIST_RAW="not json"
fail_closed "not a JSON list"; nothing_written
check "clean-up: a release list that is not a JSON list fails without deleting"

WHY=""; state "[$EXISTING]" "{\"$TAG\": \"$SHA\"}"; run_cleanup FAKE_TAGREF_502=1
fail_closed "tag"; nothing_written
check "clean-up: a tag lookup failing with anything but 404 fails without deleting"

WHY=""; state "[$EXISTING,$(rel_json "$TAG" true true "$SHA" "$(mark)")]" "{\"$TAG\": \"$SHA\"}"; run_cleanup FAKE_DELETE_KEEPS_TAG=1 FAKE_FAIL=deleteref
fail_closed "still exists"
check "clean-up: a tag that cannot be removed fails the clean-up"

WHY=""; state "[$EXISTING]" "{\"$TAG\": \"$SHA\"}"; run_cleanup FAKE_DELETE_REF_NOOP=1
fail_closed "still exists"
check "clean-up: a tag still there after a delete that reported success fails the clean-up (the 404 is required)"

# F1 (QA's FINAL on r4): only this run attempt's draft is deleted; anything else fails and deletes nothing
for c in "an earlier run:$(mark 7531 1)" "an earlier attempt of this run:$(mark "$RUN_ID" 2)" "attempt 12 (a near miss for 1):$(mark "$RUN_ID" 12)" \
         "another commit in its line:$(mark "$RUN_ID" "$RUN_ATT" "$OTHER")" "no run line:notes" \
         "the run line inside a longer line (a pull request's title):* Fix $(mark) by @someone in #1"; do
  label=${c%%:*}; body=${c#*:}
  WHY=""; state "[$EXISTING,$(rel_json "$TAG" true true "$SHA" "$body")]" "{\"$TAG\": \"$SHA\"}"; run_cleanup
  fail_closed "not this run attempt's"; nothing_written
  [ "$(q "[r['tag'] for r in s['releases'] if r['tag']=='$TAG']")" = "['$TAG']" ] || why "the draft was deleted"
  [ "$(q "s['tags'].get('$TAG')")" = "$SHA" ] || why "the tag was deleted"
  check "clean-up: a draft for today's tag from $label is never deleted, nor its tag"
done

WHY=""; state "[$EXISTING,$(rel_json "$TAG" true true "$SHA" $'* A merged pull request\r\n\r\n'"$(mark)"$'\r\n')]" "{\"$TAG\": \"$SHA\"}"; run_cleanup
[ "$CODE" -eq 0 ] || why "exit $CODE"
[ "$(q "[r['tag'] for r in s['releases'] if r['tag']=='$TAG']")" = "[]" ] || why "this attempt's draft remains"
check "clean-up: this attempt's draft whose notes come back with CRLF line endings is deleted"

WHY=""; state "[$EXISTING,$(rel_json "$TAG" true true "$OTHER" "$(mark)")]" '{}'; run_cleanup
fail_closed "not this run attempt's"; nothing_written
check "clean-up: a draft at another commit (its line names this attempt) is never deleted"

WHY=""; state "[$EXISTING,$(rel_json "$TAG" true true "$SHA" "$(mark)")]" "{\"$TAG\": \"$OTHER\"}"; run_cleanup
fail_closed "another commit"; nothing_written
[ "$(q "[r['tag'] for r in s['releases'] if r['tag']=='$TAG']")" = "['$TAG']" ] || why "the draft was deleted before the tag check"
check "clean-up: this attempt's draft whose tag points at another commit: nothing is deleted"

for unset in GITHUB_RUN_ID GITHUB_RUN_ATTEMPT; do
  WHY=""; state "[$EXISTING,$(rel_json "$TAG" true true "$SHA" "$(mark)")]" "{\"$TAG\": \"$SHA\"}"
  rm -f "$WORK/calls"; : > "$WORK/summary"
  OUT=$(env -u "$unset" PATH="$WORK/bin:$PATH" WORK="$WORK" GITHUB_REPOSITORY=$REPO CLEANUP_SLEEP=0 GITHUB_RUN_ID=$RUN_ID GITHUB_RUN_ATTEMPT=$RUN_ATT \
        bash -c 'unset '"$unset"'; exec bash "$0" "$@"' "$CLEANUP" "$TAG" "$SHA" 2>&1); CODE=$?
  fail_closed "$unset"; nothing_written
  check "clean-up: without $unset it deletes nothing"
done

WHY=""; OUT=""
refused "$CLEANUP" "an RC tag" "$RC8" "$SHA"
refused "$CLEANUP" "a stable tag" android-v2.2.6 "$SHA"
refused "$CLEANUP" "another name" x "$SHA"
refused "$CLEANUP" "a short commit" "$TAG" abc
check "clean-up: an RC or stable tag, another name or a short commit is refused before any call"

WHY=""; T2=test-nightly-cleanup-20261110; state "[$EXISTING,$(rel_json $T2 true true "$SHA" "$(mark)")]" "{\"$T2\": \"$SHA\"}"
rm -f "$WORK/calls"
OUT=$(env PATH="$WORK/bin:$PATH" WORK="$WORK" GITHUB_REPOSITORY=$REPO CLEANUP_SLEEP=0 GITHUB_RUN_ID=$RUN_ID GITHUB_RUN_ATTEMPT=$RUN_ATT \
      bash "$CLEANUP" "$T2" "$SHA" 2>&1); CODE=$?
[ "$CODE" -eq 0 ] || why "exit $CODE"
[ "$(q "[r['tag'] for r in s['releases'] if r['tag']=='$T2']")" = "[]" ] || why "the trial draft remains"
check "clean-up: the throwaway trial's tag (test-nightly-cleanup-<date>) is accepted"

[ "$ran" -gt 0 ] || { echo "nightly publish checks: no case ran"; exit 1; }
if [ "$failures" -eq 0 ]; then echo "nightly publish checks: all $ran passed"; else echo "nightly publish checks: $failures of $ran failed"; exit 1; fi
