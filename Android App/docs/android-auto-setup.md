# Android Auto Development Setup

## Desktop Head Unit (DHU)

For testing Android Auto screens without a car:

### 1. Install DHU

```bash
# Via Android Studio SDK Manager → SDK Tools → Android Auto Desktop Head Unit
# Or manually:
$ANDROID_SDK/extras/google/auto/desktop-head-unit
```

### 2. Enable Developer Mode

On your phone:
1. Open **Android Auto** app (or Settings → Android Auto)
2. Scroll to **Version** and tap 10 times
3. Tap the ⋮ menu → **Developer settings**
4. Enable **Unknown sources**
5. Add `com.openrs.dash` to the allow-list

### 3. Run DHU

```bash
# Connect phone via USB
adb forward tcp:5277 tcp:5277

# Start DHU
./desktop-head-unit --usb
```

### 4. Sideloading via AAAD

If using AA Auto Deploy (AAAD):

1. Install AAAD on your phone
2. Open AAAD and grant accessibility permission
3. Install openRS APK
4. AAAD will auto-deploy to Android Auto

## AA Screen Constraints

Android Auto has strict UI limitations:

- **PaneTemplate**: Max 4 rows, each row has title + optional text
- **ActionStrip**: Max 4 buttons
- **Refresh rate**: Templates are rate-limited (~1 Hz by AA framework)
- **No custom rendering**: Text only, no Canvas/OpenGL

Our screens use `PaneTemplate` with 4 data-dense rows and an `ActionStrip` for navigation between screens.

## Host Validator

For development, we use `ALLOW_ALL_HOSTS_VALIDATOR` in `RSDashCarAppService.kt`. For a Play Store release, replace with specific host package validation.

## Custom UI (unofficial path)

For research on how apps like [aa-torque](https://github.com/agronick/aa-torque) achieve fully customizable Android Auto UIs (themes, multiple dashboards, custom gauges), see **[android-auto-custom-ui-research.md](android-auto-custom-ui-research.md)**.
