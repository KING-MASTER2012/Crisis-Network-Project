# Crisis Network Project

A messaging system for when the usual infrastructure is gone: phones exchange messages directly over
Bluetooth Low Energy, keep them in a local SQLite database, and synchronise with a server when internet
access returns.

The shared Rust core lives in `core/`. Platform apps (Android, Apple, Desktop) will be added on top of it.
See the book in `docs/` for the protocol specification, the threat model, the architecture and the
decision records.

## Repository layout

| Path                  | Contents                                              |
|-----------------------|-------------------------------------------------------|
| `core/crates/proto`   | Wire format (envelope, ids, hop frame, fragmentation) |
| `core/crates/crypto`  | Identity, signatures, contact cards, sealed box       |
| `core/crates/storage` | On-device SQLite message store                        |
| `core/crates/mesh`    | Mesh logic (placeholder)                              |
| `core/crates/cli`     | Command-line client, binary `crisis` (placeholder)    |
| `docs/`               | mdBook documentation                                  |

## Development

```text
cargo fmt --all
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
```

The CI checks use all features:

```text
cargo clippy --workspace --all-features -- -D warnings
cargo test --workspace --all-features
```

Encrypted storage is decided by the final application: it enables a SQLCipher feature on
`rusqlite` in its own manifest, and `storage::Storage::open` detects SQLCipher at run time
(a key is refused when only plain SQLite is linked). The workspace itself never needs OpenSSL or Perl.

Documentation:

```text
cargo install mdbook --locked
mdbook serve docs
cargo doc --workspace --no-deps --open
```

All code comments and documentation are written in English (see ADR-0007).
