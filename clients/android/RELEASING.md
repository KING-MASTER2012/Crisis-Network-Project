# Releasing the Android app

Nothing in this file has been run end to end yet: the first signed release is the test of it.

## What a release needs

|             | Value                                                | Why                                                                                                                                               |
|-------------|------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| Native core | `-Pcrisis.rustProfile=release` (the default)         | LTO build; the `dev` profile is far larger and slower.                                                                                            |
| Encryption  | `-Pcrisis.sqlcipher=true`                            | Release builds open the database with `EncryptionPolicy.Required`; a core without SQLCipher cannot start. Needs Perl and make (OpenSSL is built). |
| Signing     | the four values below                                | An unsigned APK cannot be installed on a phone or uploaded.                                                                                       |
| Version     | `-Pcrisis.versionName=1.2.3 -Pcrisis.versionCode=12` | `versionCode` must be higher than in every earlier release. Defaults are `0.1.0` and `1`.                                                         |

`./gradlew :app:assembleRelease` or `:app:bundleRelease` stops at the start (before the long native build)
with a message when one of the first three is missing. CI sets `-Pcrisis.releaseCheckOnly=true` to build the
release variant without them, only to check R8 and the release lint; that APK must never be shipped.

## Signing

Create the key once, keep the file and both passwords in a password manager **and** a second safe place:
an app signed with a lost key can never be updated.

```
keytool -genkeypair -v -keystore crisis-release.jks -alias crisis -keyalg RSA -keysize 4096 -validity 10000
```

The build reads, from Gradle properties or from the environment (the properties win):

| Property                       | Environment variable            |
|--------------------------------|---------------------------------|
| `crisis.signing.storeFile`     | `CRISIS_SIGNING_STORE_FILE`     |
| `crisis.signing.storePassword` | `CRISIS_SIGNING_STORE_PASSWORD` |
| `crisis.signing.keyAlias`      | `CRISIS_SIGNING_KEY_ALIAS`      |
| `crisis.signing.keyPassword`   | `CRISIS_SIGNING_KEY_PASSWORD`   |

Never put them in `gradle.properties` of the repository. Put them in `~/.gradle/gradle.properties` or the shell.

Local build:

```
CRISIS_SIGNING_STORE_FILE=/safe/crisis-release.jks CRISIS_SIGNING_STORE_PASSWORD=... \
CRISIS_SIGNING_KEY_ALIAS=crisis CRISIS_SIGNING_KEY_PASSWORD=... \
./gradlew :app:assembleRelease :app:bundleRelease -Pcrisis.sqlcipher=true -Pcrisis.versionName=1.0.0 -Pcrisis.versionCode=1
```

## The release workflow

`.github/workflows/release-android.yml` runs for a tag `android-v1.2.3` (or by hand with the version). Secrets:
`ANDROID_KEYSTORE_BASE64` (`base64 -w0 crisis-release.jks`), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`. It builds APK and bundle, verifies the signature with `apksigner` and the 16 KB
alignment with `zipalign`, and uploads them with `mapping.txt`. **Keep the mapping file of every release**: stack
traces from that version cannot be read without it.

## Before a release

1. `./gradlew :app:lintDebug :app:testDebugUnitTest :core:runtime:testDebugUnitTest :core:ble:testDebugUnitTest` and the CI release check are green.
2. The matrix in `DEVICE_MATRIX.md` was walked on real phones (at least the relay test and the screen-off test).
3. Install the release APK (not debug) on a clean phone: onboarding, identity, join the mesh, a message between two phones.
4. `adb shell dumpsys package org.crisisnetwork.android | grep -i debuggable` shows no `DEBUGGABLE`.
5. Check that an update from the previous release keeps the identity (install over the old APK).

## Baseline profile

`app/src/main/baseline-prof.txt` lists the start-up path and is installed by `androidx.profileinstaller`, which also
activates the profiles of Compose and AndroidX. A profile generated on a device covers every screen and is worth
making before a public release:

1. Install a release-like build (`assembleRelease` with `-Pcrisis.releaseCheckOnly=true` and the debug key, or a
   benchmark build type) on a phone and start `adb shell cmd package compile -m verify -f org.crisisnetwork.android`.
2. Use the main paths by hand (start, chats, announcements, settings, network), then
   `adb shell cmd package dump-profiles org.crisisnetwork.android` and pull the profile.
3. Convert it with `profgen` or add the Baseline Profile Gradle plugin with a Macrobenchmark module (needs a device
   or a managed device; not set up here), and merge the result into `baseline-prof.txt`.
4. Compare cold start with `adb shell am start -W` before and after.

## Dependency check

`libs.versions.toml` says when the versions were checked. Before a release check the Rust side too
(`cargo audit`, see the CI job of the core) and the Android libraries for security advisories.
