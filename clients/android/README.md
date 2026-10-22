# Crisis Network: Android client

Kotlin and Jetpack Compose client of the Crisis Network core. The core is the Rust crate
`ffi` (library `crisis_core`); this project builds it for Android with cargo-ndk and calls it
through the UniFFI-generated Kotlin bindings.

Status: **phase A1** (core runtime layer, no UI and no BLE). The app is still the single self-test
screen of A0; `:core:runtime` adds the actor that drives the core, the clock policy, the
Keystore-wrapped secrets and a `Transport` abstraction with an in-memory loopback. BLE transport
and the real UI follow in phases A2 to A7.

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
   platform 37 (package `platforms;android-37.0`), Build Tools 36.0.0 and NDK `28.2.13676358` (SDK Manager > SDK Tools >
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
./gradlew :app:assembleDebug              # Linux / macOS (needs `chmod +x gradlew` once)
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
| `crisis.abis` (alias `crisisAbis`) | all three | Comma separated subset of `arm64-v8a,armeabi-v7a,x86_64`, e.g. `-Pcrisis.abis=x86_64` for emulator-only builds (CI smoke job). |

The dot-free alias wins over the dotted name when both are set (for example when `gradle.properties` sets the dotted one).

**Windows PowerShell:** it splits an unquoted `-Pcrisis.rustProfile=dev` at the dot, and Gradle
then reports "Task '.rustProfile=dev' not found". Use the dot-free alias or quote the option:

```
.\gradlew.bat :app:assembleDebug -PcrisisRustProfile=dev
.\gradlew.bat :app:assembleDebug "-Pcrisis.rustProfile=dev"
```

## Layout

```
clients/android
├── app/              application shell (A0: self-test screen)
└── core/
    ├── ffi/          native core for Android: cargo-ndk + UniFFI bindings + CoreSmoke
    ├── runtime/      A1: NodeRuntime actor, clock policy, secret vault, Transport + loopback
    └── ble/          A2: BLE link layer (scanner, advertiser, GATT server and client, BleTransport)
```

Further modules (`:core:data`, `:core:designsystem`, `:feature:*`) are added by the next phases.

## Decisions taken in A0

* `minSdk` 26. No dependency on Google Play Services.
* Nothing is backed up or transferred (`allowBackup=false` and empty data extraction rules):
  the secrets will be wrapped by the Android Keystore, which a restored copy could not use.
* SQLCipher is opt-in (`crisis.sqlcipher`), implemented as the `sqlcipher` feature of the
  `ffi` crate. The final decision (release builds on, debug builds off) is applied by the
  runtime layer in A1 once the build is verified on a device.

## Runtime layer (`:core:runtime`, phase A1)

Package `org.crisisnetwork.android.core.runtime`. No UI, no Bluetooth.

| Part | What it does |
| --- | --- |
| `NodeRuntime` | Owns one `CrisisNode` as a **single-threaded actor**. Transport events, publish calls, queries and timer ticks all go through one mailbox and run one after another on the IO dispatcher (every core call blocks on SQLite). It ticks when the core's `next_timer_ms` is reached (and at least every 30 s), sends transport commands to the `Transport`, and publishes `CoreNotification`s on a `SharedFlow`. |
| `NodeBootstrap` | Checks the interface version, unwraps seed and database key from the vault, opens the database under `noBackupFilesDir/crisis` and returns a `NodeRuntime` (not started). |
| `MonotonicWallClock` | The only time source the core sees (see below). |
| `SecretVault`, `AesGcmSecretCipher`, `KeystoreAesKey` | Seed and database key sealed with a non-extractable Android Keystore AES-256-GCM key (StrongBox when available). |
| `CoreFailure` | Stable mapping of `CoreException` (`DatabaseUnreadable` for `WrongKeyOrNotADatabase`, `SchemaTooNew`, `EncryptionUnavailable`, ...). `InternalError` and `Unexpected` are fatal and stop the runtime. |
| `Transport`, `LoopbackNetwork` | What A2/A3 implement for BLE; the loopback connects two nodes in memory (with a real MTU limit) for tests and previews. |

### Clock policy

The core stores `received_at_ms` and counts expiry from it, so its clock must survive restarts and
never run backwards. `MonotonicWallClock`: inside a process time advances with
`elapsedRealtime`, so a wall clock that jumps back cannot move it back; a wall clock that is more
than 2 s *ahead* (for example the network set it after boot) is adopted. The latest handed-out
time is persisted (`clock.mark`, throttled) and used at the next start unless the wall clock is
plausible (after 2020) and more than 24 h behind it. Limitation: a wall clock that jumps far
forward while the app runs is adopted, because it cannot be told from a correction.

### Encryption policy

`EncryptionPolicy.forApp(context)`: **release builds require SQLCipher** (opening fails with
`CoreFailure.EncryptionUnavailable` rather than storing plaintext), debuggable builds use it when
the core has it (`-Pcrisis.sqlcipher=true`) and fall back to a plaintext database otherwise. A
development device that switches between the two cannot read the other build's database
(`DatabaseUnreadable`): clear the app data. Build releases with `-Pcrisis.sqlcipher=true`.

### Secrets

Seed and database key are generated by the core, sealed with the Keystore key (labels are bound to
the ciphertext) and stored in `noBackupFilesDir/crisis/vault`. A stored secret that cannot be
decrypted (for example the Keystore key was lost) is **never overwritten**:
`CoreFailure.SecretsUnavailable(resettable = true)` is reported, and only an explicit
`NodeBootstrap.resetIdentity` (which loses the identity and all messages) clears it. The seed and
key arrays handed to the core are overwritten afterwards, but the JVM and the native marshalling may
have made other copies; this residual risk is documented for phase A7.

### Tests

```
./gradlew :core:runtime:testDebugUnitTest              # JVM: clock, timer policy, vault, loopback, failures
./gradlew :core:runtime:connectedDebugAndroidTest      # device: two nodes exchange a direct message and ack
```

The unit tests need no native library. The core itself only loads on a device or emulator, so the
actor is tested there (the A1 exit criterion).

## BLE link layer (`:core:ble`, phase A2)

Package `org.crisisnetwork.android.core.ble`. Implements `Transport` over Bluetooth Low Energy; the
radio profile (UUIDs, setup order, packet mapping) is in `docs/src/protocol/ble-link.md`.

| Part | What it does |
| --- | --- |
| `BleScanner`, `BleAdvertiser`, `GattServerPort`, `GattConnector`, `GattLink` | Ports: everything that touches the Bluetooth stack sits behind them, so the state machine is tested with fakes. |
| `BleTransport` | The state machine, one actor like `NodeRuntime`. Reports a link to the core (`PeerSeen`) only when it is usable and its MTU carries the core's minimum; assigns handles that are never reused; one bounded send queue and one send in flight per link; `PeerLost` exactly once; counters and `linkInfo()` snapshots for diagnostics. |
| `android/*` | Thin adapters over `BluetoothLeScanner`, `BluetoothLeAdvertiser`, `BluetoothGattServer` and `BluetoothGatt`, with the API 33 / older API split for reads, writes and notifications. `BleSupport` builds the transport and answers "which permissions, is Bluetooth on". |
| `BleConfig` | Role (`Central`, `Peripheral`, `Both`), minimum MTU (pass `core_info().min_link_mtu`), requested ATT MTU, link limit, timeouts, queue size. |

Decisions:

* One core packet = one write or notification, no framing; the link MTU is the ATT MTU minus 3.
* The central enables notifications only after the MTU is settled, so the peripheral knows the MTU
  when the link becomes ready.
* Android 14 and newer negotiate the MTU themselves on connect; the adapter skips its own request
  when the stack already reported one, and waits for the callback otherwise.
* `gatt.close()` is always called; leaked client interfaces make new connections fail.
* Manifest: `BLUETOOTH_SCAN` with `neverForLocation` (no location permission on Android 12+),
  `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`; the legacy permissions are limited to `maxSdkVersion=30`.
  Android 11 and older also need the system location switch on for scanning to return anything.
* Not in A2 (phase A3): tie-break between two phones that connect to each other, rotating
  advertisement token, connection rotation and limits by power profile, back-off per GATT error,
  the real permission flow, the foreground service, screen-off scanning.

### Tests

```
./gradlew :core:ble:testDebugUnitTest      # JVM: state machine against fakes, queue, handles, permissions
```

### Exit test on two real phones

Not run here (no devices). Procedure:

1. Install the debug APK on two phones (Bluetooth on; on Android 11 or older also location).
2. Open **BLE link test (A2)** on both. Phone 1: role **Peripheral**, phone 2: role **Central**
   (`Both` connects twice until A3). Tap **Start** and grant the permissions.
3. Within a few seconds each phone shows one link with a handle and the packet size; the log shows
   `link up`. Write down the packet size (ATT MTU is that plus 3).
4. **Send** an announcement on one phone: it must appear in the other phone's log, and the other way.
5. Tap **Reset counters** on both, then **Burst** on one phone (10 announcements of 1000 B). On the
   other phone read the "Received" line (payload bytes per second between first and last). The
   link rows show packets and bytes sent/received and the sending throughput on each side.
6. Repeat for the other direction (it uses the other GATT characteristic) and, if possible, with
   the phones at 1 m and at 5 m.

Results (fill in):

| Phones (model, Android) | Packet size / ATT MTU | Burst payload B/s | Link tx B/s | Notes |
| --- | --- | --- | --- | --- |
| | | | | |

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
* BLE test bench finds nothing: Android 11 and older need the system location switch on; on all
  versions Bluetooth must be on and the permissions granted. `ble: ScanFailed(2)` means the app
  started too many scans in 30 s: wait and press Start again.
* Resource names must be lowercase with underscores: `data_extraction_rules.xml`, not
  `data-extraction-rules.xml` (aapt rejects hyphens).
