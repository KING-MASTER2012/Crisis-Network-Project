# Crisis Network: Android client

Kotlin and Jetpack Compose client of the Crisis Network core. The core is the Rust crate
`ffi` (library `crisis_core`); this project builds it for Android with cargo-ndk and calls it
through the UniFFI-generated Kotlin bindings.

Status: **phase A0** (foundation). The app is a single self-test screen that proves the
native core loads and works on a device. Runtime layer, BLE transport and the real UI follow
in phases A1 to A7.

## Prerequisites

| Tool | Notes |
| --- | --- |
| JDK 17 | Gradle and the Android build run on it. |
| Android SDK | `compileSdk` 36. Android Studio installs it. |
| Android NDK `28.2.13676358` | r28c, 16 KB page aligned by default. Gradle uses the version pinned in `core/ffi/build.gradle.kts`; install it with `sdkmanager "ndk;28.2.13676358"` or from Android Studio. |
| Rust (stable, 1.93 or newer) | `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android` |
| cargo-ndk | `cargo install cargo-ndk --locked` |
| Gradle 8.14 | Only to create the wrapper once, see below. |
| Perl and make | Only for `-Pcrisis.sqlcipher=true` (OpenSSL is built from source). |

## First-time setup

The Gradle wrapper (`gradlew`, `gradle/wrapper/`) is not part of this change because it
contains a binary JAR. Create and commit it once:

```
cd clients/android
gradle wrapper --gradle-version 8.14.3
```

After that, use `./gradlew` (`gradlew.bat` on Windows) and change the `gradle-version` of the
CI jobs to the wrapper.

## Build and run

```
cd clients/android
./gradlew :app:assembleDebug          # builds the core for 3 ABIs, the bindings and the APK
./gradlew :app:installDebug           # installs it on the connected device
./gradlew :app:connectedDebugAndroidTest   # runs the on-device self-test (device or emulator)
```

`preBuild` of `:core:ffi` runs, in order: `generateKotlinBindings` (calls
`scripts/gen-bindings.sh` or `.ps1`), `syncKotlinBindings`, `buildRustLibs` (cargo-ndk) and
`verifyElfAlignment` (fails the build if a 64-bit library is not 16 KB aligned). Generated
files live in `core/ffi/build/` and are never committed. The IDE shows the generated Kotlin
as unresolved until the first build has run.

### Options (`gradle.properties` or `-P` on the command line)

| Property | Default | Meaning |
| --- | --- | --- |
| `crisis.rustProfile` | `release` | `release` (LTO, small) or `dev` (much faster Rust builds while iterating). |
| `crisis.sqlcipher` | `false` | Builds the core with SQLCipher so the database can be encrypted. The instrumented test then asserts that encryption is really active. |
| `crisis.skipRust` | `false` | Skips cargo and reuses libraries and bindings that are already built. |

## Layout

```
clients/android
├── app/            application shell (A0: self-test screen)
└── core/ffi/       native core for Android: cargo-ndk + UniFFI bindings + CoreSmoke
```

Further modules (`:core:runtime`, `:core:ble`, `:core:data`, `:core:designsystem`, `:feature:*`)
are added by the next phases.

## Decisions taken in A0

* `minSdk` 26, `compileSdk` and `targetSdk` 36, JDK 17, Kotlin 2.2, AGP 8.13. AGP 9 (built-in
  Kotlin, new DSL) is a deliberate later upgrade.
* No dependency on Google Play Services.
* Nothing is backed up or transferred (`allowBackup=false` and empty data extraction rules):
  the secrets will be wrapped by the Android Keystore, which a restored copy could not use.
* SQLCipher is opt-in (`crisis.sqlcipher`), implemented as the `sqlcipher` feature of the
  `ffi` crate. The final decision (release builds on, debug builds off) is applied by the
  runtime layer in A1 once the build is verified on a device.

## Troubleshooting

* `cargo ndk` not found: install it, and make sure `~/.cargo/bin` is on the `PATH` of the
  process that starts Gradle (Android Studio started from a launcher may not see it).
* `ANDROID_NDK_HOME` problems: unset it to let Gradle use the pinned NDK, or point it to
  `ndk/28.2.13676358`.
* `16 KB alignment check failed`: use NDK r28 or newer, or keep the `RUSTFLAGS` of
  `buildRustLibs` (it is appended to any `RUSTFLAGS` you already set).
* The self-test says "this build has no SQLCipher": expected unless built with
  `-Pcrisis.sqlcipher=true`.
