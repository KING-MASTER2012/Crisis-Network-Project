# Crisis Network: Android client

Kotlin and Jetpack Compose client of the Crisis Network core. The core is the Rust crate
`ffi` (library `crisis_core`); this project builds it for Android with cargo-ndk and calls it
through the UniFFI-generated Kotlin bindings.

Status: **phase A0** (foundation). The app is a single self-test screen that proves the
native core loads and works on a device. Runtime layer, BLE transport and the real UI follow
in phases A1 to A7.

## Toolchain (versions checked on 2026-10-18)

| Component | Version | Why |
| --- | --- | --- |
| Android Gradle plugin | 9.4.0 | Latest stable. Built-in Kotlin: there is no `org.jetbrains.kotlin.android` plugin. |
| Gradle (wrapper) | 9.6.0 | AGP 9.4 requires 9.6.0 or newer. |
| Kotlin | 2.4.10 | Latest stable; also the Compose compiler plugin version. |
| JDK | 17 or newer | AGP 9.4 minimum. Android Studio's bundled JDK is fine. |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 | androidx.core 1.19 needs compileSdk 37; targetSdk is raised deliberately later. |
| SDK Build Tools | 36.0.0 | AGP 9.4 default (Android Studio installs it). |
| NDK | 28.2.13676358 (r28c) | AGP 9.4 default; 16 KB page aligned. |
| Compose BOM / activity-compose / core-ktx | 2026.09.00 / 1.13.0 / 1.19.1 | Latest stable. |
| kotlinx-coroutines / JNA | 1.11.0 / 5.19.1 | Latest stable. |
| Rust | stable, 1.93 or newer | Workspace `rust-version`. |

All versions live in `gradle/libs.versions.toml` (plugins and libraries) and
`gradle/wrapper/gradle-wrapper.properties` (Gradle).

## One-time setup

1. Install Android Studio (a version that supports AGP 9.4) and let it install the SDK
   platform 37, Build Tools 36.0.0 and NDK `28.2.13676358` (SDK Manager > SDK Tools >
   NDK (Side by side) > show package details).
2. Install Rust and the Android targets and cargo-ndk:

   ```
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
   cargo install cargo-ndk --locked
   ```

3. Start Android Studio (or the terminal) **after** installing Rust, so that `~/.cargo/bin`
   (`%USERPROFILE%\.cargo\bin` on Windows) is on its `PATH`.

The task `:core:ffi:checkRustToolchain` runs before every native build and tells you exactly
what is missing.

## Build and run

Windows (PowerShell) and Linux/macOS use the same wrapper:

```
cd clients/android
.\gradlew.bat :app:assembleDebug          # Windows
./gradlew :app:assembleDebug              # Linux / macOS (./gradlew.sh is an alias)
```

Useful tasks:

```
:app:assembleDebug                  builds the core for 3 ABIs, the bindings and the APK
:app:installDebug                   installs it on the connected device
:app:connectedDebugAndroidTest      runs the on-device self-test (device or emulator)
:core:ffi:checkRustToolchain        checks cargo, cargo-ndk and the Rust targets
```

`preBuild` of `:core:ffi` runs, in order: `checkRustToolchain`, `generateKotlinBindings`
(calls `scripts/gen-bindings.sh` or `.ps1`), `syncKotlinBindings`, `buildRustLibs`
(cargo-ndk) and `verifyElfAlignment` (fails the build if a 64-bit library is not 16 KB
aligned). Generated files live in `core/ffi/build/` and are never committed. The IDE shows
the generated Kotlin as unresolved until the first build has run.

### Options (`gradle.properties` or `-P` on the command line)

| Property | Default | Meaning |
| --- | --- | --- |
| `crisis.rustProfile` (alias `crisisRustProfile`) | `release` | `release` (LTO, small) or `dev` (much faster Rust builds while iterating). |
| `crisis.sqlcipher` (alias `crisisSqlcipher`) | `false` | Builds the core with SQLCipher so the database can be encrypted. The instrumented test then asserts that encryption is really active. |
| `crisis.skipRust` (alias `crisisSkipRust`) | `false` | Skips cargo and reuses libraries and bindings that are already built. |

**Windows PowerShell:** it splits an unquoted `-Pcrisis.rustProfile=dev` at the dot, and Gradle
then reports "Task '.rustProfile=dev' not found". Use the dot-free alias or quote the option:

```
.\gradlew.bat :app:assembleDebug -PcrisisRustProfile=dev
.\gradlew.bat :app:assembleDebug "-Pcrisis.rustProfile=dev"
```

## Layout

```
clients/android
├── app/            application shell (A0: self-test screen)
└── core/ffi/       native core for Android: cargo-ndk + UniFFI bindings + CoreSmoke
```

Further modules (`:core:runtime`, `:core:ble`, `:core:data`, `:core:designsystem`, `:feature:*`)
are added by the next phases.

## Decisions taken in A0

* `minSdk` 26. No dependency on Google Play Services.
* Nothing is backed up or transferred (`allowBackup=false` and empty data extraction rules):
  the secrets will be wrapped by the Android Keystore, which a restored copy could not use.
* SQLCipher is opt-in (`crisis.sqlcipher`), implemented as the `sqlcipher` feature of the
  `ffi` crate. The final decision (release builds on, debug builds off) is applied by the
  runtime layer in A1 once the build is verified on a device.

## Troubleshooting

* `The Rust/Android toolchain is incomplete`: follow the listed commands, then restart the
  Gradle daemon (`.\gradlew.bat --stop`) and Android Studio.
* `ANDROID_NDK_HOME` problems: unset it to let Gradle use the pinned NDK, or point it to
  `ndk/28.2.13676358`.
* `Dependency ... requires libraries and applications that depend on it to compile against
  version 37`: install SDK platform 37 (Android Studio offers it) and keep `compileSdk = 37`.
* `16 KB alignment check failed`: use NDK r28 or newer, or keep the `RUSTFLAGS` of
  `buildRustLibs` (it is appended to any `RUSTFLAGS` you already set).
* The self-test says "this build has no SQLCipher": expected unless built with
  `-PcrisisSqlcipher=true`.
* Resource names must be lowercase with underscores: `data_extraction_rules.xml`, not
  `data-extraction-rules.xml` (aapt rejects hyphens).
