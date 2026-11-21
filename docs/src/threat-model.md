# Threat model

## Assets

* The integrity and authorship of messages.
* The confidentiality of Direct messages.
* The availability of the mesh and of each device's storage.

## Attackers

An attacker may be a nearby peer, a relaying node, or anyone able to inject radio traffic.
Peers are untrusted by default.

## Mitigations

* **Forgery.** Every envelope is signed with Ed25519 and verified strictly before it is stored
  or forwarded. Keys of small order and non-reduced signature scalars are rejected.
* **Confidentiality.** Direct messages use a sealed box to a single recipient (X25519,
  HKDF-SHA256, ChaCha20-Poly1305). Tampering is detected.
* **Clock manipulation.** `created_at_ms` is untrusted. Expiry is counted from the receive time.
* **Flooding.** Per-peer rate limits, a penalty score with bans, a per-sender rate limit, local
  sender blocking, a hop cap, bounded tables and strict reassembly memory limits.
* **Storage exhaustion.** A byte budget with deterministic eviction. Inbound messages are pinned
  against eviction only up to a fixed budget, so strangers cannot fill the store with unevictable data.
* **Resurrection.** Tombstones keep a deleted or expired message from returning from another node.

## Known limitations

* `hops_left` is outside the signature, so a relaying node can *increase* it. This is a
  denial-of-service (flooding) risk, not an integrity risk; the mesh caps inflated hop counts.
* Direct messages have no forward secrecy: compromise of a recipient's key reveals past messages.
* Broadcast messages are public by design.
* Metadata (sender, recipient, timing) is visible to relaying nodes.

## The device and the clients (Android and Apple)

The sections above are about the protocol. This one is about the phone or the Mac that runs it: what an attacker who holds the
device, or who sits next to it, can reach. The Apple clients are described as of phase I9; the Android client has the same
shape (Keystore instead of Keychain, `FLAG_SECURE` instead of the app-switcher cover).

### What is kept on the device, and where

* **Secrets** (the identity key and the key of the database) are in the system's secret store: the iOS Keychain with the data
  protection class "after first unlock, this device only" (never in a backup, never synced), on a Mac the login keychain
  (see "Mac" below). An item that exists but cannot be read is never overwritten: the app stops with a clear error instead
  of making a new identity over the old one.
* **The database** is encrypted with SQLCipher. A **release build refuses to open a plaintext database** (the build with the
  encrypted core is the only one that starts); a debug build may use one, which is why debug files are for testing only.
* **The node's directory** is excluded from backups (the key is bound to the device, so a restored backup could not be
  read anyway) and carries a file protection class on iOS.
* **Nothing leaves the device except over Bluetooth, to the devices a message is for or that relay it.** There is no server,
  no account, no analytics and no advertising identifier. The privacy manifest says so (`App/Shared/Resources/PrivacyInfo.xcprivacy`:
  no tracking, no collected data), and App Store Connect's "App Privacy" answer is "Data Not Collected".

### Someone with the unlocked or the stolen device

* An **unlocked** device shows everything the owner can see: that is outside what an app can defend.
* A **locked** device shows no names and no text in notifications (the lock screen gets a generic line, not the message), and the
  **app switcher** shows the app's own colours instead of the chats (Android: the screenshot is blocked). iOS has no way to
  block screenshots, and the app does not pretend to: what a person screenshots is theirs to decide.
* A **stolen, locked** device: the database and the identity are encrypted with keys that the system releases only after the
  first unlock since start-up. Before that first unlock the node does not run (it cannot read its key).
* **Backups and cloud**: the node's files and secrets are not in iCloud or device backups. The consequence is that a new phone
  starts with a new identity; contacts have to be added again.

### Someone nearby

* **Bluetooth** shows that a device runs the app (the service identifier is in the advertisement) and, to anyone who connects,
  what the protocol shows to a peer: nothing is readable without the keys, but metadata is visible to relaying nodes (above).
  Switching Bluetooth off, or leaving the mesh, stops advertising.
* **Contact cards** are public data (a public key and its signature). The **safety number** shown on the Home screen and in a
  contact's detail is for comparing in person; a card that arrives over a channel one cannot trust (a screenshot sent by a
  stranger, a QR picture on a website) is exactly as trustworthy as that channel. The trust level of a contact records how much
  the person trusts it; the app cannot decide that for them.
* The **clipboard** and **picture files** hold cards (public data) and never secrets. The app never copies a key.
* The **camera** reads QR codes and nothing is recorded or kept; a picture read from a file or the clipboard is bounded
  (20 MB, 4096 pixels on a side) before it is decoded, so a hostile picture cannot exhaust memory.

### The Mac app

* The app is **sandboxed** with four rights: Bluetooth, the camera, and files the person picks or drops themselves. It has **no
  network entitlement**, so even a flaw in the app cannot open a socket; `scripts/check-mac-app.sh` fails the build when a
  network, "all files" or hardened-runtime-weakening entitlement appears.
* The vault uses the **login keychain**. Its items are tied to the code signature: a Developer ID build keeps them across
  updates, and a differently signed build is asked about access first. A Mac with automatic login leaves the login keychain
  locked, so the first start may show the system's password prompt. Moving to the data protection keychain needs a
  provisioning profile and a migration (decided for a later phase).
* The window can be closed while the node runs on in the menu bar; quitting stops it. Opening at login is the system's login
  item (visible and revocable in System Settings).

### Builds and updates

* **Release builds are signed** (Android: the release key; iOS: App Store signing; macOS: Developer ID, notarized). The
  release assets that are *not* signed with a real identity say so in their name (`-unsigned`, `-testkey`) and are for testing;
  a throw-away key cannot update an app signed with another key.
* Every release file has a **SHA-256** in `SHA256SUMS.txt` next to it. Checking it protects against a damaged download, not
  against a compromised release; for that, the signature is the check.
* The **R8 mapping file** and the **debug symbols** of a release stay with it: they let the developer read a crash report and
  contain no user data.

### Known limitations of the clients

* A person who is shown a stranger's QR code and scans it **trusts what the code says only as far as they trust where they got
  it**. The app tells the difference (trust levels) but cannot decide it.
* **iOS runs Bluetooth in the background only as far as the system allows** (the scan and the advertisement are slower and
  thinner with the app suspended, and "Low Power Mode" and "Background App Refresh" off make it worse). This is an
  availability limit, not a confidentiality one; the device matrix (`clients/apple/DEVICE_MATRIX.md`) records what was measured.
* The app **cannot tell a screen reader from a person**: what VoiceOver reads (names, messages) is what is on the screen.
