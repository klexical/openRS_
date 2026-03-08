# Release Signing Setup

The release APK is signed with a project-specific keystore. Both the keystore file and its credentials are **gitignored** and must be kept safe on your local machine.

## Files (never committed)

| File | Location | Description |
|------|----------|-------------|
| `openrs-release.jks` | `android/openrs-release.jks` | RSA-2048 release keystore, valid 10 000 days |
| `keystore.properties` | `android/keystore.properties` | Credentials read by `build.gradle.kts` |

## keystore.properties format

```properties
storeFile=openrs-release.jks
storePassword=<password>
keyAlias=openrs
keyPassword=<password>
```

## Restoring on a new machine

Copy `openrs-release.jks` and `keystore.properties` into the `android/` directory, then build normally:

```bash
cd android
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/openRS_vX.Y.Z.apk  (signed)
```

> **Keep the keystore backed up.** If you lose it, any existing installs cannot be updated in-place — Android ties APK updates to the signing certificate.
