# aauto.aar — Android Auto Unofficial SDK

## What this is

`aauto.aar` is the unofficial Android Auto SDK that enables a full custom
Activity-based UI in Android Auto — identical rendering to the phone app,
with complete layout control (no template restrictions).

Required for openRS_ Phase 3: Custom Activity Android Auto UI.

---

## How to obtain aauto.aar

### Option 1 — Build from the cupral fork (recommended)

The aa-torque project uses an updated SDK from a Git submodule:

```bash
git clone https://github.com/cupral/aauto-sdk.git
cd aauto-sdk
./gradlew assembleRelease
# Output: build/outputs/aar/aauto-release.aar
cp build/outputs/aar/aauto-release.aar ../android/app/libs/aauto.aar
```

### Option 2 — JitPack (original SDK, may work)

Add to `android/build.gradle.kts` (root):
```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}
```
Then in `android/app/build.gradle.kts`:
```kotlin
implementation("com.github.martoreto:aauto-sdk:v4.7")
```

### Option 3 — Download from aa-torque

The aa-torque project distributes a prebuilt `aauto.aar` at:
```
https://github.com/agronick/aa-torque/blob/master/lib/aauto.aar
```
Download and place at `android/app/libs/aauto.aar`.

---

## Once you have aauto.aar

In `android/app/build.gradle.kts`, uncomment:
```kotlin
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
```

---

## Manifest entry (already added)

The `AndroidManifest.xml` already declares the `CarDashActivity` with
`CATEGORY_PROJECTION_OEM` so the custom UI registers with Android Auto
once the AAR is present.

## Android Auto requirement

The head unit's Android Auto must allow "unknown sources" (AA developer
mode or a modified AA build). This is the same requirement as aa-torque
and similar apps.

---

## Key classes from aauto.aar

| Class | Purpose |
|-------|---------|
| `com.google.android.gms.car.CarActivity` | Base class for the AA Activity (replaces `Screen`) |
| `com.google.android.gms.car.CarUiController` | Controls AA chrome (back button, etc.) |
| `android.car.Car` | Root Android Automotive OS API |

The `CarDashActivity` in `android/auto/CarDashActivity.kt` extends `CarActivity`
and renders the full Compose UI — identical to `MainActivity` — inside Android Auto.
