# Crisis Network: Android client

Kotlin and Jetpack Compose client of the Crisis Network core. The core is the Rust crate
`ffi` (library `crisis_core`); this project builds it for Android with cargo-ndk and calls it
through the UniFFI-generated Kotlin bindings.

Status: **phase A6** (network status, storage and lifetime settings, notifications; contacts, chats and
announcements from A5, design system and shell from A4, BLE and the foreground service from A2/A3).
Phase A7 (hardening and release) follows.

## Toolchain (versions checked on 2026-10-18)

| Component                                 | Version                      | Why                                                                                |
|-------------------------------------------|------------------------------|------------------------------------------------------------------------------------|
| Android Gradle plugin                     | 9.4.0                        | Latest stable. Built-in Kotlin: there is no `org.jetbrains.kotlin.android` plugin. |
| Gradle (wrapper)                          | 9.6.0                        | AGP 9.4 requires 9.6.0 or newer.                                                   |
| Kotlin                                    | 2.4.10                       | Latest stable; also the Compose compiler plugin version.                           |
| JDK                                       | 17 or newer                  | AGP 9.4 minimum. Android Studio's bundled JDK is fine.                             |
| compileSdk / targetSdk / minSdk           | 37 / 36 / 26                 | androidx.core 1.19 needs compileSdk 37; targetSdk is raised deliberately later.    |
| SDK Build Tools                           | 36.0.0                       | AGP 9.4 default (Android Studio installs it).                                      |
| NDK                                       | 28.2.13676358 (r28c)         | AGP 9.4 default; 16 KB page aligned.                                               |
| Compose BOM / activity-compose / core-ktx | 2026.09.00 / 1.13.0 / 1.19.1 | Latest stable.                                                                     |
| kotlinx-coroutines / JNA                  | 1.11.0 / 5.19.1              | Latest stable.                                                                     |
| Rust                                      | stable, 1.93 or newer        | Workspace `rust-version`.                                                          |

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

| Property                                         | Default   | Meaning                                                                                                                               |
|--------------------------------------------------|-----------|---------------------------------------------------------------------------------------------------------------------------------------|
| `crisis.rustProfile` (alias `crisisRustProfile`) | `release` | `release` (LTO, small) or `dev` (much faster Rust builds while iterating).                                                            |
| `crisis.sqlcipher` (alias `crisisSqlcipher`)     | `false`   | Builds the core with SQLCipher so the database can be encrypted. The instrumented test then asserts that encryption is really active. |
| `crisis.skipRust` (alias `crisisSkipRust`)       | `false`   | Skips cargo and reuses libraries and bindings that are already built.                                                                 |
| `crisis.abis` (alias `crisisAbis`)               | all three | Comma separated subset of `arm64-v8a,armeabi-v7a,x86_64`, e.g. `-Pcrisis.abis=x86_64` for emulator-only builds (CI smoke job).        |

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
├── app/              application: theme, shell, onboarding, mesh service (A0 self-test now under Settings)
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

| Part                                                  | What it does                                                                                                                                                                                                                                                                                                                                                                                                  |
|-------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `NodeRuntime`                                         | Owns one `CrisisNode` as a **single-threaded actor**. Transport events, publish calls, queries and timer ticks all go through one mailbox and run one after another on the IO dispatcher (every core call blocks on SQLite). It ticks when the core's `next_timer_ms` is reached (and at least every 30 s), sends transport commands to the `Transport`, and publishes `CoreNotification`s on a `SharedFlow`. |
| `NodeBootstrap`                                       | Checks the interface version, unwraps seed and database key from the vault, opens the database under `noBackupFilesDir/crisis` and returns a `NodeRuntime` (not started).                                                                                                                                                                                                                                     |
| `MonotonicWallClock`                                  | The only time source the core sees (see below).                                                                                                                                                                                                                                                                                                                                                               |
| `SecretVault`, `AesGcmSecretCipher`, `KeystoreAesKey` | Seed and database key sealed with a non-extractable Android Keystore AES-256-GCM key (StrongBox when available).                                                                                                                                                                                                                                                                                              |
| `CoreFailure`                                         | Stable mapping of `CoreException` (`DatabaseUnreadable` for `WrongKeyOrNotADatabase`, `SchemaTooNew`, `EncryptionUnavailable`, ...). `InternalError` and `Unexpected` are fatal and stop the runtime.                                                                                                                                                                                                         |
| `Transport`, `LoopbackNetwork`                        | What A2/A3 implement for BLE; the loopback connects two nodes in memory (with a real MTU limit) for tests and previews.                                                                                                                                                                                                                                                                                       |

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

| Part                                                                         | What it does                                                                                                                                                                                                                                                                                                                         |
|------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `BleScanner`, `BleAdvertiser`, `GattServerPort`, `GattConnector`, `GattLink` | Ports: everything that touches the Bluetooth stack sits behind them, so the state machine is tested with fakes.                                                                                                                                                                                                                      |
| `BleTransport`                                                               | The state machine, one actor like `NodeRuntime`. Reports a link to the core (`PeerSeen`) only when it is usable and its MTU carries the core's minimum; assigns handles that are never reused; one bounded send queue and one send in flight per link; `PeerLost` exactly once; counters and `linkInfo()` snapshots for diagnostics. |
| `android/*`                                                                  | Thin adapters over `BluetoothLeScanner`, `BluetoothLeAdvertiser`, `BluetoothGattServer` and `BluetoothGatt`, with the API 33 / older API split for reads, writes and notifications. `BleSupport` builds the transport and answers "which permissions, is Bluetooth on".                                                              |
| `BleConfig`                                                                  | Role (`Central`, `Peripheral`, `Both`), minimum MTU (pass `core_info().min_link_mtu`), requested ATT MTU, link limit, timeouts, queue size.                                                                                                                                                                                          |

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
* Phase A3 (below) adds discovery with a tie-break, back-off, scan budget, link rotation, power
  profiles, the permission flow and the foreground service.

### Tests

```
./gradlew :core:ble:testDebugUnitTest      # JVM: state machine against fakes, queue, handles, permissions
```

### Exit test on two real phones

Not run here (no devices). Procedure:

1. Install the debug APK on two phones (Bluetooth on; on Android 11 or older also location).
2. Open **BLE link test** on both (Settings, Developer tools; debug builds only). Phone 1: role **Peripheral**, phone 2: role **Central**
   (since A3 `Both` on both phones also works: they agree on one link). Tap **Start** and grant
   the permissions.
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
|-------------------------|-----------------------|-------------------|-------------|-------|
|                         |                       |                   |             |       |

## Design system, shell and onboarding (phase A4)

Package layout of `:app` (`ui/`):

| Package            | Content                                                                                                                                                               |
|--------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ui.theme`         | `ThemeSettings` (mode, dynamic colour, high contrast, text scale), brand colour schemes, `CrisisTheme`, `StatusColors` for the announcement styles of A5.             |
| `ui.components`    | `ScreenColumn`, `ScreenTitle`, `InfoCard` (with `Tone`), `PrimaryButton` / `SecondaryButton` / `QuietButton`, `StatusPill`, `EmptyState`, `SwitchRow`, `ChoiceGroup`. |
| `ui.shell`         | `Destination` (Home, Chats, Announcements, Contacts, Settings) and `AdaptiveShell`.                                                                                   |
| `ui.permissions`   | `rememberMeshStartFlow` + `BlockerCard`: the permission / Bluetooth / location flow of the A3 bench, reusable.                                                        |
| `ui.onboarding`    | `OnboardingFlow` (rules, tested), `IdentityCreator`, `OnboardingScreen`.                                                                                              |
| `ui.screens`       | Home, Settings, Diagnostics (the A0 self-test), placeholders.                                                                                                         |
| `ui.UiPreferences` | Appearance choices, introduction completed, public identity values.                                                                                                   |

**Theme.** Colour: wallpaper colours on Android 12+ (switch), else the brand palette; light, dark,
AMOLED (true black surfaces) or system; *high contrast* (black/white, strong outlines) replaces the
wallpaper colours because their contrast cannot be guaranteed. Text: the user's size (1.0, 1.15, 1.3,
1.5) multiplies the system font scale. The system bar icons follow the app's own day/night choice.

**Components** keep touch targets at 48 dp or more, wrap text instead of cutting it, expose roles
(switch, radio button, heading) to TalkBack and never use colour alone (every tone has a label).

**Adaptive navigation.** Compact windows (< 600 dp): navigation bar at the bottom. Medium and
expanded (>= 600 dp): navigation rail at the start. The width is the window's, so split-screen and
unfolding switch the layout. Content is limited to 640 dp. No navigation library: five top-level
destinations with saved state; A5 adds the screens behind them.

**Introduction** (first launch, five steps): welcome, **create identity**, nearby-devices permission
with its reasons, background and battery, done (join now or open the app).

* The identity is created by opening the node once without a radio (`NullTransport`): the core
  generates the key pair from a fresh seed which the Keystore-backed vault seals (A1). The safety
  number and the node id are public and cached in `UiPreferences` for display. There is no display
  name in the core; the nickname is local and arrives with the contacts in A5.
* A vault that cannot be unlocked offers an explicit, confirmed **reset** (deletes the identity and the
  stored data), nothing is reset silently.
* Permissions: reasons first, then the system dialog; after "don't ask again" a shortcut to the app
  settings; Bluetooth off and the Android 11 location switch are handled. The permission step can be
  skipped; the home screen asks again when joining.
* Battery: explains the foreground notification and Doze, shows whether the app is excluded from
  battery optimisation (`isIgnoringBatteryOptimizations`, no permission needed) and opens the system
  list (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`). The app does **not** request the exemption
  by itself: Google Play restricts `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
* A killed process resumes after the identity step if the identity exists, so a second identity is
  never created. Debug builds can show the introduction again (Settings, Developer tools).

Tests: `app/src/test` (`OnboardingFlowTest`, `ThemeSettingsTest`: step rules, safety number format,
theme decisions, width classes); CI runs `:app:testDebugUnitTest`.

Not in A4: a fake repository for previews (the Compose tooling dependency is not in the project).

### Phase A6: network status, settings, notifications

* **Network screen** (`ui.network.NetworkScreen`, overlay `network`): neighbours from
  `MeshController.State.links`, the radio from `BleStats`, storage and counters from
  `NodeRuntime.stats()` (polled every 2 s while the node runs and `AppVisibility.visible` is true).
  A Bluetooth link says nothing about who is on the other side, so neighbours are numbered and shown
  with the end of their (usually random) address.
* **Core settings.** `settings.StorageLimits` / `DefaultLifetimes` hold the choices; `MeshController`
  stores them in `SharedPreferences` and builds `NodeSettings` in `open()`. The core takes them only
  at open, hence "applies the next time the mesh starts" (`State.settingsPending`). The share of the
  store that others may pin stays a quarter, as in the core's defaults.
* **Notifications.** `notifications.AppNotifications` (channels, permission state),
  `MessageNotifier` (fed by `MeshService` from `MeshController.notifications`), `AlertGate` (one
  sound per burst), `NotificationAccessPlan` (dialog or settings). Taps become a `LaunchTarget` in
  `LaunchRequests`, which the shell consumes. Channel ids: `mesh_service` (unchanged),
  `direct_messages`, `announcements`.
* Tests: `NetworkSettingsTest`, `NotificationLogicTest`, `OverlayStackTest`.

## Discovery, topology and the foreground service (phase A3)

### Radio behaviour (`:core:ble`)

| Topic          | Behaviour                                                                                                                                                                                                                                                                                                                                                               |
|----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Advertisement  | Service UUID plus manufacturer data (company id `0xFFFF`): 1 flag byte (`can initiate`) and a **random 32-bit token**, replaced every 10 minutes. 30 of 31 bytes. Format: `BleAdvertisement`.                                                                                                                                                                           |
| Tie-break      | Of two phones that both scan and advertise, the one with the **smaller token initiates**; the other waits. A peer without a token, or one that cannot initiate (`Peripheral`), is always connected to; a device that advertises no token always initiates. If we have no link at all for 30 s we also connect to devices we were leaving alone.                         |
| Connecting     | One attempt at a time (`maxConcurrentConnects`), optional spacing (the app uses 300 ms), strongest signal first. One slot of the link limit is kept free for incoming connections (limit 3 or more).                                                                                                                                                                    |
| Back-off       | Per address: 5 s, doubled for every failure in a row, up to 60 s (GATT 133, timeouts). A device that lacks our service waits at least 30 s. A link that lived 20 s starts clean; a link that flaps counts as a failure. Three failures in a row (any address) pause all new connections for 5 s.                                                                        |
| Scan budget    | Android allows 5 scan starts per 30 s: the transport stays at 4 and delays restarts. `ScanFailed(6)` (too frequent) waits 30 s, other codes back off from 5 s. A scan is refreshed every 25 minutes (Android demotes long scans).                                                                                                                                       |
| Link rotation  | Only when the limit is reached **and** a neighbour is waiting: once per rotation interval the oldest *outgoing* link (older than 1 min) is closed and its address left alone for 1 min. Incoming links are never dropped from this side.                                                                                                                                |
| Power profiles | `PowerProfile.Saver` / `Balanced` / `Aggressive` set scan mode (screen on, screen off), advertise mode, link limit (3 / 5 / 7), connection interval class and rotation interval. They change at run time (`setPowerProfile`), as does the screen state (`setScreenOn`). The scan is always filtered on the service UUID, which is what lets it run with the screen off. |

`BleTransport.stats()` returns a `BleStats` snapshot (profile, scan mode, token, candidates, held by
tie-break, back-off, counters) for the diagnostics screen (A6).

### App (`:app`)

* `MeshService`: foreground service, type `connectedDevice` (permissions `FOREGROUND_SERVICE` and
  `FOREGROUND_SERVICE_CONNECTED_DEVICE`), persistent notification with the neighbour count, the
  profile and a **Stop** action. Start it from a visible screen only (Android 12+ forbids starting
  a foreground service from the background). After a restart by the system it comes back only when
  the user had switched the mesh on and Android allows it.
* `MeshController`: owns the node and the transport, remembers role and profile, follows the screen
  state, publishes `state`. The service and the screens only observe it.
* Permission flow (`BleSupport.readiness`): no BLE, then missing permissions (Android 12+: Nearby
  devices; 11 and older: location), then Bluetooth off, then the location switch (11 and older).
  The bench shows a rationale before the system dialog, a shortcut to the app settings after
  "don't ask again", and asks for `POST_NOTIFICATIONS` (Android 13+, optional) once.
* Strings for the rationale and the notification are in `values/` and `values-tr/`; the A4 onboarding
  reuses them.

### Tests

```
./gradlew :core:ble:testDebugUnitTest      # includes DiscoveryTest and BleAdvertisementTest
```

### Exit test: three phones, relay A to C, and the battery

Not run here (no devices).

**Relay A - B - C.** Three phones with the debug APK, permissions granted:

1. Phone A and C: role **Central**. Phone B: role **Both**. (A and C advertise nothing, so they
   only connect to B and never to each other: the chain is forced by the roles.)
2. Start the mesh on B first, then on A and C (**Start** in *BLE link test*). B shows two links.
3. **Send** on A: the announcement must appear in C's log (and the other way). Note the time from
   Send to arrival.
4. Move A out of reach of C if you want a physical chain; the result must be the same.
5. Two phones in `Both`: they must end with **one** link (check *Links* on both), with the smaller
   token as the initiator (`BleStats`: `held by tie-break` on the other one).
6. Switch the profile on one phone while linked: scan/advertise state in the stats line changes
   at once and the link stays up.

**Battery, screen off.** Per profile, one phone with the mesh running and one neighbour linked,
screen off, unplugged, 60 minutes:

```
adb shell dumpsys batterystats --reset
# ... run 60 minutes, screen off, USB unplugged (adb over Wi-Fi) ...
adb shell dumpsys batterystats --charged org.crisisnetwork.android | grep -i -E "bluetooth|Estimated power|Uid"
adb shell dumpsys bluetooth_manager | grep -i -E "scan|advert"
```

Results (fill in):

| Phones | Profile | Screen | Neighbours | Drain %/h | BLE scan time | Notes |
|--------|---------|--------|------------|-----------|---------------|-------|
|        |         |        |            |           |               |       |

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
  versions Bluetooth must be on and the permissions granted. `ble: ScanFailed(6)` means the app
  started too many scans in 30 s (the transport waits and retries by itself since A3);
  `ScanFailed(2)` is a failed scanner registration, retried with back-off.
* `Cannot run in the foreground` in the bench: Android 14 and newer need a Bluetooth permission
  before a `connectedDevice` service starts, and Android 12 and newer refuse a start from the
  background. Start from the visible screen after granting the permissions.
* Two phones in `Both` do not link: the smaller token initiates; the other must see the first
  one's advertisement. If neither scan sees the other, the fallback connects after 30 s of isolation.
* Resource names must be lowercase with underscores: `data_extraction_rules.xml`, not
  `data-extraction-rules.xml` (aapt rejects hyphens).
