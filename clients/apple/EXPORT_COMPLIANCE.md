# Export compliance of the Apple apps (phase I9)

App Store Connect asks, for every build, whether the app uses encryption, and the answer is a legal statement made by the
publisher. **This file records the facts the answer rests on and the choices; it is not legal advice.** Ask a lawyer (or the
export-control office of the organisation that publishes the app) before the first submission.

## What the app does with encryption

| What | Algorithm | Where | Provided by |
| --- | --- | --- | --- |
| Signature of every message and contact card | Ed25519 | Rust core (`ed25519-dalek`) | the app |
| Key agreement for a direct message | X25519 | Rust core (`x25519-dalek`) | the app |
| Key derivation | HKDF with SHA-256 | Rust core (`hkdf`, `sha2`) | the app |
| Content of a direct message (sealed box to one recipient) | ChaCha20-Poly1305 | Rust core (`chacha20poly1305`) | the app |
| The database on the device | SQLCipher (AES-256) with OpenSSL, built into the app | Rust core (`rusqlite`, `bundled-sqlcipher-vendored-openssl`) | the app |
| Random numbers | the system generator | Rust core (`getrandom`) | the OS |
| Storage of the keys | Keychain | Swift (`KeychainSecretStore`) | the OS |
| Bluetooth link | the system's | CoreBluetooth | the OS |

The app has **no network access, no server and no account**: encryption protects messages between devices and the database on
one device. The source code is public (GPL-3.0-only, in this repository).

## The answer the project gives

`ITSAppUsesNonExemptEncryption` is **`true`** in `project.yml` (iOS and macOS). Reason: the app implements encryption of its own
(end-to-end encryption of messages and an encrypted database) **in addition to** what the operating system provides, which is
the case Apple's question is about. The earlier value (`false`, a placeholder of phase I0) claimed an exemption that nobody had
checked; with `true`, App Store Connect asks the follow-up questions instead and the publisher answers them once per version.
`scripts/check-mac-app.sh` and `ReleaseReadinessTests` check that the app says what this file says.

## What the follow-up questions ask, and the usual ways through them (to confirm with a lawyer)

1. *Does the app qualify for an exemption in Category 5 Part 2 of the US Export Administration Regulations?* The candidates for an
   app like this one are the "mass market" self-classification (a yearly self-classification report to BIS) and the treatment
   of **publicly available encryption source code** (the source is public; this route asks for a notification by e-mail to BIS
   and NSA when the code is published or changes). They are not the same; which one applies depends on facts about the publisher.
2. *Is the app available in France?* France has had its own declaration rules for encryption; whether they apply to this app now
   is part of the same legal check. Distribution can be limited by country in App Store Connect if the answer is no.
3. When the answers are settled Apple gives an **encryption compliance code**. Put it into `project.yml` as
   `ITSEncryptionExportComplianceCode: <code>` (both targets) and TestFlight builds stop asking.

Until then a build uploaded to TestFlight waits at "Missing Compliance" in App Store Connect: the build is there, a person has to
answer the questions once, and testers get it after that. That is the intended safe behaviour, not an error of the pipeline.

## Also relevant before the first submission

* **Licence.** The repository is GPL-3.0-only. The App Store's terms are widely held to be hard to combine with the GPL for code
  the publisher does not hold the copyright of (see the README of the Apple client). Settle this before the first submission.
  Direct distribution (the notarized disk image, the release assets) does not have this problem.
* **App Privacy.** Answer "Data Not Collected" (matches `PrivacyInfo.xcprivacy`); there is no tracking.
* **Age rating and content.** Messages come from other people and are not moderated by the publisher: answer the "user generated
  content" questions accordingly: the app can block a sender on the device, and there is no server to report to.
