#!/usr/bin/env bash
# test-rc-maintenance.sh — checks rc-maintenance.sh's source pin in throwaway git repositories (no network, no secrets):
# pinned tree → pinned; any other file changed, marker missing or a marker field differing from the workflow's
# constant → not built. The release-* commands read GitHub and are checked against the live release separately.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PASS=0; FAILS=0
ok()  { PASS=$((PASS + 1)); echo "ok   $*"; }
bad() { FAILS=$((FAILS + 1)); echo "FAIL $*"; }

export EXPECT_RELEASE_TAG=android-v9.9.9-rc.1 EXPECT_RELEASE_TAG_COMMIT=0123456789abcdef0123456789abcdef01234567 \
  EXPECT_ORIGINAL_ASSET=openRS_v9.9.9-rc.1.apk EXPECT_ORIGINAL_ASSET_SHA256=00ff EXPECT_ASSET_NAME=openrs-9.9.9-rc.1-build2.apk \
  EXPECT_VERSION_NAME=9.9.9-rc.1 EXPECT_VERSION_CODE=2
unset GITHUB_OUTPUT
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

repo() {   # a repository whose last commit adds the marker for the tree before it
  local d; d=$(mktemp -d "$TMP/repo.XXXXXX")
  git -C "$d" init -q -b main; git -C "$d" config user.email t@example.invalid; git -C "$d" config user.name t
  git -C "$d" config commit.gpgsign false
  mkdir -p "$d/android/scripts" "$d/android/app"
  cp "$HERE/rc-maintenance.sh" "$d/android/scripts/"; echo "code v1" > "$d/android/app/Main.kt"
  git -C "$d" add -A; git -C "$d" commit -qm source
  local tree; tree=$(git -C "$d" rev-parse 'HEAD^{tree}')
  jq -n --arg t "$tree" '{release_tag: "android-v9.9.9-rc.1", release_tag_commit: "0123456789abcdef0123456789abcdef01234567",
    original_asset: "openRS_v9.9.9-rc.1.apk", original_asset_sha256: "00ff", asset_name: "openrs-9.9.9-rc.1-build2.apk",
    version_name: "9.9.9-rc.1", version_code: 2, source_tree: $t}' > "$d/android/release-maintenance.json"
  git -C "$d" add -A; git -C "$d" commit -qm marker
  echo "$d"
}
run() { (cd "$1" && bash android/scripts/rc-maintenance.sh source android/release-maintenance.json 2>&1); }

D=$(repo)
o=$(run "$D"); grep -q '^pinned=true$' <<<"$o" && ok "pinned tree → pinned" || bad "pinned tree: $o"

git -C "$D" checkout -q -b merged HEAD~1 && git -C "$D" commit -q --allow-empty -m "staging side" \
  && git -C "$D" merge -q --no-ff --no-edit main >/dev/null 2>&1
o=$(run "$D"); grep -q '^pinned=true$' <<<"$o" && ok "--no-ff merge with an unchanged base → pinned" || bad "merge: $o"

D=$(repo); echo "code v2" > "$D/android/app/Main.kt"; git -C "$D" commit -qam "later change"
o=$(run "$D"); grep -q '^pinned=false$' <<<"$o" && ok "source changed after the marker → not built" || bad "changed: $o"

D=$(repo); echo x > "$D/README"; git -C "$D" add README; git -C "$D" commit -qm "unrelated file"
o=$(run "$D"); grep -q '^pinned=false$' <<<"$o" && ok "any other file added → not built" || bad "added: $o"

D=$(repo); jq '.release_tag = "android-v9.9.9-rc.2"' "$D/android/release-maintenance.json" > "$D/m" \
  && mv "$D/m" "$D/android/release-maintenance.json" && git -C "$D" commit -qam "marker redirected"
if o=$(run "$D"); then bad "redirected marker passed: $o"; else grep -q 'differs from the workflow' <<<"$o" \
  && ok "marker field ≠ workflow constant → error" || bad "redirected: $o"; fi

D=$(repo); git -C "$D" rm -q android/release-maintenance.json; git -C "$D" commit -qm "marker removed"
o=$(run "$D"); grep -q '^pinned=false$' <<<"$o" && ok "no marker → not built" || bad "no marker: $o"

D=$(repo); jq '.source_tree = "not-a-tree"' "$D/android/release-maintenance.json" > "$D/m" \
  && mv "$D/m" "$D/android/release-maintenance.json" && git -C "$D" commit -qam "bad tree"
if o=$(run "$D"); then bad "bad tree id passed: $o"; else ok "malformed source_tree → error"; fi

# A marker updated after one exists: the parent's tree still holds the OLD marker, so "parent tree" is the wrong pin
# (that slip happened once); source-tree gives the right one.
D=$(repo); echo "code v3" > "$D/android/app/Main.kt"; git -C "$D" commit -qam "a fix after the first marker"
PARENT=$(git -C "$D" rev-parse 'HEAD^{tree}')
RIGHT=$(cd "$D" && bash android/scripts/rc-maintenance.sh source-tree android/release-maintenance.json)
[ "$RIGHT" != "$PARENT" ] && ok "updated marker: source-tree differs from the parent's tree (old marker inside)" \
  || bad "source-tree equals the parent tree"
jq --arg t "$PARENT" '.source_tree = $t' "$D/android/release-maintenance.json" > "$D/m" && mv "$D/m" "$D/android/release-maintenance.json"
git -C "$D" commit -qam "marker = parent tree (wrong)"
o=$(run "$D"); grep -q '^pinned=false$' <<<"$o" && ok "updated marker pinned to the parent's tree → not built" || bad "parent-tree pin: $o"
jq --arg t "$RIGHT" '.source_tree = $t' "$D/android/release-maintenance.json" > "$D/m" && mv "$D/m" "$D/android/release-maintenance.json"
git -C "$D" commit -qam "marker = source-tree (right)"
o=$(run "$D"); grep -q '^pinned=true$' <<<"$o" && ok "updated marker pinned to source-tree → pinned" || bad "source-tree pin: $o"

echo "rc-maintenance source pin: $PASS passed, $FAILS failed"
[ "$FAILS" -eq 0 ]
