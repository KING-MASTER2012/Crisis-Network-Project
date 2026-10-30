# Changes: Android phase A6 (network status, settings, notifications)

Written without an Android toolchain: **nothing here was compiled, run or tested on a device**. Build
(`./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`), then try it with two phones
with the app in the background. No change in the core or in the Rust workspace.

* **Network status screen** (Home: "Network details", Settings: "Network status and counters").
  Connected neighbours with direction, traffic, packet size, queue and drops; devices in range that
  are not connected; message storage used against the limit; the Bluetooth radio (`BleStats`); and
  all counters of the core (`NodeStats`), grouped as traffic / refused or dropped / delivery trouble.
  Counters are read every 2 s, only while the node runs and the app is on screen.
* **Settings.** Size of the message store (8 to 256 MB) and the default lifetime of announcements.
  The core reads both only when the node is opened (`NodeSettings`), so a change is remembered at
  once and applies the next time the mesh starts; the screen says so while a running node still has
  the old values. The composer preselects the chosen lifetime. New "About" section: app version and
  `core_info()` (core version, FFI API, protocol, database schema).
* **Notifications.** Three channels: `direct_messages`, `announcements` and the unchanged
  `mesh_service`. New messages produce a notification only while the app is not on screen: one per
  person in messaging style, one for all announcements (count, emergency title). The lock screen
  shows only that something arrived. Only the first notification of a burst makes a sound. A tap
  opens that chat or the announcements; opening them removes the notification.
* **POST_NOTIFICATIONS flow.** Settings shows whether notifications work, with a button that shows
  the system dialog while the system still does and opens the notification settings otherwise
  (also when the switch is off although the permission is held). Home warns while the mesh runs
  with notifications off.

Files: new `ui/network/*`, `ui/screens/NotificationsSettings.kt`, `notifications/*`, `settings/NodeLimits.kt`,
`AppVisibility.kt`, `ui/shell/LaunchTarget.kt`, three test classes; changed `MeshController`, `MeshService`,
`MainActivity`, `Overlay`, `HomeScreen`, `SettingsScreen`, `ComposeAnnouncementScreen`, `Messaging`,
`MeshStartFlow`, both `strings.xml`, `OverlayStackTest`, `clients/android/README.md`.

---

# Changes: Android phase A5 (contacts, chats, announcements)

Written without an Android toolchain: **nothing here was compiled, run or tested on a device**. Build
(`./gradlew :app:assembleDebug :app:testDebugUnitTest`), then try it with two phones. Needs the C7
change below (core API version 2 and regenerated bindings).

* **Contacts.** Show your own card as a QR code (and copy or share it as text), scan another phone's
  QR code, or enter a card as text. Adding a card shows the safety number to compare and asks for a
  nickname. A contact detail screen compares safety numbers, marks a contact verified (or removes it)
  and blocks or unblocks. A card scanned while the mesh is off is kept and added when the node runs.
* **Chats.** List of conversations (preview, time, unread count), the chat screen with backward
  paging, a "new messages" line, "Published" / "Delivered" under sent messages, a UTF-8 byte counter
  against the 976-byte limit of a private message, and a choice of contact for a new message.
* **Announcements.** Feed (newest first, backward paging, unread line), composer with a byte counter,
  importance (normal / important / emergency, shown with a word and a colour), and a lifetime from
  1 second to 7 days (presets and a custom value). Important and emergency ones ask for confirmation.
* **Unread badges** on the Chats and Announcements destinations; read state lives in the core (C7).
* **Convention to share with the other clients:** an important or emergency announcement starts its
  payload with the bytes `0x1E` and the ASCII digit `1` or `2`, then the UTF-8 text; a normal one is
  plain text (see `AnnouncementCodec`). Text from other phones is cleaned of control characters and
  bidirectional override characters before it is shown.
* **QR without Google Play services:** ZXing (plain Java) draws and reads the codes, CameraX delivers
  the frames. This replaces the bundled ML Kit model of the plan: no native library is added, so
  nothing new needs the 16 KB alignment check. A contact card is tiny (QR version 8, 49 modules).
  Dependencies added: `com.google.zxing:core:3.5.3` and `androidx.camera:*:1.4.2`; check for newer versions.
* New permission `CAMERA` (and optional camera features in the manifest); the camera is asked for
  only on the scan screen, with the reason first.
* `NodeRuntime` gained `conversations`, `conversationHistory`, `markConversationRead`, `unreadCounts`;
  `MeshController.dataVersion` tells screens that messages changed; the contact card text is stored
  with the identity (`UiPreferences`) so that the QR code is shown without opening the node.
* Known limits, left for A6/A7: messages can only be read and sent while the mesh runs (the node is
  opened by the foreground service only); nicknames are plain app-private preferences (not encrypted);
  no notifications for incoming messages yet (A6); the card is shown as text and QR only.
* Tests (JVM): card codec and QR round trip, announcement codec and sanitizing, limits and lifetimes,
  nicknames, hex, overlay stack.

# Changes: core phase C7 (conversations: paging, unread, delivery state)

Written without a Rust toolchain: **the Rust sources were not compiled or run here**. Run
`cargo fmt --all`, `cargo clippy --workspace --all-targets -- -D warnings` and
`cargo test --workspace`, then rebuild the Android bindings (`gen-bindings.sh`).

* Storage schema **version 3** (migration is automatic, existing data is kept): indexes for
  per-conversation queries, `message_notes` (removed together with its message by a foreign key)
  and `read_marks` (one per conversation; the announcements use the single byte `0` as scope).
* `storage`: `conversation_messages` (backward paging, oldest first within a page, `more_before`),
  `conversations` (list with last position, message count, unread), `read_mark` / `mark_read`
  (never moves backwards), `unread_counts`, `set_plaintext` / `mark_delivered` / `note`.
* `engine`: `conversations`, `conversation`, `mark_read`, `unread_counts`. `HistoryEntry` gained
  `delivered_at_ms`. `Engine::handle` records `Notification::Delivered` in the store.
* **Behaviour change:** the text of a direct message the user sent is now kept next to the stored
  (sealed) message, so the history and the chat show it. The copy lives in the same database,
  under the same at-rest encryption, and is deleted with the message when it expires, is evicted
  or is deleted. Messages sent before this version stay unreadable on the sender's side. A crash
  between storing a message and storing its text leaves that one message without text.
* `ffi`: new `conversations`, `conversation_history`, `mark_conversation_read`, `unread_counts`;
  new types `ConversationRef` (`Announcements` / `Direct { peer }`), `ConversationInfo`,
  `ConversationPageInfo`, `UnreadInfo`; `HistoryItem.delivered_at_ms`.
  **`FFI_API_VERSION` is now 2** (the Android `EXPECTED_FFI_API_VERSION` follows).
* Tests: `storage/tests/conversations.rs`, migration tests for version 3 (the old tests now build on
  the real migration list), new engine and FFI end-to-end tests; the engine test that expected a
  sealed outgoing body now expects the text.
* Not changed: Kotlin wrappers for the new calls (phase A5), the CLI, the protocol and the wire format.

# Changes: Android phase A4 (design system, shell, onboarding)

Written without a Kotlin or Android toolchain: **the Android sources were not compiled or run
here**. Run `./gradlew :app:assembleDebug :app:testDebugUnitTest` and try the app on a phone and a
tablet (or the foldable / resizable emulator).

* Theme: `ThemeSettings`, brand colour schemes (light, dark, AMOLED, high contrast light/dark),
  wallpaper colours on Android 12+, text scale on top of the system font scale, `StatusColors`.
* Components: `ScreenColumn`, `InfoCard`, buttons, `StatusPill`, `EmptyState`, `SwitchRow`,
  `ChoiceGroup` and the navigation icons (vector drawables).
* Shell: five destinations; bottom bar on compact windows, rail from 600 dp; edge-to-edge with bar
  icons that follow the in-app theme.
* Onboarding: welcome, identity creation (`IdentityCreator` + `NullTransport`, explicit reset on an
  unlockable vault), permissions with reasons (reused from the A3 bench as `rememberMeshStartFlow`),
  background and battery explanation, done. Strings in English and Turkish.
* Home (join / leave the mesh, neighbours), Settings (appearance, power profile, identity,
  developer tools), Diagnostics (the A0 self-test moved here). `MainActivity` is now the host of
  theme, onboarding and shell.
* `:app` unit tests (`OnboardingFlowTest`, `ThemeSettingsTest`) and the CI step that runs them.
* Placeholders for Chats, Announcements and Contacts until A5.

# Changes: Android phase A3 (discovery, topology, power, foreground service)

Written without a Kotlin or Android toolchain: **the Android sources were not compiled or run
here**, and nothing was measured on real phones. Run `./gradlew :app:assembleDebug
:core:ble:testDebugUnitTest`, then follow "Exit test: three phones" in `clients/android/README.md`.

* `:core:ble`: advertisement with a rotating random token (`BleAdvertisement`) and the tie-break
  (smaller token initiates); candidate list with strongest-signal-first connecting, one attempt at a
  time, optional spacing; per-address exponential back-off and a pause after repeated failures
  (GATT 133); scan-start budget (4 per 30 s), failure handling by error code and a 25-minute scan
  refresh; link rotation under pressure with a free slot kept for incoming links; power profiles
  (`PowerProfile`) with run-time `setPowerProfile` / `setScreenOn` (scan mode, advertise mode, link
  limit, connection interval); `BleStats`; `BleSupport.readiness`.
* Ports changed: `BleScanner.start(mode)`, `BleAdvertiser.start(mode, advertisement)`,
  `ScanEvent.Sighting` carries the advertisement, `GattLink.requestPriority` (default no-op).
  `BleConfig.maxLinks` is now a ceiling above the profile's limit (default 8).
* App: `MeshService` (foreground service, type `connectedDevice`, persistent notification with a
  Stop action), `MeshController` (owns the node; remembers role and profile; follows the screen),
  permission flow with rationale / settings shortcut / Bluetooth and location prompts, notification
  channel and strings (en, tr). The test bench now starts the mesh through the service and shows the
  new state; `BleLinkTestController` only sends and counts.
* Tests: `DiscoveryTest`, `BleAdvertisementTest`; the fakes follow the new ports and the harness
  stops the transport when the test scope ends.
* Not changed on purpose: `docs/src/protocol/ble-link.md` still says the tie-break is specified in
  A3; the mdBook pages are updated at the end of the project (the advertisement format is in the
  KDoc of `BleAdvertisement` until then).

# Fix: `Unresolved reference 'cancel'` in `BleTransport.kt`

* Added the missing `import kotlinx.coroutines.cancel` (`CoroutineScope.cancel()` is an extension).
  Failed `:core:ble:compileDebugKotlin` in both Android CI jobs.

# Changes: Android phase A2 (BLE link layer)

Written without a Kotlin or Android toolchain: **the Android sources were not compiled or run
here**, and nothing was measured on real phones. Run `./gradlew :app:assembleDebug
:core:runtime:testDebugUnitTest :core:ble:testDebugUnitTest`, then follow "Exit test on two real
phones" in `clients/android/README.md` and fill in its results table.

* New module `:core:ble` with `BleTransport`, the `Transport` of the core over BLE: ports for
  scanner, advertiser, GATT server, connector and link; handle allocator (never reused); bounded
  per-link send queue with one send in flight; MTU and handshake rules; `PeerSeen` only for usable
  links; counters and snapshots for diagnostics. The state machine has no Android dependency.
* Android adapters for the platform APIs (`android` package), including the API 33 / older split
  and an address-to-device cache for scan results; `BleSupport` for permissions and construction.
* Library manifest with the BLE permissions and feature (`neverForLocation` for scanning).
* App: development test bench (`BleLinkTestActivity`, opened from the self-test screen) that runs
  the real node over real Bluetooth and shows links, MTU and throughput. English only.
* Docs: `docs/src/protocol/ble-link.md` (the GATT profile); README section with the exit-test
  procedure; CI runs `:core:ble:testDebugUnitTest`.
* Not yet (A3): tie-break between two phones connecting to each other, rotating token, link
  rotation and limits, back-off per GATT error, permission flow, foreground service, screen-off scan.

# Fix: invalid resource file name (app)

* `app/src/main/res/xml/data-extraction-rules.xml` -> `data_extraction_rules.xml`. Android resource
  file names may only contain a-z, 0-9 and `_`; the hyphen failed `:app:packageDebugResources`
  in both Android CI jobs. The manifest already referenced `@xml/data_extraction_rules`.

# Fix: the Gradle wrapper script called itself (root cause of the CI hangs), cargo-deny

* `clients/android/gradlew` was the "alias" script (`exec "$(dirname "$0")/gradlew" "$@"`), which
  runs itself in an endless loop: every `./gradlew ...` on Linux/macOS hung without any output,
  until the 6 h / 40 min limits killed it. It is now the real POSIX wrapper script (it starts
  `gradle/wrapper/gradle-wrapper.jar`). There is no `gradlew.sh`.
* `deny.toml`: ignore RUSTSEC-2026-0374 (`basic-toml` unmaintained, reached only through
  uniffi_bindgen/askama at build time) so that `cargo deny check` passes.
* The previous entry's guess that the slow Rust build was the cause was wrong; its timeouts,
  step split and `dev` profile stay because they make a future hang fail fast and cheaply.

# Changes: CI timeouts (android, android-smoke)

Not run here (no Android/Kotlin toolchain, no network): verify on the next CI run.

* `android` hung for 6 h in the Gradle step; `android-smoke` hit "Timeout waiting for emulator to boot".
  Every job now has `timeout-minutes`, the Android Gradle step is split (native core, APK, lint, unit
  tests) with a limit per step, and reports are uploaded on failure.
* Both Android jobs run on `ubuntu-24.04`, free disk space first, and build with the `dev` Cargo
  profile (no fat LTO); the smoke job builds only `x86_64`, builds the APKs before the emulator
  starts, checks `/dev/kvm`, and uses `emulator-boot-timeout: 900`.
* `:core:ffi`: new `crisis.abis` option, `probe()` can no longer block, Exec tasks have timeouts,
  and the dot-free aliases now take precedence over `gradle.properties` (before, `-PcrisisRustProfile=dev`
  was silently ignored because `crisis.rustProfile=release` is set there).
* `:core:runtime`: unit test tasks have a 10 min limit and log each test.

# Changes: Android phase A1 (core runtime layer) and CI fix

Written without a Kotlin or Android toolchain: **the Android sources were not compiled or run
here**. Run `./gradlew :app:assembleDebug :core:runtime:testDebugUnitTest` and, on a device or
emulator, `./gradlew :core:runtime:connectedDebugAndroidTest`.

## CI

* `android` and `android-smoke`: `android-actions/setup-android@v3` failed with
  `Failed to find package 'tools'` because its default package list contains `tools`, which the
  current command-line tools no longer provide. The packages are now named explicitly
  (`platform-tools`, `platforms;android-37.0`, `build-tools;36.0.0`, `ndk;28.2.13676358`); the separate
  NDK step is gone.
* `android` and `android-smoke`: the next failure was `Failed to find package 'platforms;android-37'`.
  Since API 36 the platform packages carry a minor version suffix; Android 17 is published as
  `platforms;android-37.0` (and `android-37.1`). `compileSdk = 37` still resolves to it.
* `android` also runs `:core:runtime:testDebugUnitTest`; `android-smoke` also runs
  `:core:runtime:connectedDebugAndroidTest`.

## New module `:core:runtime`

See `clients/android/README.md`, section "Runtime layer": `NodeRuntime` (single-threaded actor,
`next_timer_ms` tick loop), `MonotonicWallClock`, `CoreFailure` mapping, Keystore-wrapped seed and
database key (`SecretVault`), `EncryptionPolicy`, `NodeBootstrap`, `Transport` and
`LoopbackNetwork`. JVM unit tests plus an instrumented loopback test.

# Changes: phase C6 (FFI and versioned core)

## Before you build

Written without a Rust toolchain: **nothing has been compiled or tested yet**. Run:

```text
cargo fmt --all
cargo test --workspace --all-features
cargo clippy --workspace --all-features -- -D warnings
scripts/gen-bindings.sh          # or: powershell scripts/gen-bindings.ps1
```

Things most likely to need a small fix on the first compile: the exact shape of UniFFI 0.32
macro output under `-D warnings` (for example `missing_docs`; if it complains about generated
items, set `missing_docs = "allow"` in `core/crates/ffi/Cargo.toml`), and the new tests.

## New crate `ffi` (library `crisis_core`)

A thin, versioned [UniFFI](https://mozilla.github.io/uniffi-rs/) layer over `engine`, for Kotlin
(Android) and Swift (iOS, macOS). Desktop (Tauri) can link the crate from Rust directly. It uses
UniFFI's proc-macro mode, so there is no UDL file.

* `CrisisNode` (object, thread-safe): `open` / `in_memory`; `node_id`, `contact_card`, `safety_number`;
  events `peer_seen`, `peer_lost`, `packet_received`, `tick` (each returns a `CoreOutput`);
  `publish_broadcast[_with_lifetime]`, `publish_direct`, `publish_direct_to_card`;
  `add_contact`, `contacts`, `set_trust`; `history`, `connected_peers`, `stats`.
* Free functions: `default_settings`, `generate_seed`, `generate_database_key`,
  `inspect_contact_card`, `core_info`, `ffi_api_version`, `require_api_version`.
* Data: ids and keys are byte arrays; neighbours are `u64` handles chosen by the transport;
  `CoreOutput { transport, notifications, next_timer_ms }` carries everything the platform has
  to do. Bluetooth, the clock and the secure key store stay on the platform side.
* `CoreError` is a stable error enum (for example `WrongKeyOrNotADatabase`,
  `EncryptionUnavailable`, `SchemaTooNew`, `UnknownContact`, `InvalidContactCard`,
  `VersionMismatch`); `detail` texts are for logs only. A key given to a build without SQLCipher
  fails instead of silently storing unencrypted data.
* The identity seed and the database key are zeroized after use on the Rust side.

## Versioning

* `FFI_API_VERSION` (now 1): bump on every incompatible change of the exported interface.
  Applications call `require_api_version(EXPECTED)` once at start-up.
* `core_info()` reports the crate version, API version, wire protocol version, database schema
  version (new `storage::schema_version()`), minimum link MTU and protocol limits.
* UniFFI also checks at load time that bindings and native library match.

## Tooling

* `ffi` has its own `[lints]` table: `unsafe_code = "allow"` because UniFFI's generated code needs
  it; nothing written by hand uses `unsafe`.
* `scripts/gen-bindings.sh` / `.ps1`: generate Kotlin and Swift bindings into `target/bindings`.
  They use an unstripped **debug** build (the release profile strips the symbols UniFFI reads);
  the generated code works with the release libraries of every platform.
* CI: new `bindings` job generates and uploads the bindings.
* Workspace `Cargo.toml`: comment updated. `storage` gets `schema_version()`.

## Tests

* `ffi/tests/end_to_end.rs`: two nodes wired through byte packets: direct message delivered,
  decrypted and acknowledged; broadcast; contacts and trust; bad input; settings; restart with the
  same database; history paging; secret generation; version check; use from several threads.
* Unit tests for error mapping, type conversions and versioning.

## Notes for the platform clients

* Call `tick` when `next_timer_ms` is reached; feed `peer_seen` again if the link MTU changes.
* Android: build `cdylib` per ABI (`cargo ndk`), place the Kotlin bindings with the `.so` files.
  Apple: build the `staticlib` per target and combine into an XCFramework.
* The mdBook documentation stays postponed until the whole project is finished.
