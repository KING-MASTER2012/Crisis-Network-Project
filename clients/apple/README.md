# Crisis Network for Apple (iOS, iPadOS, macOS)

SwiftUI clients on top of the Rust core. Plan and phases: `I0` ... `I9` (see `CHANGELOG.md`). This folder holds the
result of **phase I0** (the toolchain, the core as a Swift package, a placeholder app, CI), **phase I1** (the
runtime around the core), **phase I2** (the Bluetooth link), **phase I3** (discovery, topology, the background) and
**phase I4** (design system, shell, introduction). Chats, announcements and contacts are still placeholders (I5, I6).

```
clients/apple/
  Packages/CrisisCore/     Swift package: the Rust core (XCFramework), its UniFFI bindings, CoreSelfCheck
  Packages/CrisisRuntime/  Swift package: clock, secrets (Keychain), storage layout, NodeRuntime, transport seam
  App/Shared/              the app (iOS and macOS share it)
  Tests/iOS/               tests that run inside the iOS app on the simulator
  Config/                  Base.xcconfig (shared), Local.xcconfig (yours, not committed)
  project.yml              XcodeGen specification of the Xcode project
scripts/build-apple.sh     builds the core for Apple platforms and fills the package
```

## What you need

* A Mac with **Xcode 16 or newer** (Swift 6 toolchain), the command line tools and `brew install xcodegen`.
* Rust (`rustup`); the script adds the Apple targets by itself.
* For a **real iPhone**: an Apple developer team (a free account is enough to install on your own phone; TestFlight
  and the App Store need the paid programme) and a device cable. The simulator needs none of this, but it has no
  Bluetooth: the Bluetooth phases (I2 and on) can only be proven on real devices.

Minimum systems: **iOS 17** and **macOS 14** (set in `Package.swift`, `Config/Base.xcconfig` and
`scripts/build-apple.sh`; change all three together).

## First run

```sh
# 1. The core: static libraries, XCFramework, Swift bindings (a few minutes the first time).
bash scripts/build-apple.sh             # dev profile, arm64 slices; add --sqlcipher for the encrypted database

# 2. The Swift package tests on the Mac.
swift test --package-path clients/apple/Packages/CrisisCore
swift test --package-path clients/apple/Packages/CrisisRuntime

# 3. The Xcode project, then open it.
cd clients/apple && xcodegen generate && open CrisisNetwork.xcodeproj
```

Run the `CrisisNetwork-iOS` scheme on a simulator or `CrisisNetwork-macOS` on the Mac: the app shows the
**self-test** (`CoreSelfCheck`): the core loads, identities and conventions work, two nodes exchange a broadcast, a
private message and its acknowledgement through an in-memory link, and (with `--sqlcipher`) the encrypted database
stores, reopens and refuses a wrong key. All green on your phone is the exit test of I0.

### On a real iPhone

```sh
cp clients/apple/Config/Local.xcconfig.example clients/apple/Config/Local.xcconfig
# edit it: DEVELOPMENT_TEAM = <your team id>, and APP_BUNDLE_ID = <something unique to you>
```

Then regenerate the project (`xcodegen generate`), pick your phone and run. The first time, the phone asks you to
trust the developer (Settings > General > VPN & Device Management).

## The runtime (phase I1)

`CrisisRuntime` is the Swift counterpart of the Android module `core:runtime`, with the same contracts:

| Piece | What it does |
| --- | --- |
| `NodeBootstrap` | Checks the interface version, prepares the storage directory, takes the seed and the database key from the vault, opens the database, returns a `NodeRuntime`. `EncryptionPolicy`: `required` (release), `ifAvailable` (debug), `disabled` (tests). |
| `NodeRuntime` (actor) | Owns the node. Transport events, calls and timer ticks run one after another. Routes the core's output: packets to the `Transport`, notifications to `notifications()`. Errors become `CoreFailure`; a fatal one stops the runtime. |
| `Transport` | The seam to the radio (events in, `send`/`disconnect` out, same contract as on Android). `LoopbackNetwork` is the in-memory implementation for tests and previews; the Bluetooth one is `BleTransport` (I2, I3). |
| `SecretVault` + `KeychainSecretStore` | Seed and database key in the Keychain (`AfterFirstUnlockThisDeviceOnly`: readable while the phone is locked, never copied to another device or to a backup). An item that exists but cannot be read is **never overwritten**; only an explicit `resetIdentity` clears it. |
| `StorageLayout` | `Application Support/Crisis`: excluded from backups, protection class `completeUntilFirstUserAuthentication` on iOS (the default `complete` would make background writes fail on a locked phone). |
| `MonotonicWallClock` | The clock the core sees: monotonic inside a process (`mach_continuous_time`, which counts sleep), adopts forward corrections, remembers a high-water mark across restarts (same rules as Android). |
| `TimerPolicy` | When to `tick` next (the core's deadline, an idle tick every 30 s, never faster than every 10 ms). |
| `CoreFailure` | The errors the app can react to, mapped from the core's `CoreError`. |

**A deleted app starts with a new identity.** On iOS Keychain items survive the deletion of the app, its files do
not. `NodeBootstrap` therefore discards secrets that have neither the install marker (`installed-v1`) nor a
database next to them. (Android needs no such rule: the Keystore key goes with the app.)

**Background.** iOS runs no timers while the app is suspended. In the background the runtime ticks when the
Bluetooth stack wakes the app (a sighting, a connection, a packet), for the few seconds iOS then allows; see
"Background and power" below.

**Keychain and signing in tests.** An unsigned app (`CODE_SIGNING_ALLOWED=NO`) has no application identifier, so on the
simulator every Keychain call fails with `-34018` ("a required entitlement isn't present"). The iOS target therefore
declares a `keychain-access-groups` entitlement (`App/iOS/CrisisNetwork.entitlements`, generated by XcodeGen) and CI runs
the simulator tests **ad-hoc signed** (`CODE_SIGN_IDENTITY=-`, no team or certificate needed). Building without
signing is still fine where nothing runs (macOS build, device slice).

`RuntimeSelfCheck` runs the clock, the Keychain, the bootstrap (restart and reinstall) and two runtimes talking over
the loopback; the app shows it under "Runtime", and the iOS test bundle runs it on the simulator.

## Bluetooth (phase I2)

`BleTransport` (in `CrisisRuntime/Sources/CrisisRuntime/Ble/`) is the `Transport` over Bluetooth Low Energy. It is the
Swift counterpart of the Android `BleTransport`: I2 brought the links, the handshake, the send queue, the back-off and
the removal of duplicates; I3 the tie-break, the power profiles, link rotation and the background.

```
BleTransport            state machine on one serial queue: links, handshake, send queue, back-off, duplicates
  Ports (BlePorts.swift)  BleScanner, BleAdvertiser, GattServerPort, GattConnector, GattLink
  CoreBluetoothCentral    CBCentralManager: scan, connect, CoreBluetoothClientLink (one per outgoing connection)
  CoreBluetoothPeripheral CBPeripheralManager: service, advertising, CoreBluetoothServerLink (one per subscribed central)
```

Everything that touches CoreBluetooth sits behind the ports, so the state machine is tested on the Mac with fakes
(`BleTransportTests`, no radio, no permission). The transport and both managers share **one queue**; every callback
arrives on it, and `send`/`disconnect` only enqueue.

**Profile.** It speaks **profile 1.1** of `docs/src/protocol/ble-link.md` (rewritten in A8): service `6f1d5a30-...`,
RX (write without response), TX (notify) and the HELLO characteristic `6f1d5a33-...` (read and write, nine bytes:
version `1` and a big-endian 64-bit identifier). The document also says what an Apple device does without a token.

**What iOS cannot do, and what the code does about it:**

| Android | iOS |
| --- | --- |
| Radio address | Only `CBPeripheral.identifier` (a UUID, stable on this device only). Links are keyed by it while they exist. |
| Token and flag in the advertisement | Not possible: only the service UUID can be advertised. An iPhone therefore advertises no token and connects to everything it sees; two devices that connected both ways close one link through the HELLO identifiers (same rule as Android: the link whose client has the lower identifier stays). |
| Request an MTU of 517 | The system negotiates; the code reads `maximumWriteValueLength(for: .withoutResponse)` (= ATT MTU - 3) and polls it at every tick, reporting a larger value again for the same neighbour. |
| Completion of a write | None for a write without response: it is complete when queued; `canSendWriteWithoutResponse` and `peripheralIsReady` are the flow control. A busy platform is asked again when it is ready. |
| Drop a connected central | No call for it. The server link goes quiet (nothing sent, writes ignored) until the central leaves; the other side's rules end the connection. |
| Connect/disconnect events for centrals | Only subscribe and unsubscribe of TX; a link exists from the subscription. |

The Bluetooth permission is asked the first time the transport starts. The background is the next section.

### Test bench and the exit test

The app has a **Bluetooth** tab (`BleLinkTestView`): pick a role, **Start**, and the node runs with the radio. It shows
the radio state, the links (role, handle, packet size, queue, counters), lets you send an announcement or a burst of
10 x 1,000 B and counts what arrives, like the Android `BleLinkTestActivity`. It needs real devices (the simulator has
no Bluetooth).

Exit test of I2, with both apps in the foreground and the screens on:

1. **iPhone and Android:** start both (role Both, or Central on one and Peripheral on the other). The Links section
   shows one usable link on each side; an announcement sent on one arrives on the other, in both directions.
2. **iPhone and iPhone:** the same. With role Both on both phones two links may appear for a moment; the "Duplicate
   links closed" counter goes up and one link stays.
3. A burst of 10 x 1,000 B arrives completely; note the rate from the other device's counters.

## Interface (phase I4)

```
App/Shared/
  Design/      Palette (colours, contrast), Theme (appearance settings, type scale), Components (cards, buttons, pills)
  Prefs/       UiPreferences (UserDefaults), AppLanguage and L10n
  Shell/       RootView, AppShell (tabs or sidebar), Destination
  Screens/     HomeView, SettingsView, PlaceholderView
  Onboarding/  OnboardingFlow (the rules), OnboardingView, IdentityCreator, BluetoothAccess
  Resources/   Localizable.xcstrings (en, tr)
```

**Design system.** The palette and the text sizes of the Android client (`ui/theme`), as `Palette`: light, dark, a
high-contrast version of each, and a black dark theme for OLED screens. The wallpaper colours of Android have no
counterpart. Every text/background pair is tested for contrast (4.5:1, 7:1 in high contrast) in
`DesignSystemTests`. The type scale uses Dynamic Type text styles, so everything follows the system setting; the app's
text size (normal, large, larger, largest) moves Dynamic Type up by 0 to 3 steps on top of it. Colour never carries a
meaning alone: cards and pills of a tone also have an icon.

**Shell.** `AppShell` shows a **tab bar** on an iPhone (compact width) and a **sidebar with a detail column** on an
iPad and a Mac (`NavigationSplitView`), with the five destinations of the Android client: Home, Chats, Announcements,
Contacts, Settings. Every destination has a **native `NavigationStack`**, so the back button and the swipe from the
edge are the platform's (no custom back handling, unlike Android's overlay stack). The selected destination is
remembered per scene.

**Introduction.** Welcome, identity, Bluetooth, background, done; the steps are pushed on a `NavigationStack` and the
rules (`OnboardingFlow`) are those of Android: only the identity blocks, only the Bluetooth step can be skipped, a
person who already has an identity starts at the Bluetooth step with the earlier steps on the back stack.

* **Identity:** `IdentityCreator` opens the node once with `NodeBootstrap.open` (a transport that does nothing), reads
  the safety number and node id, and closes it again; the seed and the database key are made and kept by the vault in
  the Keychain as before. A failure is shown in words with a reset where a reset helps (`secretsUnavailable(resettable)`).
* **Bluetooth permission:** `BluetoothAccess` reads `CBManager.authorization` and the power state, asks only when the
  button is pressed (the system dialog is the one with the reason in `NSBluetoothAlwaysUsageDescription`), and
  explains each refusal: denied (open Settings), restricted, off (system alert), not supported. The same card is on
  the home screen when joining is not possible.
* **Background (iOS):** what Bluetooth can and cannot do in the background, the state of Background App Refresh, Low
  Power Mode, and "do not force-quit the app". On a Mac only a short note.

**Language.** `Localizable.xcstrings` has English and Turkish (the Android strings that apply, plus the iOS-specific ones
for the permission and the background). The language can be changed in the settings and **takes effect at once**: the
root view puts the chosen locale into the environment, which is what `Text` looks its keys up with. Text that is not a
`Text` goes through `L10n.string(_:language:)`. Add a language by adding it to the catalog and to `AppLanguage`.

**Settings** has appearance (theme, high contrast, text size with a sample), language, power profile, the identity
(safety number, node id) and a developer section (the Bluetooth test bench, the self-test, show the introduction
again). The rest of the settings (notifications, storage, privacy) is I7.

## Discovery, topology and the background (phase I3)

**Who connects to whom (tie-break).** An iPhone reads the token that an Android neighbour advertises
(`BleAdvertisement`) but cannot advertise one. While it advertises in the foreground it **leaves the connection to a
neighbour that can initiate**, because that neighbour sees an iPhone without a token and connects to it (profile 1.1,
"Tie-break"); this avoids a second link. It connects itself when the neighbour cannot initiate, when it only scans
(role Central: nobody can see it), when the app is in the background (an Android scan cannot see an iPhone that
advertises in the background), and when it has had no link at all for 30 s since it first saw the neighbour. Two
iPhones advertise no token, so both connect and the HELLO identifiers close the duplicate, as in I2.
`config.yieldToInitiators = false` switches the yielding off.

**Reconnecting.** An address that failed or whose link ended is left alone for 5 s, doubling with every failure in a
row up to 60 s; a link that lived 20 s or more starts from a clean slate. A closed duplicate is left alone for 5
minutes. A node that has all its links keeps looking for neighbours that wait and swaps its **oldest outgoing link**
(at least a minute old) for one of them, at most every 10 minutes (balanced), so that a crowd does not leave devices
out for good. The HELLO identifier is replaced every 10 minutes.

**Power profiles.** iOS has no scan modes and no advertising interval, so a profile controls what the app can:

| Profile | Foreground scan | Links | Rotation |
| --- | --- | --- | --- |
| `saver` | 2 s on, 18 s off | 3 | 15 min |
| `balanced` (default) | 5 s on, 10 s off | 5 | 10 min |
| `aggressive` | continuous, every advertisement | 7 | 5 min |

A node that has all its links scans like `saver` whatever the profile. Low Power Mode lowers the profile to `saver`
(`BleLinkTestModel`). Advertising has no setting on iOS; the connection interval of a link cannot be requested by a
central.

**Background and power (iOS).** `UIBackgroundModes` has `bluetooth-central` and `bluetooth-peripheral`.

* The scan then stays **on without a duty cycle** (the system schedules it and wakes the app for a sighting; pausing
  it would stop the wake-ups) and does not ask for duplicates (the system reports a device once).
* An iPhone **advertising in the background** is found by other iPhones only: the service UUID moves to an overflow
  area that an Android scan with a service filter does not match. Android neighbours are reached because the iPhone
  connects to them (see the tie-break).
* When the app goes to the background it asks for a background task (25 s) to finish what is in flight; after that
  the node runs only when Bluetooth wakes it, for a few seconds at a time.
* **State restoration:** the managers have restore identifiers. If the system terminates the app, it relaunches it in
  the background for a Bluetooth event; `CrisisNetworkApp.init` then starts the node again at once
  (`BleLinkTestModel.resumeIfEnabled()`, the node is started at every launch until Stop is pressed). The restored
  connections and services are **reset** and rebuilt (a link needs its handshake and its handle); the diagnostics
  show `restored`.
* The user can turn Background App Refresh off, or force-quit the app: iOS then does not relaunch it for Bluetooth.
  Nothing can be done about that; the I9 device matrix tests it.

**macOS.** No foreground/background difference for the radio and no background modes: the node runs while the app
runs. An activity assertion (`beginActivity`) keeps App Nap from throttling its timers. Sandbox and Bluetooth
entitlement are in place; everything else macOS-specific is I8.

### Exit test of I3: three mixed devices and the battery

The test bench has a Power picker, a Battery section and the tie-break/rotation counters. The Android 3-phone test
should be run first: it tells what the mesh does without iOS in it.

1. **Chain A - B - C, mixed.** A = iPhone, role Central; B = Android, role Both; C = iPhone, role Peripheral (A and C
   cannot see each other). Start all. A announcement from A reaches C through B, and the other way round. Repeat
   with B as an iPhone (Both) and A, C as Android.
2. **Tie-break.** iPhone (Both) and Android (Both) next to each other: one link, not two. "Held back by the
   tie-break" is 1 on the iPhone until the Android connects; "Duplicate links closed" stays 0.
3. **Background.** Lock the iPhone in a chain of step 1 and send from the others: the announcement arrives when
   Bluetooth wakes the app (the log shows `app in the background`). Then force-quit the app and relaunch it by hand:
   the node starts by itself.
4. **Battery.** Same chain, screen locked, device unplugged: run 30 minutes per profile (saver, balanced, aggressive)
   with one announcement per minute from another device, read the Battery section after unlocking. Compare the drain
   per hour; a measurement that charged is not valid.

## `scripts/build-apple.sh`

| Option | Meaning |
| --- | --- |
| `--profile dev\|release` | Cargo profile. `release` (fat LTO) is for builds you ship. |
| `--sqlcipher` | The encrypted database (vendored OpenSSL), the same feature Android ships with. |
| `--universal` | Also the x86_64 slices (Intel simulator, Intel Macs). Default: arm64 only. |
| `--platforms ios,sim,mac` | A subset, to save time while working on one platform. |

It writes `Packages/CrisisCore/CrisisCoreFFI.xcframework`, `Sources/CrisisCore/Generated/crisis_core.swift` and
`Generated/CoreBuildInfo.swift` (what the build was made with: profile, SQLCipher, slices, revision). None of
them is committed. After every change to the Rust core (or to a UniFFI signature) run the script again.

## Decisions taken in I0

* **Minimum iOS 17 / macOS 14.** Gives the `@Observable` model and a modern SwiftUI; older systems are a small
  share of the phones that matter. Cheap to change now, expensive later.
* **XcodeGen.** The Xcode project is generated from `project.yml`: it diffs, merges and ships in a ZIP; a
  hand-edited `.xcodeproj` does none of that.
* **One bundle identifier** (`org.crisisnetwork.app`, a placeholder: change it in `Base.xcconfig`) for iOS and
  macOS, so that one purchase covers both.
* **Swift 5 language mode with strict concurrency warnings.** The generated UniFFI bindings are Swift 5 code. The
  hand-written code is written for Swift 6; switching is `SWIFT_VERSION = 6.0` once the warnings are gone.
* **The SQLCipher decision is open until the CI job `apple-sqlcipher` is green**: the plan is the same vendored
  OpenSSL build as Android (one crypto stack to audit). If OpenSSL does not build for the simulator or
  macOS slices, the fallback is SQLCipher with Apple's CommonCrypto, which needs a change in the `storage` crate.
* **Scripts are called with `bash`** (`bash scripts/build-apple.sh`): a ZIP unpacked on Windows loses the
  executable bit, and CI would fail with "Permission denied". On a Mac you can also `chmod +x scripts/*.sh`, and
  `git update-index --chmod=+x scripts/*.sh` makes git remember it.
* **Static library with a C module map**, not a dynamic framework: nothing to embed or sign, and App Store review
  sees one binary.

## Before the first App Store upload (not needed for I0)

* **Licence.** The repository is GPL-3.0-only. Apple's App Store terms are widely held to be incompatible with
  the GPL for code you do not hold the copyright of. As the only copyright holder you may distribute your own
  code under other terms, but outside contributions change that. Decide this (and ask a lawyer) before I9.
* **Export compliance.** The app uses encryption (ChaCha20, Ed25519, SQLCipher). `ITSAppUsesNonExemptEncryption`
  is set to `false` in `project.yml` as a placeholder: App Store Connect asks the question again and the answer
  needs a deliberate decision, not a default.
* **Privacy manifest** (`PrivacyInfo.xcprivacy`) and the Bluetooth usage text in every language: I9.
