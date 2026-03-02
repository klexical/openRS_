# aauto.aar — Android Auto Unofficial SDK

## What this is

`aauto.aar` is the unofficial Android Auto SDK that enables a full custom
Activity-based UI in Android Auto — identical rendering to the phone app,
with complete layout control (no template restrictions).

This is the approach used by [aa-torque](https://github.com/agronick/aa-torque)
and is required for openRS_'s custom AA UI (Phase 3 of the roadmap).

## How to obtain aauto.aar

1. Download a release from the [aa-torque releases page](https://github.com/agronick/aa-torque/releases)
   and extract `app/libs/aauto.aar` from the APK or source archive.

2. **OR** — extract from the Android Auto app APK:
   ```bash
   # Pull from a rooted device or use an APK mirror
   adb pull /data/app/com.google.android.projection.gearhead*/base.apk
   unzip base.apk classes.jar
   # Repack as aauto.aar
   ```

3. Place the file here as `app/libs/aauto.aar`.

## Once you have it

In `app/build.gradle.kts`, uncomment:
```kotlin
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
```

Then the `CarDashActivity` class in `auto/CarDashActivity.kt` will compile
and the full custom AA UI will be available.

## Manifest note

The manifest already has the `CATEGORY_PROJECTION_OEM` intent filter for
`CarDashActivity`. Android Auto on the head unit must allow "unknown sources"
(AA developer mode / modified AA) for this to work.
