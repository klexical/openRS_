#!/bin/bash
# ═══════════════════════════════════════════════════════
# openRS — Quick install to connected device
# ═══════════════════════════════════════════════════════

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

echo "🔧 Building openRS debug APK..."
./gradlew assembleDebug --no-daemon

# build.gradle.kts renames debug output to openRS_v{version}-staging-debug.apk
APK=$(ls -t app/build/outputs/apk/debug/openRS_v*-staging-debug.apk 2>/dev/null | head -1)

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "❌ Build failed — no openRS_v*-staging-debug.apk in $PROJECT_DIR/app/build/outputs/apk/debug/"
    exit 1
fi

echo "📱 Installing to device..."
adb install -r "$APK"

echo "🚀 Launching openRS..."
# Android 17+ relaunches an updated app's task from SystemUI ~1 s after install (no extras,
# CLEAR_TASK). Let that settle, then force-stop + start so our extras (e.g. demo) survive.
sleep 3
# Extra args pass through to am start, e.g. --es demo driving (debug demo mode)
adb shell am start -S -n com.openrs.dash.debug/com.openrs.dash.ui.MainActivity "$@"

echo "✅ Done! openRS is running."
