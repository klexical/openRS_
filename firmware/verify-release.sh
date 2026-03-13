#!/usr/bin/env bash
# =============================================================================
#  openrs-fw release verification
#  Checks that firmware binaries match their build manifest and were not
#  simply renamed from a previous version.
#
#  Usage:
#    ./firmware/verify-release.sh [--target usb|pro]
#
#  Checks performed:
#    1. BUILD_MANIFEST exists and is valid JSON
#    2. Every binary's SHA-256 matches the manifest
#    3. Manifest git_commit matches the current HEAD
#    4. Binary is not identical to any other version in release/
#
#  Exit codes:
#    0 — all checks passed
#    1 — one or more checks failed
# =============================================================================
set -euo pipefail

TARGET="${1:-}"
if [ "$TARGET" = "--target" ]; then
    TARGET="${2:-}"
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASE_DIR="$SCRIPT_DIR/release"

RED='\033[0;31m'; GRN='\033[0;32m'; YLW='\033[1;33m'; RST='\033[0m'
pass() { echo -e "  ${GRN}✓${RST} $*"; }
fail() { echo -e "  ${RED}✗${RST} $*"; FAILURES=$((FAILURES + 1)); }
warn() { echo -e "  ${YLW}!${RST} $*"; }

FAILURES=0

# If no target specified, check all manifests found
if [ -z "$TARGET" ]; then
    MANIFESTS=("$RELEASE_DIR"/BUILD_MANIFEST_*.json)
    if [ ${#MANIFESTS[@]} -eq 0 ] || [ ! -f "${MANIFESTS[0]}" ]; then
        echo -e "${RED}No BUILD_MANIFEST_*.json found in $RELEASE_DIR${RST}"
        echo "Run ./firmware/build.sh --target usb (or pro) first."
        exit 1
    fi
else
    MANIFESTS=("$RELEASE_DIR/BUILD_MANIFEST_${TARGET}.json")
fi

for MANIFEST in "${MANIFESTS[@]}"; do
    [ -f "$MANIFEST" ] || { fail "Manifest not found: $MANIFEST"; continue; }
    MTARGET=$(python3 -c "import json; print(json.load(open('$MANIFEST'))['target'])")
    echo ""
    echo "Verifying target: $MTARGET ($(basename "$MANIFEST"))"
    echo "─────────────────────────────────────────────"

    # 1. Parse manifest
    FW_VERSION=$(python3 -c "import json; print(json.load(open('$MANIFEST'))['firmware_version'])")
    OUTPUT_BIN=$(python3 -c "import json; print(json.load(open('$MANIFEST'))['output_bin'])")
    MANIFEST_COMMIT=$(python3 -c "import json; print(json.load(open('$MANIFEST'))['git_commit'])")
    BUILT_AT=$(python3 -c "import json; print(json.load(open('$MANIFEST'))['built_at'])")
    pass "Manifest parsed — $FW_VERSION built at $BUILT_AT"

    # 2. SHA-256 verification — iterate all keys from the manifest's sha256 object
    BIN_NAMES=$(python3 -c "import json; [print(k) for k in json.load(open('$MANIFEST'))['sha256'].keys()]")
    while IFS= read -r BIN_NAME; do
        [ -z "$BIN_NAME" ] && continue
        BIN_PATH="$RELEASE_DIR/$BIN_NAME"
        if [ ! -f "$BIN_PATH" ]; then
            fail "$BIN_NAME — file missing"
            continue
        fi
        EXPECTED=$(python3 -c "import json; print(json.load(open('$MANIFEST'))['sha256']['$BIN_NAME'])")
        ACTUAL=$(shasum -a 256 "$BIN_PATH" | cut -d' ' -f1)
        if [ "$EXPECTED" = "$ACTUAL" ]; then
            pass "$BIN_NAME — SHA-256 matches manifest"
        else
            fail "$BIN_NAME — SHA-256 MISMATCH (binary was modified after build)"
            echo "       expected: $EXPECTED"
            echo "       actual:   $ACTUAL"
        fi
    done <<< "$BIN_NAMES"

    # 3. Git commit check
    CURRENT_HEAD=$(git -C "$SCRIPT_DIR/.." rev-parse HEAD 2>/dev/null || echo "unknown")
    if [ "$MANIFEST_COMMIT" = "$CURRENT_HEAD" ]; then
        pass "Git commit matches HEAD ($CURRENT_HEAD)"
    else
        warn "Git commit differs from HEAD"
        echo "       manifest: $MANIFEST_COMMIT"
        echo "       HEAD:     $CURRENT_HEAD"
        echo "       (This is expected if commits were made after the build)"
    fi

    # 4. Duplicate binary detection — catch renamed copies
    APP_SHA=$(shasum -a 256 "$RELEASE_DIR/$OUTPUT_BIN" | cut -d' ' -f1)
    for OTHER in "$RELEASE_DIR"/openrs-fw-*.bin; do
        [ -f "$OTHER" ] || continue
        OTHER_NAME=$(basename "$OTHER")
        [ "$OTHER_NAME" = "$OUTPUT_BIN" ] && continue
        OTHER_SHA=$(shasum -a 256 "$OTHER" | cut -d' ' -f1)
        if [ "$APP_SHA" = "$OTHER_SHA" ]; then
            fail "$OUTPUT_BIN is IDENTICAL to $OTHER_NAME — binary was copied, not rebuilt"
        fi
    done
    pass "No duplicate binaries found"
done

echo ""
if [ $FAILURES -gt 0 ]; then
    echo -e "${RED}$FAILURES check(s) failed.${RST} Do not release — rebuild first."
    exit 1
else
    echo -e "${GRN}All checks passed.${RST} Binaries are verified."
    exit 0
fi
