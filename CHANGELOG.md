# Changes: phase C2 and project-wide conventions

## Before you build

These files were written without access to a Rust toolchain, so **nothing here has been compiled or
tested yet**. The SQL schema and the key queries were checked against SQLite 3.45 with Python, and the
golden cryptographic vectors were produced and cross-checked with an independent Python implementation.

```text
cargo fmt --all
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
```

Places most likely to need a fix on the first compile (third-party API drift):
`crypto/src/x25519.rs`, `crypto/src/identity.rs`, `crypto/src/contact.rs`, and the `rusqlite` calls in
`storage/src/*.rs` (`rusqlite 0.40`).

## What changed

- **Project renamed** to `crisis-network-project`.
- **Crate names** lose the `kriz-` prefix: `proto`, `crypto`, `mesh`, `storage`, `cli` (binary `crisis`).
- **Domain-separation tags** changed from `kriz/v1/...` to `crisis/v1/...`; the golden vectors were regenerated.
- **English everywhere:** all comments, doc comments, error messages and documents.
- **mdBook** documentation in `docs/`, replacing the loose Markdown files.
- **Phase C2:** the new `storage` crate.
- **CI:** SQLCipher job and mdBook/rustdoc job added.

## Files to delete from your working copy

These are replaced by the new layout:

```text
C0-C1-NOTLAR.md
docs/README.md
docs/THREAT_MODEL.md
docs/protocol/PROTOCOL.md
docs/adr/                      (the old Turkish-named ADR files)
core/crates/storage/src/lib.rs (replaced by the new storage sources)
```

Also rename the repository folder to `crisis-network-project`.
