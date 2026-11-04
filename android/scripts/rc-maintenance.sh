#!/usr/bin/env bash
# rc-maintenance.sh <command> <marker> — the checks around an RC maintenance asset: a separately named, higher-versionCode
# APK added to an EXISTING release (rc.7 Build 38: openrs-2.2.7-rc.7-build38.apk beside the untouched
# openRS_v2.2.7-rc.7.apk). Run by .github/workflows/rc-maintenance-asset.yml; every command is read-only.
#
#   source          Is this checkout the pinned, reviewed source? Prints pinned=true|false (also to $GITHUB_OUTPUT).
#                   The marker can't hold the hash of the tree it is part of, so it pins everything else: the tree of
#                   HEAD with the marker file itself removed from the index must equal the marker's source_tree. That
#                   covers every byte that is built, this script and the workflow. The marker's other fields must
#                   equal the workflow's own constants (EXPECT_* in the environment), so none of them is free either.
#                   A missing marker or a different tree is "not the pinned source" (exit 0), never an error.
#   release-before  The release as pinned (GH_TOKEN, GITHUB_REPOSITORY): tag → release_tag_commit, prerelease, not a
#                   draft, original asset present with its SHA-256. Prints uploaded=true|false for asset_name.
#   release-upload  Same checks; FAILS if asset_name is already on the release (the workflow never replaces an asset).
#   release-after   <apk> Same checks, plus asset_name present with exactly the SHA-256 of <apk>, and nothing else
#                   added or removed: assets = original + asset_name.
# Never prints a secret: it reads none.
set -euo pipefail
CMD="${1:?command}"; MARKER="${2:?marker path, relative to the repository root}"
fail() { echo "::error::$*"; exit 1; }
out() { echo "$1"; if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "$1" >> "$GITHUB_OUTPUT"; fi; }
field() { jq -er --arg k "$1" '.[$k] | tostring' "$ROOT/$MARKER"; }

ROOT=$(git rev-parse --show-toplevel)

case "$CMD" in
  source)
    if [ ! -f "$ROOT/$MARKER" ]; then
      echo "::notice::no $MARKER in this commit — not the pinned source, nothing to build"; out "pinned=false"; exit 0
    fi
    jq -e . "$ROOT/$MARKER" >/dev/null || fail "$MARKER is not valid JSON"
    WANT=$(field source_tree)
    [[ "$WANT" =~ ^[0-9a-f]{40}$ ]] || fail "$MARKER source_tree '$WANT' is not a 40-hex tree id"
    IDX=$(mktemp); trap 'rm -f "$IDX"' EXIT
    GIT_INDEX_FILE="$IDX" git -C "$ROOT" read-tree HEAD
    GIT_INDEX_FILE="$IDX" git -C "$ROOT" rm --cached -q -- "$MARKER"
    HAVE=$(GIT_INDEX_FILE="$IDX" git -C "$ROOT" write-tree)
    if [ "$HAVE" != "$WANT" ]; then
      echo "::notice::not the pinned source (tree without the marker $HAVE, pinned $WANT) — nothing to build"
      out "pinned=false"; exit 0
    fi
    for k in release_tag release_tag_commit original_asset original_asset_sha256 asset_name version_name version_code; do
      var="EXPECT_$(tr '[:lower:]' '[:upper:]' <<<"$k")"
      [ -n "${!var:-}" ] || fail "$var is not set — the workflow pins every marker field"
      [ "$(field "$k")" = "${!var}" ] || fail "$MARKER $k '$(field "$k")' differs from the workflow's $var '${!var}'"
    done
    echo "Pinned source: tree without the marker = $HAVE; $(field asset_name) for $(field release_tag)"
    out "pinned=true"
    ;;

  release-before|release-upload|release-after)
    : "${GITHUB_REPOSITORY:?}"
    TAG=$(field release_tag); ASSET=$(field asset_name)
    ORIG=$(field original_asset); ORIG_SHA=$(field original_asset_sha256); TAG_COMMIT=$(field release_tag_commit)
    REF=$(gh api "repos/$GITHUB_REPOSITORY/git/ref/tags/$TAG" --jq '.object.type + " " + .object.sha')
    [ "$REF" = "commit $TAG_COMMIT" ] || fail "tag $TAG is '$REF', pinned as commit $TAG_COMMIT — refusing"
    REL=$(gh api "repos/$GITHUB_REPOSITORY/releases/tags/$TAG")
    [ "$(jq -r .draft <<<"$REL")" = "false" ] || fail "release $TAG is a draft"
    [ "$(jq -r .prerelease <<<"$REL")" = "true" ] || fail "release $TAG is not a prerelease — pinned as one"
    GOT=$(jq -r --arg n "$ORIG" '[.assets[] | select(.name == $n) | .digest] | if length == 1 then .[0] else "missing" end' <<<"$REL")
    [ "$GOT" = "sha256:$ORIG_SHA" ] || fail "original asset $ORIG is '$GOT', pinned sha256:$ORIG_SHA — refusing"
    HAS=$(jq -r --arg n "$ASSET" '[.assets[] | select(.name == $n)] | length' <<<"$REL")
    case "$CMD" in
      release-before)
        echo "Release $TAG as pinned: tag → $TAG_COMMIT, prerelease, $ORIG sha256:${ORIG_SHA:0:16}…"
        if [ "$HAS" = "0" ]; then out "uploaded=false"; else out "uploaded=true"; fi ;;
      release-upload)
        [ "$HAS" = "0" ] || fail "$ASSET is already on $TAG — never replaced"
        echo "Release $TAG as pinned; $ASSET not present — upload may proceed" ;;
      release-after)
        APK="${3:?apk}"
        SHA=$(sha256sum "$APK" | cut -d' ' -f1)
        GOT=$(jq -r --arg n "$ASSET" '[.assets[] | select(.name == $n) | .digest] | if length == 1 then .[0] else "missing" end' <<<"$REL")
        [ "$GOT" = "sha256:$SHA" ] || fail "uploaded $ASSET is '$GOT', built sha256:$SHA"
        NAMES=$(jq -r '[.assets[].name] | sort | join(" ")' <<<"$REL")
        WANT_NAMES=$(printf '%s\n' "$ORIG" "$ASSET" | sort | paste -sd' ' -)
        [ "$NAMES" = "$WANT_NAMES" ] || fail "release assets are '$NAMES', expected '$WANT_NAMES'"
        echo "Release $TAG after upload: $ORIG unchanged (sha256:${ORIG_SHA:0:16}…), $ASSET sha256:${SHA:0:16}…, tag and prerelease unchanged" ;;
    esac
    ;;

  *) fail "unknown command $CMD" ;;
esac
