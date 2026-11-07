# Crisis Network for Apple (iOS, iPadOS, macOS)

SwiftUI clients on top of the Rust core. Plan and phases: `I0` ... `I9` (see `CHANGELOG.md`). This folder is the
result of **phase I0**: the toolchain, the core as a Swift package, a placeholder app and CI. There is no
Bluetooth and no real screen yet.

```
clients/apple/
  Packages/CrisisCore/     Swift package: the Rust core (XCFramework), its UniFFI bindings, CoreSelfCheck
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
scripts/build-apple.sh                  # dev profile, arm64 slices; add --sqlcipher for the encrypted database

# 2. The Swift package tests on the Mac.
cd clients/apple/Packages/CrisisCore && swift test && cd -

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
