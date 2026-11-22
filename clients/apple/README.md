# Crisis Network for Apple (iOS, iPadOS, macOS)

SwiftUI clients on top of the Rust core. Plan and phases: `I0` ... `I9` (see `CHANGELOG.md`). This folder holds the
result of **phase I0** (the toolchain, the core as a Swift package, a placeholder app, CI), **phase I1** (the
runtime around the core), **phase I2** (the Bluetooth link), **phase I3** (discovery, topology, the background) and
**phase I4** (design system, shell, introduction), **phase I5** (contacts) and **phase I6** (chats and announcements), **phase I7** (network, settings, notifications), **phase I8** (macOS) and **phase I9** (release, privacy, accessibility).

```
clients/apple/
  Packages/CrisisCore/     Swift package: the Rust core (XCFramework), its UniFFI bindings, CoreSelfCheck
  Packages/CrisisRuntime/  Swift package: clock, secrets (Keychain), storage layout, NodeRuntime, transport seam
  App/Shared/              the app (iOS and macOS share it)
  Tests/iOS/               tests that run inside the iOS app on the simulator
  Config/                  Base.xcconfig (shared), Local.xcconfig (yours, not committed)
  project.yml              XcodeGen specification of the Xcode project
scripts/build-apple.sh     builds the core for Apple platforms and fills the package
scripts/check-mac-app.sh   checks the Mac app's sandbox rights and permission texts (CI, and before notarizing)
scripts/notarize-mac.sh    builds, signs (Developer ID), notarizes and packs the Mac app as a disk image
scripts/package-apple.sh   builds every flavour of the app that a release carries (macOS/iOS, debug/release, each processor type)
scripts/apple-version.sh   the version of a release from its git tag (apple-v1.2.3)
scripts/check-privacy-manifest.sh  compares the privacy manifest with what the built binary imports
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

## Contacts (phase I5)

The contacts destination has the list of known people and three ways to add one: **show my card** (a QR code, plus the
card as text to copy or share), **scan a card** (the camera, on a Mac also a picture; see phase I8) and **enter a card as text**. Adding a card shows the safety number to compare, takes an optional nickname and
stores the contact. A contact has a **trust level** (not verified, verified, blocked) and a screen of its own: nickname,
trust buttons, and the safety numbers (theirs and yours) to read to each other. Blocking asks first. Those screens are
`App/Shared/Contacts/`:

| File | Job |
| --- | --- |
| `ContactBook.swift` | The rules: who counts as a contact, the order of the list, display names, `Hex`, `CardText`, the stores for nicknames and for pending cards. No UI, no node. |
| `ContactsModel.swift` | The holder for the whole app (`ContactsModel.shared`): reads the contacts from the running node, adds a card, changes the trust, keeps the nicknames. Talks to the node through the small `ContactsBackend` protocol, which `NodeRuntime` conforms to. |
| `QrCode.swift` | QR codes with CoreImage only: `image(of:)` draws (black on white, with the quiet zone), `read(_:)` detects. |
| `CameraAccess.swift`, `QrScannerView.swift` | The camera permission (asked when the person presses the button, one explanation per refusal) and the AVFoundation reader (iOS). |
| `ContactsView`, `ContactDetailView`, `MyCardView`, `ScanCardView`, `PasteCardView`, `AddContactForm` | The screens. They are pushed on the destination's own `NavigationStack`, so the back button and the swipe are the platform's. |

**The conventions are the core's.** The text form of a card (`crisis:` + URL-safe base64, 171 characters), nickname
rules and the other app conventions are the Rust `appcodec` crate, called through the generated bindings
(`encodeCardText`, `decodeCardText`, `normalizeNickname`, `shortNodeId`, ...). There is no second implementation to
keep in step. What the three clients share is `core/testdata/appcodec.json`: the Rust tests, the Android tests and the
Swift package test `ConventionVectorTests` (which runs on the Mac with `swift test`) read the same file.

**Rules that follow the Android client**

* The node is only open while the mesh runs (the database cannot be opened twice), so the list is read, and the trust
  level can be changed, only then. A card scanned or pasted while the mesh is off is **kept** and added as soon as the
  node runs; adding your own card is refused either way.
* Nicknames live on this device only (the core knows keys, not names), in the app's defaults, keyed by node id.
  A nickname alone makes a node a contact. A node that was merely seen is not a contact until it has a card, a changed
  trust level or a nickname.
* Deleting the identity (the introduction, "start over") forgets the nicknames and the pending cards.
* The contact screen has a "Send a message" button that appears when the chats exist (`onMessage`, I6).

**Camera.** `NSCameraUsageDescription` is in `project.yml` (iOS). The reader uses `AVCaptureMetadataOutput` with the
`.qr` type; nothing is recorded or sent. On a device without a camera (the simulator) the scan screen says so and
leads to the text entry. The Mac has its own scan screen with the camera and picture alternatives (phase I8).

**Tests.** `ConventionVectorTests` (package `CrisisCore`, all vector groups), `ContactsTests` (`Tests/iOS`: the
rules, the model against a real in-memory core, the text form, a QR code drawn and read back, the camera table, the
strings in both languages). Not tested by a machine: the live camera, which needs a device (see the exit check below).

**Exit check of I5 (by hand, two devices).** On A: contacts, "Show my card". On B: contacts, "Scan a card" and read A's
code; compare the safety numbers; add; mark as verified. Repeat the other way round. Then test the text path (copy on A,
"Enter a card as text" on B), a card added with the mesh off, and an Android phone's card read by an iPhone and the
other way round (the QR code and the text form are the same on both).

## Chats and announcements (phase I6)

The chats destination lists the private conversations (newest first, with a preview and the unread count) and starts a
new one (only contacts with a stored card who are not blocked). A **conversation** shows the messages oldest first,
the "new messages" line above the first one that was unread when it was opened, the state of every sent message
("Published", then "Delivered" when the acknowledgement came back), and a box that counts **UTF-8 bytes** against what a
private message can carry. Scrolling up loads older pages. The announcements destination shows the public announcements
(newest first; important and emergency ones in their own colour, always with the word as well) and the screen to write
one: text with the byte counter, importance (normal, important, emergency) and how long devices keep it (presets from the
core, or any time from one second up to the longest the core allows). Important and emergency announcements ask for a
confirmation first, because they cannot be taken back. Unread counts are the badges of the two destinations. Code in
`App/Shared/Messaging/`:

| File | Job |
| --- | --- |
| `MessageBook.swift` | The rules, no UI and no node: a stored message as a row, the chat list line, previews, `ConversationWindow` (the loaded part of a conversation: paging, read marker, the unread line). |
| `MessagingModel.swift` | The holder for the whole app (`MessagingModel.shared`): reads chats, the open chat, the feed and the unread counts from the running node, marks an open conversation read, pages backwards, sends and publishes. Talks to the node through the `MessagingBackend` protocol, which `NodeRuntime` conforms to. |
| `MessageViews.swift` | Bubble, time, byte counter, unread line, labels of severities and states, the lazy scroll column. |
| `ChatsView`, `ChatView`, `AnnouncementsView` (with `ComposeAnnouncementView`) | The screens. |

`Shell/AppNavigator.swift` holds the path of the chat destination's `NavigationStack`: "Send a message" on a contact
switches to the chat destination with that conversation open (and a notification will do the same in I7).

**Rules that follow the Android client**

* The node is only open while the mesh runs, so messages are read and sent only then (`ready`); a screen says so.
* An open conversation is a window over the newest messages that grows backwards one page (40) at a time; a refresh
  reads the whole window again so that the delivery marks of older messages stay right. While a screen is open, new
  incoming messages are marked read at once. A chat is only forgotten by its screen disappearing when it is still the
  one that was opened for that person.
* Text from other devices is cleaned of control characters and direction tricks by the core (`sanitizeText`) before it
  is shown. A message that cannot be read says so.
* Whether a text is too long is decided by the core's byte budgets (`directByteLimit`, `announcementByteLimit`,
  `budgetStatus`); lifetimes by `lifetimePresets`, `lifetimeIsValid`, `splitLifetime`.
* The lifetime preselected for a new announcement is one day (the core's default); a setting for it comes with I7.

**Tests.** `MessagingTests` (`Tests/iOS`): the rules, the window and its paging, the model against a real in-memory core
(send, refuse, publish, page backwards, close), a message that really travels between two runtimes over the in-memory
link (unread until opened, marked read, acknowledged), the navigator, and the strings in both languages (arguments and
plurals). Not tested by a machine: the screens themselves and scrolling.

**Exit check of I6 (by hand, two devices).** Add each other's cards, join the mesh on both. A writes to B: B sees the badge
on the chats tab, the conversation with a preview, opens it (the line above the message, badge gone) and A sees
"Delivered". Write more than 40 messages and scroll up. Publish a normal, an important and an emergency announcement with
different lifetimes: the other device shows them with the right colour and word, and they disappear when the time is
up. Try the same between an iPhone and an Android phone. Turn the mesh off: both destinations say so.

## Network, settings and notifications (phase I7)

**Network screen** (`Network/NetworkView.swift`, opened from the settings and the home screen): the neighbours (the last
characters of each link's address, who connected to whom, traffic both ways, packet size, what waits to be sent, packets
dropped, more devices in range), how full the message store is, the Bluetooth radio (central and peripheral state,
scanning, advertising, connections, devices backed off, failed attempts) and the node's counters in three groups
(traffic, refused and dropped, delivery trouble). The links and the radio are the ones `BleLinkTestModel` already
refreshes every second; the node's counters are read every two seconds while the screen is open.

**Settings** gained: notifications (the permission, with a button to ask or to open the system settings), privacy, the
network screen, the size of the message store (8 to 256 MB), the default lifetime of announcements, and an about card
(app, core, API and protocol versions). A new size or lifetime is remembered at once and applies **the next time the mesh
starts** (the core reads them when it opens), which the screens say while the mesh runs with other values.
`Prefs/NodePreferences.swift` holds the choices (`StoreLimits`, `DefaultLifetimes`, `NodeChoices`); `NodeChoices` turns
them into the `NodeSettings` the node opens with (the pinned share stays a quarter of the store).

**Notifications** (`Notifications/`). iOS has no push for this app: messages arrive over Bluetooth. When the system lets
the app run to receive one (Bluetooth background modes, state restoration) and the app is not on screen,
`MessageNotifier` shows a local notification:

* A private message: one notification per person (the next one replaces it), titled with the nickname or the short
  address, with one line of cleaned text. Announcements: one notification that says how many there are; its title names
  the importance.
* A burst makes one sound per three seconds (and per importance); the rest arrive silently.
* While the app is on screen nothing is shown (the tab badges say it). Opening a chat or the announcements removes
  their notifications. The unread count is the badge of the app icon.
* The categories carry a placeholder text for the lock screen when the system hides previews.
* A tap opens the chat of the sender or the announcements (`NotificationRouter`, set as the center's delegate at launch,
  so a tap that starts the app is not lost; `AppNavigator` remembers the request until the shell exists).
* The permission is asked from the settings, not at launch (the person should know why). `NotificationAccessPlan` says
  what to offer: nothing, ask, or open the system settings.

**Privacy.** iOS has no way to block screenshots. Instead the window is covered whenever the app is not active, so that
the app switcher shows no messages (on by default, like the screenshot block on Android; iOS only: a Mac has no switcher
snapshot).

**Tests.** `NetworkAndNotificationsTests`: store sizes, lifetimes and how they become `NodeSettings`, remembered and
corrected choices, sizes and counters (units, decimal separators of both languages, the three groups of counters and
that none is lost), the permission plan, the alert gate, what a received message becomes as a notification (name, text,
replacement, sound, announcements counting, both languages), the cover, the router, and the strings (arguments and
plurals). Not tested by a machine: the notification center, the screens, and what the system does in the background.

**Exit check of I7 (by hand, real devices).** Join the mesh on two devices and open the network screen: neighbours,
traffic and the counters move. Change the store size while joined: the screen says it applies at the next start; leave and
join again, and the storage card shows the new size. Allow notifications in the settings, send a message from the other
device while this app is in the background (then locked): a notification appears, a second message replaces it, a tap
opens the chat. Publish an announcement of each importance. Check the app switcher shows the cover, and that turning the
option off shows the real window.

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
runs (also with its window closed, see "The Mac app (phase I8)"). An activity assertion (`beginActivity`) keeps App Nap
from throttling its timers.

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

## The Mac app (phase I8)

The Mac app is the same SwiftUI code as the iOS app (`App/Shared`), with a Mac-specific layer in `App/Shared/Mac/` and a
few `#if os(macOS)` places elsewhere. What is different on a Mac:

**One window, and the node outlives it.** The app has a single `Window` scene and a menu bar icon (`MenuBarExtra`).
Closing the window **never** stops the node: the app goes on in the menu bar, and `applicationShouldTerminateAfterLastWindowClosed`
says no. Quitting (Cmd-Q or "Quit" in the menu bar menu) stops the node first (`applicationShouldTerminate` waits for it, at most five
seconds), so the database is closed properly, and keeps the "join the mesh at launch" choice, so the next launch joins again
(`BleLinkTestModel.shutdownForQuit()`; the plain `stop()` of the Leave button forgets that choice). A second copy cannot start
(`LSMultipleInstancesProhibited`: the database cannot be opened twice).

| Piece | What it does |
| --- | --- |
| `MacApp.swift` | `MacDesktop` (windows, Dock icon, "is the app on screen", sleep and wake in the log), `MacAppDelegate`, the menu bar label and menu, the menus of the app (Settings on Cmd-comma, Go on Cmd-1 to Cmd-5, Mesh on Cmd-Shift-J), `SMAppServiceLoginItem`. Mac only. |
| `MacShellPolicy.swift` | The rules, no AppKit: `MacShellChoices`, `MacPresence`, `MenuBarSummary`, `LoginItemPlan`, `LoginItemModel`. Compiles everywhere, so the iOS test bundle tests it. |
| `MacSettingsSection.swift` | The "Mac" section of the settings: menu bar icon, Dock icon, open at login, start without a window. |
| `Contacts/MacScanCardView.swift`, `MacQrScannerView.swift`, `QrImageImport.swift` | Reading a card on a Mac, below. |

**Menu bar icon.** A symbol for the state of the mesh (the state is also written in the menu, so the symbol never carries the
meaning alone), the number of unread messages, and a menu: state, unread, "Open Crisis Network", "Join/Leave the mesh", "Quit". It
is on by default and can be switched off in the settings; the Dock icon can only be hidden while it is shown (`MacShellChoices.effectiveHideDock`),
so that the app can always be reached. Dragging the icon out of the menu bar switches the choice off and the Dock icon returns.

**Open at login** is `SMAppService.mainApp` (macOS 13 and later): no helper app, no entitlement, visible and revocable in
System Settings > General > Login Items. A first registration may ask for the person's approval there; the settings show that state and a button
for it. The system does not register an app that runs from a build folder or a disk image: the settings say to move it to Applications.
With "start without a window" on (the default) and the menu bar icon shown, a launch **by the login item** closes the first window
at once, so nothing opens while the person is logging in; the system's launch event tells the two kinds of launch apart, and if it does
not, the window just opens.

**The radio and "foreground".** On iOS the transport is told whether the app is in the foreground, because the system treats a
background app differently. On a Mac there is no such difference, so the radio is **always** told "foreground" (the tie-break that yields to a
neighbour that can connect needs the full advertisement, which a Mac always sends); `isForeground` then only means "the person
can see the app" (active app **and** an open window, `MacPresence`) and decides about notifications: with the window closed or another app in front, a
message makes a notification, and a tap on it opens the window again (`NotificationRouter`). The activity assertion of I3 keeps App Nap from throttling the
timers; sleep and wake are written to the log of the test bench.

**Sandbox and rights.** The app is sandboxed; `project.yml` gives it exactly these entitlements:

| Entitlement | Why |
| --- | --- |
| `com.apple.security.app-sandbox` | The sandbox. The node's files live in the app's container (`Application Support/Crisis`). |
| `com.apple.security.device.bluetooth` | The Bluetooth radio. The permission dialog shows `NSBluetoothAlwaysUsageDescription`. |
| `com.apple.security.device.camera` | The camera that reads a card's QR code (`NSCameraUsageDescription`). |
| `com.apple.security.files.user-selected.read-write` | A picture the person chooses or drops to read a card from, and the file they save their own card's QR code to. |

There is **no network entitlement**: the app talks to other devices over Bluetooth only, and a sandbox that forbids sockets is a property worth keeping
for the threat model. `scripts/check-mac-app.sh` reads the entitlements back and fails when one of the four is missing or when a
forbidden one appears (network, all files, a way around the hardened runtime, a temporary exception, `get-task-allow` in a signed
build), and checks the permission texts in `Info.plist`. CI runs it on a Release build that is ad-hoc signed with the hardened runtime on
(`ENABLE_HARDENED_RUNTIME` is set for the Release configuration only), so what the system would enforce is what is checked.

**Keychain on the Mac.** The vault keeps using the file-based (login) keychain on the Mac (`KeychainSecretStore.defaultUsesDataProtection` stays
`false` there). The data protection keychain needs the `keychain-access-groups` entitlement, which is a restricted one: it comes with a
provisioning profile, which a Developer ID build outside the App Store does not otherwise need, and moving items from one keychain to the other without
losing an identity needs a migration of its own. Consequences: an item is tied to the code signature, so a **Developer ID** build keeps its
items across updates (an ad-hoc build asks again after every rebuild); and a Mac with automatic login leaves the login keychain locked, so the first
read may show the system's password prompt. Revisit this with the TestFlight build (I9), where a profile exists anyway.

**Reading a card without a camera.** `Contacts` > "Scan a card" on a Mac (`MacScanCardView`) shows the camera when there is one (built-in,
USB, or an iPhone as a camera) and it is allowed, with the same permission explanations as on iOS. The Mac reader takes the frames itself (`AVCaptureVideoDataOutput`, four a
second) and gives them to the CoreImage detector, because a Mac's cameras do not all offer QR metadata. **Always** offered, camera or not: a **picture file** (a panel), a **picture from the
clipboard** (a screenshot), a picture **dropped** on the window, and the card as text. All three picture paths go through `QrImageImport`: files over 20 MB are refused, a
picture over 4096 pixels on a side is decoded at that size, and the result is a card, "not a card", "no QR code", "not a picture" or "too large", each with its sentence. In the
other direction, "Show my card" has **Copy the QR code as a picture** and **Save the QR code as a picture** next to the text forms.

### Distributing the Mac app outside the App Store: notarization

`scripts/notarize-mac.sh` does the whole path: the core for Apple silicon and Intel (release profile, encrypted database), the archive (Release, hardened runtime,
manual signing with a **Developer ID Application** certificate), the export, `scripts/check-mac-app.sh --signed`, the notarization of the app, stapling, a disk image with an
Applications shortcut, its signature, its notarization and stapling, and the `spctl` assessments. Run it on a Mac that has the certificate in its keychain:

```sh
export TEAM_ID=ABCDE12345
export NOTARY_PROFILE=crisis-notary   # xcrun notarytool store-credentials crisis-notary --apple-id YOU --team-id $TEAM_ID --password <app-specific password>
bash scripts/notarize-mac.sh          # build/mac-release/CrisisNetwork.dmg, with its SHA-256
```

A CI job uses an App Store Connect API key instead (`NOTARY_KEY_PATH`, `NOTARY_KEY_ID`, `NOTARY_ISSUER`); that job belongs to the release pipeline of I9 (`release-apple.yml`), together with the
TestFlight upload. If Apple refuses the app, the script prints the notary log. The usual reasons: the hardened runtime is off, a library is not signed with a timestamp, or an entitlement
that is not allowed was added. The bundle identifier in `Config/Base.xcconfig` is a placeholder: use your own (`APP_BUNDLE_ID`) before the first real build.

### Exit check of I8 (by hand, on Macs)

1. **Window and menu bar.** Start the app, finish the introduction, join the mesh. Close the window: the menu bar icon stays, the menu says "Mesh on", and a message from another device makes a
   notification; a tap on it opens the window with the chat. "Quit" stops the app; starting it again joins the mesh without a click.
2. **Dock icon.** Settings > Mac: hide the Dock icon (the app leaves the Dock, the menu bar icon stays); switch the menu bar icon off (the Dock icon returns by itself).
3. **Open at login.** Switch it on, log out and in: the app is running with no window and the mesh is joined; "Open Crisis Network" shows the window. System Settings > Login Items shows the app, and switching it
   off there is seen by the app's switch. Try an app that runs from the disk image: the settings explain that it cannot.
4. **Cards without a camera.** On a Mac with a camera: scan the code on a phone. Refuse the camera permission once: the explanation and "Open Settings" appear. Then without the camera: choose a screenshot of
   a card, paste one, drop one on the window; a picture of something else says "No QR code"; "Show my card" > copy as a picture and paste it into another app, and save it as a file.
5. **Mixed links.** Mac with an iPhone and an Android phone next to each other: one link each, not two (the tie-break), a message each way, also with the Mac's lid closed and opened again (the log shows sleep and wake).
6. **The signed build.** `bash scripts/notarize-mac.sh`, copy the disk image to another Mac (not the build machine), open it from the Downloads folder: no warning, the permission dialogs for Bluetooth and the camera show the texts of `project.yml`.

## Release, privacy and accessibility (phase I9)

### Tag a release, get the files as assets

Pushing a tag that starts with `apple-` builds the app and **puts the files straight into the GitHub release of that tag**:

```sh
git tag apple-v1.0.0 && git push origin apple-v1.0.0     # a tag with a hyphen in its version (apple-v1.0.0-rc1) is a pre-release
git tag android-v1.0.0 && git push origin android-v1.0.0 # the Android release works the same way (clients/android/RELEASING.md)
```

`.github/workflows/release-apple.yml` builds the core once (release profile, encrypted database, every processor type), then the
macOS app and the iOS app from it in parallel, then publishes. Each Debug and Release build is made for each processor type and
every file says which:

| Asset | What it is |
| --- | --- |
| `CrisisNetwork-<version>-macos-<debug\|release>-<arm64\|x86_64\|universal>.zip` | The Mac app, signed ad hoc (the sandbox is in force; Gatekeeper asks on the first start: right-click > Open). `universal` has both processor types; the other two are smaller. |
| `CrisisNetwork-<version>-macos-release-universal-notarized.dmg` | Signed with a Developer ID and notarized: opens without a warning. **Only with the signing secrets** (below). |
| `CrisisNetwork-<version>-ios-device-<debug\|release>-arm64-unsigned.ipa` | iPhone and iPad (arm64), not signed: sign it with your own Apple ID (Xcode, AltStore, Sideloadly). |
| `CrisisNetwork-<version>-ios-simulator-<debug\|release>-<arm64\|x86_64>.zip` | For the iOS simulator of a Mac with Apple silicon or Intel (`xcrun simctl install booted CrisisNetwork-iOS.app`). |
| `SHA256SUMS.txt` | Checksums of every file. |

A "debug" file is the Debug configuration of the app (assertions, debug symbols, debuggable) on the **same optimised core with
the encrypted database** as the release one: the core takes most of the build time and is the same code in both. Started by hand
(Actions > Apple release > Run workflow) the same files are made and kept as one workflow artifact, with no release and no
upload to TestFlight: that is the way to try the pipeline from a branch.

What is checked on the way, and stops the release: the Swift package tests with SQLCipher required; for the Mac app the
sandbox rights (`scripts/check-mac-app.sh`), the privacy manifest against the binary (`scripts/check-privacy-manifest.sh`) and
that every file has exactly the processor types its name says; for iOS the privacy manifest as well.

**Signing and TestFlight need repository secrets; without them the workflow still produces everything above.**

| Secret | For |
| --- | --- |
| `APPLE_TEAM_ID` | Your 10-character team identifier. |
| `ASC_KEY_P8_BASE64`, `ASC_KEY_ID`, `ASC_ISSUER_ID` | An App Store Connect API key (App Store Connect > Users and Access > Integrations; the `.p8` file as `base64 -i AuthKey_XXXX.p8`). **iOS:** Xcode signs by itself with it (automatic signing), archives the Release app and uploads it to TestFlight (the step is `continue-on-error`: a failure there is a warning and does not hold back the assets). **macOS:** the notary service login. |
| `MAC_DEVELOPER_ID_P12_BASE64`, `MAC_DEVELOPER_ID_P12_PASSWORD` | The "Developer ID Application" certificate with its private key, exported from Keychain Access as `.p12` (`base64 -i cert.p12`). With the five above, `scripts/notarize-mac.sh` makes the notarized disk image. |

The app record in App Store Connect (with the bundle identifier of `Config/Base.xcconfig`) must exist before the first TestFlight
upload; the build number is the number of the workflow run, the version is the tag (`apple-v1.2.3` is 1.2.3). Builds wait at
"Missing Compliance" until the export questions are answered once: `EXPORT_COMPLIANCE.md`.

### App icons

The icons of both platforms come from **one master logo**, `assets/branding/logo-master-1024.png` (the logo on a transparent background, 1024 px),
through `python3 scripts/make-icons.py` (needs Pillow; `--check` only checks, CI runs it). To change the logo, replace the master and run the script.
What it makes, and why the sets from icon generators are not used as they are:

* **iOS** (`App/Shared/Resources/Assets.xcassets/AppIcon.appiconset`): one 1024 px icon, from which Xcode makes every size, on an opaque white
  background. A generated icon with a transparent background is refused by App Store Connect (an iOS icon may not have an alpha channel), and the system
  rounds the corners itself.
* **macOS** (same set): ten sizes from 16 to 1024 px, the logo on a white rounded tile with the margin and shadow of Apple's template.
* **Android** (`clients/android/app/src/main/res`): an adaptive icon (white background, the logo inside the 66 dp that every launcher shape shows), a monochrome
  layer for themed icons (Android 13), the legacy PNGs for Android 7 and older, and `ic_launcher-playstore.png` (512 px) for the Play Store listing.
  The generated template had the logo reaching the edge of the layer, which a round launcher cuts, and a green grid as the background.

The iOS and macOS targets take the set with `ASSETCATALOG_COMPILER_APPICON_NAME: AppIcon` in `project.yml`; Xcode adds the icon keys to `Info.plist`.
Dark and tinted iOS 18 variants of the icon are not made (the system then uses the normal icon); they can be added to the same set later.

### Setting up the Apple secrets (they need the paid membership first)

Android needed a keystore you made yourself. **Apple has no such file**: a certificate has to be issued by Apple to a member of the Apple Developer
Program (99 USD a year), so nothing in this section can be done before the membership. The `apple-*` release workflow runs without any of it
(unsigned and ad-hoc files); the secrets only add the notarized disk image and the TestFlight upload. A Mac is **not** required:

1. **App Store Connect API key** (for TestFlight and for notarization): App Store Connect > Users and Access > Integrations > App Store Connect API > "+",
   role "App Manager" or "Admin". Download the `AuthKey_XXXXXXXXXX.p8` (it can be downloaded once). Note its **Key ID** and the **Issuer ID** at the top of
   the page. Secrets: `ASC_KEY_ID`, `ASC_ISSUER_ID`, and `ASC_KEY_P8_BASE64` = `base64 -w0 AuthKey_XXXXXXXXXX.p8` (Git Bash or WSL on Windows).
2. **Team ID**: developer.apple.com > Account > Membership details (10 characters). Secret: `APPLE_TEAM_ID`.
3. **Developer ID Application certificate** (for the notarized Mac disk image; only the Account Holder can create it). With OpenSSL, no Mac:
   ```sh
   openssl genrsa -out devid.key 2048
   openssl req -new -key devid.key -out devid.csr -subj "/emailAddress=YOUR@MAIL/CN=YOUR NAME/C=TR"
   # developer.apple.com > Certificates > "+" > "Developer ID Application" (G2 Sub-CA) > upload devid.csr > download the .cer
   openssl x509 -inform DER -in developerID_application.cer -out devid.pem
   openssl pkcs12 -export -legacy -inkey devid.key -in devid.pem -out devid.p12   # -legacy: the macOS "security import" in the workflow
                                                                                   # cannot read the newer default encryption of OpenSSL 3
   base64 -w0 devid.p12
   ```
   Secrets: `MAC_DEVELOPER_ID_P12_BASE64` (that output) and `MAC_DEVELOPER_ID_P12_PASSWORD` (the password you gave `openssl pkcs12`). Keep `devid.key` and
   `devid.p12` out of the repository (they are the identity of the publisher).
4. **App record**: App Store Connect > Apps > "+" > New App, with the bundle identifier of `Config/Base.xcconfig` (register it first under Certificates, Identifiers &
   Profiles > Identifiers; no capability needs to be switched on for the app). iOS signing needs nothing else: Xcode creates the distribution certificate and the
   profile itself through the API key (`-allowProvisioningUpdates`).
5. Push a tag `apple-v0.1.0-rc1` and watch the "Notarized disk image" and "Archive and upload to TestFlight" steps.

### Privacy manifest and App Privacy

`App/Shared/Resources/PrivacyInfo.xcprivacy` (iOS and macOS): **no tracking, no collected data**, and a reason for each
"required reason" API the app calls: UserDefaults (`CA92.1`), file timestamps in the app's own container (`C617.1`, the Rust core and
SQLite), the system boot time for measuring elapsed time (`35F9.1`) and disk space (`E174.1`: the core and SQLite write the node's
database and ask the system about the volume; the first Release build of I9 found this one, the Swift code never asks for free space). In App Store Connect, answer "Data Not Collected". The list of
APIs that Apple checks grows: `scripts/check-privacy-manifest.sh` reads the imports of the built binary and fails when it finds a
category that the manifest lacks, which is the mail that App Store Connect would otherwise send after the upload (ITMS-91053). Run
on the Mac app and the iOS app; if it names a category, add it with the reason that is true for the app (Apple's list of reasons
is linked in the script).

### Permission texts in every language

`App/Shared/Resources/InfoPlist.xcstrings` has the Bluetooth and camera texts (and the name under the icon) in English and Turkish,
next to the English ones in `project.yml`. Adding a language means adding it to `Localizable.xcstrings`, to this catalog and to
`CFBundleLocalizations`.

### Export compliance

`ITSAppUsesNonExemptEncryption` is now `true`: the app has encryption of its own. What it uses, the choices and the questions App Store
Connect asks are in `EXPORT_COMPLIANCE.md`. This is a legal answer: ask a lawyer.

### VoiceOver and Dynamic Type

The text styles, the headings, the combined rows and the tone icons came with I4 to I7. I9 adds what an audit of the screens
turned up: **the home screen announces** when the mesh connects, searches or goes offline (`HomeStatus`, not the two states that only
mean "wait"); **large text** (`Design/AdaptiveLayout.swift`): at the accessibility sizes the chat composer stacks (the send button
under the field, full width) and its width grows with the text before that, names and previews are not cut to one or two lines,
and the empty-state icon scales. The walkthrough with expected results is in `DEVICE_MATRIX.md`, sections 3.

### Device matrix and threat model

`DEVICE_MATRIX.md` is the plan and the table for the round on real devices (iPhone models, Low Power Mode, Background App Refresh off,
the Bluetooth permission denied, VoiceOver, Dynamic Type, mixed pairs). `docs/src/threat-model.md` has a new section on the clients: what
is on the device and how it is protected, what a stolen or a nearby device can reach, the Mac sandbox and the builds.

### Exit check of I9 (by hand)

1. Push `apple-v0.0.1-rc1` from a branch you can delete: the release has the assets of the table above, `SHA256SUMS.txt` matches
   (`shasum -a 256 -c`), the release is marked pre-release. Install the Mac `universal` zip on both an Apple silicon and an Intel Mac,
   an iOS simulator zip on a simulator, the unsigned `.ipa` on a phone through your signing tool.
2. With the secrets set: the `-notarized.dmg` opens on another Mac without a warning; a build appears in TestFlight (after the
   compliance question) and installs on a phone.
3. `DEVICE_MATRIX.md`: sections 2 to 4 on the devices you have, written into the table.
4. VoiceOver and the two largest text sizes through the whole app in both languages.

### What was not run

Written without a Swift toolchain and without GitHub: neither workflow has run. The likeliest first-run problems: the privacy check
naming a category that is not in the manifest (add it, with its reason); the Intel simulator or Intel Mac slice not linking if a
processor type is missing from the core build; `xcodebuild` refusing `ARCHS` on `generic/platform` destinations; App Store Connect refusing the
automatic signing with an API key that lacks the "App Manager" role or higher; the export step's `app-store-connect` method name on older Xcode
versions (`app-store`). The tests of this phase (`ReleaseReadinessTests`) are the first thing to run.

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
* **Export compliance.** Decided in I9: `ITSAppUsesNonExemptEncryption` is `true`, see `EXPORT_COMPLIANCE.md` and
  "Release, privacy and accessibility (phase I9)".
* **Privacy manifest** and the permission texts in every language: done in I9.
