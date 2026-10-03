# Crisis Network Project

A messaging system for when the usual infrastructure is gone: phones exchange messages directly over
Bluetooth Low Energy, keep them in a local SQLite database, and synchronise with a server when internet
access returns.

The shared Rust core lives in `core/`. Platform apps (Android, Apple, Desktop) will be added on top of it.
See the book in `docs/` for the protocol specification, the threat model, the architecture and the
decision records.

## Repository layout

| Path | Contents |
|---|---|
| `core/crates/proto` | Wire format (envelope, ids, hop frame, fragmentation) |
| `core/crates/crypto` | Identity, signatures, contact cards, sealed box |
| `core/crates/storage` | On-device SQLite message store |
| `core/crates/mesh` | Mesh logic (placeholder) |
| `core/crates/cli` | Command-line client, binary `crisis` (placeholder) |
| `docs/` | mdBook documentation |

## Development

```text
cargo fmt --all
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
```

Encrypted storage (SQLCipher) is opt-in and needs a C toolchain and Perl:

```text
cargo test -p storage --no-default-features --features sqlcipher
```

Documentation:

```text
cargo install mdbook --locked
mdbook serve docs
cargo doc --workspace --no-deps --open
```

All code comments and documentation are written in English (see ADR-0007).
