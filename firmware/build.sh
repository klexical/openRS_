#!/usr/bin/env bash
# =============================================================================
#  openrs-fw build script
#  Clones wican-fw, integrates the focusrs component, applies patches, builds.
#
#  Usage:
#    chmod +x firmware/build.sh
#    ./firmware/build.sh
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

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/.build"
WICAN_DIR="$BUILD_DIR/wican-fw"
RELEASE_DIR="$SCRIPT_DIR/release"
PATCHES_DIR="$SCRIPT_DIR/patches"
COMPONENTS_DIR="$SCRIPT_DIR/components"

# Pinned wican-fw tag for reproducible builds
WICAN_TAG="v3.10"

# ESP-IDF version and install path
# Defaults to inside the build dir so it works in restricted environments.
# Override with: IDF_PATH=/your/path ./build.sh
IDF_VERSION="v5.2.3"
IDF_PATH="${IDF_PATH:-$BUILD_DIR/esp-idf}"

# Colours
RED='\033[0;31m'; GRN='\033[0;32m'; YLW='\033[1;33m'; RST='\033[0m'
log()  { echo -e "${GRN}[openrs-fw]${RST} $*"; }
warn() { echo -e "${YLW}[openrs-fw]${RST} $*"; }
err()  { echo -e "${RED}[openrs-fw]${RST} $*"; exit 1; }

# ── 1. Prerequisites ──────────────────────────────────────────────────────────
log "Checking prerequisites..."
command -v git    >/dev/null 2>&1 || err "git is required"
command -v python3 >/dev/null 2>&1 || err "python3 is required"

# ── 2. ESP-IDF ────────────────────────────────────────────────────────────────
if [ ! -f "$IDF_PATH/export.sh" ]; then
    log "ESP-IDF not found at $IDF_PATH — installing $IDF_VERSION (this takes 5–15 minutes)..."
    mkdir -p "$(dirname "$IDF_PATH")"
    git clone --depth 1 --branch "$IDF_VERSION" \
        --recurse-submodules --shallow-submodules \
        https://github.com/espressif/esp-idf.git "$IDF_PATH"
    "$IDF_PATH/install.sh" esp32c3
    log "ESP-IDF installed."
else
    log "ESP-IDF found at $IDF_PATH"
fi

# shellcheck disable=SC1091
source "$IDF_PATH/export.sh"

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
# ble_transport is header-only in v1.0 — copy for future use
cp -r "$COMPONENTS_DIR/ble_transport" "$WICAN_DIR/components/"

# ── 5. Apply source patches ───────────────────────────────────────────────────
log "Applying source patches..."
python3 "$PATCHES_DIR/apply_patches.py" "$WICAN_DIR"

# ── 6. Build ──────────────────────────────────────────────────────────────────
log "Building for esp32c3..."
cd "$WICAN_DIR"
idf.py set-target esp32c3
idf.py build

# ── 7. Package release files ──────────────────────────────────────────────────
log "Packaging release files..."
mkdir -p "$RELEASE_DIR"

cp build/bootloader/bootloader.bin              "$RELEASE_DIR/bootloader.bin"
cp build/partition_table/partition-table.bin    "$RELEASE_DIR/partition-table.bin"
cp build/ota_data_initial.bin                   "$RELEASE_DIR/ota_data_initial.bin"

# Find the main application binary (named after the project)
APP_BIN=$(find build -maxdepth 1 -name "wican-fw*.bin" | grep -v "bootloader" | head -1)
if [ -z "$APP_BIN" ]; then
    APP_BIN=$(find build -maxdepth 1 -name "*.bin" | grep -v "bootloader\|partition\|ota_data" | head -1)
fi
[ -z "$APP_BIN" ] && err "Could not find application .bin in build output"
cp "$APP_BIN" "$RELEASE_DIR/openrs-fw-usb_v100.bin"
log "App binary: $(basename "$APP_BIN") → openrs-fw-usb_v100.bin"

# storage.bin is optional — present only if a custom NVS image was built
if [ -f "build/storage.bin" ]; then
    cp build/storage.bin "$RELEASE_DIR/storage.bin"
fi

# ── 8. Summary ────────────────────────────────────────────────────────────────
echo ""
log "Build complete! Flash files:"
echo ""
echo "  Address     File"
echo "  ─────────   ────────────────────────────────────────────"
for f in bootloader.bin partition-table.bin ota_data_initial.bin openrs-fw-usb_v100.bin storage.bin; do
    fp="$RELEASE_DIR/$f"
    [ -f "$fp" ] || continue
    SIZE=$(du -sh "$fp" | cut -f1)
    case "$f" in
        bootloader.bin)         ADDR="0x0"       ;;
        partition-table.bin)    ADDR="0x8000"    ;;
        ota_data_initial.bin)   ADDR="0xd000"    ;;
        openrs-fw-usb_v100.bin) ADDR="0x10000"   ;;
        storage.bin)            ADDR="0x383000"  ;;
    esac
    printf "  %-11s %-40s %s\n" "$ADDR" "$f" "($SIZE)"
done
echo ""
log "Release directory: $RELEASE_DIR"
log "Ready to flash. See firmware/docs/firmware-update.md for instructions."
