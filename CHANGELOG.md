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
