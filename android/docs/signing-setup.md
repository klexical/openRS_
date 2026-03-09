# Release Signing Setup

The release APK is signed with a project-specific keystore. Both the keystore file and its credentials are **gitignored** and must be kept safe on your local machine.

## Files (never committed)

| File | Location | Description |
|------|----------|-------------|
| `openrs-release.jks` | `android/openrs-release.jks` | Release keystore, valid 10 000 days |
| `keystore.properties` | `android/keystore.properties` | Credentials read by `build.gradle.kts` |

## Generating a new keystore

```bash
keytool -genkeypair -v \
  -keystore openrs-release.jks \
  -alias openrs \
  -keyalg RSA -keysize 4096 \
  -validity 10000
```

> **Recommendation:** Use RSA-4096 for new keystores. Existing RSA-2048 keys remain valid but 4096-bit is current best practice. Alternatively, use EC keys: `-keyalg EC -groupname secp256r1`.

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
