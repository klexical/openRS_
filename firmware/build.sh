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

# Pinned wican-fw tag for reproducible builds (matches stock firmware v4.20u_beta-01)
WICAN_TAG="v4.20u_beta-01"

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
command -v git >/dev/null 2>&1 || err "git is required"

# ESP-IDF 5.2 on macOS with system Python 3.9 fails package validation due to
# a Python 3.9 importlib.metadata bug with namespace packages (e.g. ruamel.yaml).
# Prefer Python 3.11+ from Homebrew if available.
PREFERRED_PYTHON="$(command -v python3.11 2>/dev/null || command -v python3.12 2>/dev/null || true)"
LOCAL_BIN="$BUILD_DIR/bin"
if [ -n "$PREFERRED_PYTHON" ] && [ -x "$PREFERRED_PYTHON" ]; then
    log "Using $PREFERRED_PYTHON for ESP-IDF environment"
    mkdir -p "$LOCAL_BIN"
    ln -sf "$PREFERRED_PYTHON" "$LOCAL_BIN/python3"
    ln -sf "$PREFERRED_PYTHON" "$LOCAL_BIN/python"
    export PATH="$LOCAL_BIN:$PATH"
fi
command -v python3 >/dev/null 2>&1 || err "python3 is required"

# Ensure Homebrew tools (cmake, ninja) are available
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

# Always run install.sh to ensure the Python env and toolchain are complete.
# This is fast (< 30s) when already installed.
log "Running ESP-IDF install for esp32c3..."
"$IDF_PATH/install.sh" esp32c3

# IDF_SKIP_CHECK_DEPENDENCIES bypasses the Python package version check.
# Needed on macOS with system Python 3.9 where setuptools metadata is not
# discoverable via importlib.metadata despite the package being installed.
export IDF_SKIP_CHECK_DEPENDENCIES=1
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

# Copy our sdkconfig.defaults and custom partition table CSV before set-target.
cp "$PATCHES_DIR/sdkconfig.defaults"      "$WICAN_DIR/sdkconfig.defaults"
cp "$PATCHES_DIR/partitions_openrs.csv"   "$WICAN_DIR/partitions_openrs.csv"

# Run set-target when any required sdkconfig flag is missing.
# This regenerates sdkconfig from sdkconfig.defaults (picking up BT, WS, custom partition, etc.)
NEEDS_SET_TARGET=false
if ! grep -q 'CONFIG_IDF_TARGET="esp32c3"' "$WICAN_DIR/sdkconfig" 2>/dev/null; then
    NEEDS_SET_TARGET=true
elif ! grep -q 'CONFIG_BT_ENABLED=y' "$WICAN_DIR/sdkconfig" 2>/dev/null; then
    NEEDS_SET_TARGET=true
elif ! grep -q 'CONFIG_HTTPD_WS_SUPPORT=y' "$WICAN_DIR/sdkconfig" 2>/dev/null; then
    NEEDS_SET_TARGET=true
elif ! grep -q 'CONFIG_PARTITION_TABLE_CUSTOM=y' "$WICAN_DIR/sdkconfig" 2>/dev/null; then
    NEEDS_SET_TARGET=true
fi

if [ "$NEEDS_SET_TARGET" = "true" ]; then
    log "Running set-target esp32c3 (regenerates sdkconfig with BT, WS, custom partition table)..."
    idf.py set-target esp32c3
else
    log "Target already configured for esp32c3 with required options — skipping set-target"
fi

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
cp "$APP_BIN" "$RELEASE_DIR/openrs-fw-usb_v140.bin"
log "App binary: $(basename "$APP_BIN") → openrs-fw-usb_v140.bin"

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
for f in bootloader.bin partition-table.bin ota_data_initial.bin openrs-fw-usb_v140.bin storage.bin; do
    fp="$RELEASE_DIR/$f"
    [ -f "$fp" ] || continue
    SIZE=$(du -sh "$fp" | cut -f1)
    case "$f" in
        bootloader.bin)         ADDR="0x0"       ;;
        partition-table.bin)    ADDR="0x8000"    ;;
        ota_data_initial.bin)   ADDR="0xd000"    ;;
        openrs-fw-usb_v140.bin) ADDR="0x10000"   ;;
        storage.bin)            ADDR="0x210000"  ;;
    esac
    printf "  %-11s %-40s %s\n" "$ADDR" "$f" "($SIZE)"
done
echo ""
log "Release directory: $RELEASE_DIR"
log "Ready to flash. See firmware/docs/firmware-update.md for instructions."
