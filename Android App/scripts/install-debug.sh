#!/bin/bash
# ═══════════════════════════════════════════════════════
# openRS — Quick install to connected device
# ═══════════════════════════════════════════════════════

set -e

echo "🔧 Building openRS debug APK..."
./gradlew assembleDebug --no-daemon

APK="app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK" ]; then
    echo "❌ Build failed — APK not found"
    exit 1
fi

echo "📱 Installing to device..."
adb install -r "$APK"

echo "🚀 Launching openRS..."
adb shell am start -n com.openrs.dash.debug/com.openrs.dash.ui.MainActivity

echo "✅ Done! openRS is running."
