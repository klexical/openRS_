#!/usr/bin/env bash
# =============================================================================
#  openrs-fw build script
#  Clones wican-fw, integrates the focusrs component, applies patches, builds.
#
#  Usage:
#    ./firmware/build.sh [--target usb|pro]
#
#  Targets:
#    usb  — WiCAN USB-C3 (ESP32-C3, wican-fw v4.20u)     [default]
#    pro  — WiCAN Pro    (ESP32-S3, wican-fw v4.48p)
#
#  Output:
#    firmware/release/  ← flash-ready .bin files
#
#  Requirements:
#    - macOS or Linux
#    - git, python3
#    - ~2 GB free disk space (ESP-IDF + toolchain)
# =============================================================================
set -euo pipefail

# ── Parse arguments ──────────────────────────────────────────────────────────
TARGET="usb"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --target)
            TARGET="$2"
            shift 2
            ;;
        --target=*)
            TARGET="${1#*=}"
            shift
            ;;
        *)
            echo "Unknown argument: $1"
            echo "Usage: $0 [--target usb|pro]"
            exit 1
            ;;
    esac
done

# Validate target
case "$TARGET" in
    usb|pro) ;;
    *)
        echo "ERROR: invalid target '$TARGET'. Must be 'usb' or 'pro'."
        exit 1
        ;;
esac

# ── Target-specific configuration ───────────────────────────────────────────
# Each target has its own upstream tag, SoC, sdkconfig, partition table, and
# output binary name. The focusrs component is shared across all targets.
case "$TARGET" in
    usb)
        WICAN_TAG="v4.20u_beta-01"
        IDF_TARGET="esp32c3"
        SDKCONFIG_FILE="sdkconfig.defaults.usb"
        PARTITIONS_FILE="partitions_openrs_usb.csv"
        OUTPUT_BIN="openrs-fw-usb_v1.5.bin"
        TARGET_DESC="WiCAN USB-C3 (ESP32-C3)"
        ;;
    pro)
        WICAN_TAG="v4.48p"
        IDF_TARGET="esp32s3"
        SDKCONFIG_FILE="sdkconfig.defaults.pro"
        PARTITIONS_FILE="partitions_openrs_pro.csv"
        OUTPUT_BIN="openrs-fw-pro_v1.0.bin"
        TARGET_DESC="WiCAN Pro (ESP32-S3)"
        ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/.build/$TARGET"
WICAN_DIR="$BUILD_DIR/wican-fw"
RELEASE_DIR="$SCRIPT_DIR/release"
PATCHES_DIR="$SCRIPT_DIR/patches"
COMPONENTS_DIR="$SCRIPT_DIR/components"

IDF_VERSION="v5.2.3"
IDF_PATH="${IDF_PATH:-$SCRIPT_DIR/.build/esp-idf}"

# Colours
RED='\033[0;31m'; GRN='\033[0;32m'; YLW='\033[1;33m'; RST='\033[0m'
log()  { echo -e "${GRN}[openrs-fw:$TARGET]${RST} $*"; }
warn() { echo -e "${YLW}[openrs-fw:$TARGET]${RST} $*"; }
err()  { echo -e "${RED}[openrs-fw:$TARGET]${RST} $*"; exit 1; }

log "Build target: $TARGET_DESC"
log "Upstream tag: $WICAN_TAG"

# ── 1. Prerequisites ──────────────────────────────────────────────────────────
log "Checking prerequisites..."
command -v git >/dev/null 2>&1 || err "git is required"

command -v python3 >/dev/null 2>&1 || err "python3 is required"

export PATH="/opt/homebrew/bin:$PATH"

# ── 2. ESP-IDF ────────────────────────────────────────────────────────────────
if [ ! -f "$IDF_PATH/export.sh" ]; then
    log "ESP-IDF not found at $IDF_PATH — installing $IDF_VERSION (this takes 5–15 minutes)..."
    mkdir -p "$(dirname "$IDF_PATH")"
    git clone --depth 1 --branch "$IDF_VERSION" \
        --recurse-submodules --shallow-submodules \
        https://github.com/espressif/esp-idf.git "$IDF_PATH"
    log "ESP-IDF cloned."
else
    log "ESP-IDF found at $IDF_PATH"
fi

log "Running ESP-IDF install for $IDF_TARGET..."
"$IDF_PATH/install.sh" "$IDF_TARGET"

export IDF_SKIP_CHECK_DEPENDENCIES=1
# shellcheck disable=SC1091
# export.sh's Python package check can fail on macOS due to ruamel.yaml
# namespace packaging issues, even when all packages actually import fine.
# Allow the failure — the PATH and env vars are set before the check runs.
set +e
source "$IDF_PATH/export.sh"
set -e
# Verify idf.py is actually available after sourcing
command -v idf.py >/dev/null 2>&1 || err "idf.py not found after sourcing export.sh — ESP-IDF setup is broken"

# ── 3. Clone wican-fw ─────────────────────────────────────────────────────────
mkdir -p "$BUILD_DIR"
if [ ! -d "$WICAN_DIR/.git" ]; then
    log "Cloning meatpiHQ/wican-fw @ $WICAN_TAG ..."
    git clone --depth 1 --branch "$WICAN_TAG" \
        https://github.com/meatpiHQ/wican-fw.git "$WICAN_DIR"
else
    log "wican-fw already cloned at $WICAN_DIR"
fi

# ── 4. Inject our focusrs component ──────────────────────────────────────────
log "Injecting focusrs component..."
cp -r "$COMPONENTS_DIR/focusrs" "$WICAN_DIR/components/"
cp -r "$COMPONENTS_DIR/ble_transport" "$WICAN_DIR/components/"

# ── 5. Apply source patches ───────────────────────────────────────────────────
log "Applying source patches (target: $TARGET)..."
python3 "$PATCHES_DIR/apply_patches.py" "$WICAN_DIR" --target "$TARGET"

# ── 6. Build ──────────────────────────────────────────────────────────────────
log "Building for $IDF_TARGET..."
cd "$WICAN_DIR"

cp "$PATCHES_DIR/$SDKCONFIG_FILE"   "$WICAN_DIR/sdkconfig.defaults"
cp "$PATCHES_DIR/$PARTITIONS_FILE"  "$WICAN_DIR/$PARTITIONS_FILE"

NEEDS_SET_TARGET=false
if ! grep -q "CONFIG_IDF_TARGET=\"$IDF_TARGET\"" "$WICAN_DIR/sdkconfig" 2>/dev/null; then
    NEEDS_SET_TARGET=true
elif ! grep -q 'CONFIG_BT_ENABLED=y' "$WICAN_DIR/sdkconfig" 2>/dev/null; then
    NEEDS_SET_TARGET=true
elif ! grep -q 'CONFIG_HTTPD_WS_SUPPORT=y' "$WICAN_DIR/sdkconfig" 2>/dev/null; then
    NEEDS_SET_TARGET=true
elif ! grep -q 'CONFIG_PARTITION_TABLE_CUSTOM=y' "$WICAN_DIR/sdkconfig" 2>/dev/null; then
    NEEDS_SET_TARGET=true
fi

if [ "$NEEDS_SET_TARGET" = "true" ]; then
    log "Running set-target $IDF_TARGET (regenerates sdkconfig)..."
    idf.py set-target "$IDF_TARGET"
else
    log "Target already configured for $IDF_TARGET with required options — skipping set-target"
fi

# Delete stale dependencies.lock if present — force fresh resolution.
# Works around persistent hash mismatches for git-sourced components
# (e.g. meatpihq/usb_host_ch34x_vcp) where the upstream content changed
# after the lock was generated.
if [ -f "$WICAN_DIR/dependencies.lock" ]; then
    rm -f "$WICAN_DIR/dependencies.lock"
    log "Removed stale dependencies.lock (will be regenerated)"
fi

idf.py build

# ── 7. Package release files ──────────────────────────────────────────────────
log "Packaging release files..."
mkdir -p "$RELEASE_DIR"

BOOT_BIN="bootloader_${TARGET}.bin"
PART_BIN="partition-table_${TARGET}.bin"
OTA_BIN="ota_data_initial_${TARGET}.bin"

cp build/bootloader/bootloader.bin           "$RELEASE_DIR/$BOOT_BIN"
cp build/partition_table/partition-table.bin "$RELEASE_DIR/$PART_BIN"
cp build/ota_data_initial.bin                "$RELEASE_DIR/$OTA_BIN"

APP_BIN=$(find build -maxdepth 1 -name "wican-fw*.bin" | grep -v "bootloader" | head -1)
if [ -z "$APP_BIN" ]; then
    APP_BIN=$(find build -maxdepth 1 -name "*.bin" | grep -v "bootloader\|partition\|ota_data" | head -1)
fi
[ -z "$APP_BIN" ] && err "Could not find application .bin in build output"
cp "$APP_BIN" "$RELEASE_DIR/$OUTPUT_BIN"
log "App binary: $(basename "$APP_BIN") → $OUTPUT_BIN"

if [ -f "build/storage.bin" ]; then
    cp build/storage.bin "$RELEASE_DIR/storage.bin"
fi

# ── 8. Build manifest ───────────────────────────────────────────────────────
# Records what was built, when, and from which source. Used by
# verify-release.sh and CI to catch stale/renamed binaries.
log "Writing build manifest..."
FW_VERSION=$(grep -o 'OPENRS_FW_VERSION.*"[^"]*"' "$WICAN_DIR/components/focusrs/focusrs.h" | grep -o '"[^"]*"' | tr -d '"')
GIT_SHA=$(git -C "$SCRIPT_DIR/.." rev-parse HEAD 2>/dev/null || echo "unknown")
GIT_DIRTY=$(git -C "$SCRIPT_DIR/.." diff --quiet 2>/dev/null && echo "false" || echo "true")
APP_SHA256=$(shasum -a 256 "$RELEASE_DIR/$OUTPUT_BIN" | cut -d' ' -f1)
BOOT_SHA256=$(shasum -a 256 "$RELEASE_DIR/$BOOT_BIN" | cut -d' ' -f1)
PART_SHA256=$(shasum -a 256 "$RELEASE_DIR/$PART_BIN" | cut -d' ' -f1)
OTA_SHA256=$(shasum -a 256 "$RELEASE_DIR/$OTA_BIN" | cut -d' ' -f1)

cat > "$RELEASE_DIR/BUILD_MANIFEST_${TARGET}.json" <<MANIFEST
{
  "target": "$TARGET",
  "firmware_version": "$FW_VERSION",
  "output_bin": "$OUTPUT_BIN",
  "upstream_tag": "$WICAN_TAG",
  "idf_target": "$IDF_TARGET",
  "built_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "git_commit": "$GIT_SHA",
  "git_dirty": $GIT_DIRTY,
  "sha256": {
    "$OUTPUT_BIN": "$APP_SHA256",
    "$BOOT_BIN": "$BOOT_SHA256",
    "$PART_BIN": "$PART_SHA256",
    "$OTA_BIN": "$OTA_SHA256"
  }
}
MANIFEST
log "Manifest: $RELEASE_DIR/BUILD_MANIFEST_${TARGET}.json"

# ── 9. Summary ────────────────────────────────────────────────────────────────
echo ""
log "Build complete! Target: $TARGET_DESC"
log "Flash files:"
echo ""
echo "  Address     File"
echo "  ─────────   ────────────────────────────────────────────"
for f in "$BOOT_BIN" "$PART_BIN" "$OTA_BIN" "$OUTPUT_BIN" storage.bin; do
    fp="$RELEASE_DIR/$f"
    [ -f "$fp" ] || continue
    SIZE=$(du -sh "$fp" | cut -f1)
    case "$f" in
        "$BOOT_BIN")   ADDR="0x0"       ;;
        "$PART_BIN")   ADDR="0x8000"    ;;
        "$OTA_BIN")    ADDR="0xd000"    ;;
        "$OUTPUT_BIN") ADDR="0x10000"   ;;
        storage.bin)   ADDR="0x210000"  ;;
    esac
    printf "  %-11s %-40s %s\n" "$ADDR" "$f" "($SIZE)"
done
echo ""
log "Release directory: $RELEASE_DIR"
log "Ready to flash. See android/docs/firmware-update.md for instructions."
