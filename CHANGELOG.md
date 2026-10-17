# Changes: phase C5 (hardening)

## Before you build

These files were written without access to a Rust toolchain, so **nothing here has been compiled or
tested yet**. The SQL (new index and the pinned-bytes query) was checked with Python's `sqlite3`.

```text
cargo fmt --all
cargo test --workspace --all-features
cargo clippy --workspace --all-features -- -D warnings
```

Most likely to need a small fix on the first compile: the new test files
(`mesh/tests/robustness.rs`, `mesh/tests/hardening.rs`, `crypto/tests/robustness.rs`,
`storage/tests/pinning.rs`) and the fuzz targets (`core/fuzz`, separate workspace, nightly).

## Security review: findings and fixes

| # | Finding | Severity | Fix |
|---|---------|----------|-----|
| 1 | Anyone can address messages to us; all of them (and the acks we create) got `Own` priority, which is never evicted. A flood pinned the whole store and made the user's own sending fail with `QuotaExceeded`. | High | `Config::max_inbound_own_bytes` (default 8 MiB). Beyond it such messages keep their transit priority and are evictable. Migration V2 adds a covering index for the byte count. |
| 2 | A forged frame carrying the id of a message we were waiting for (the id does not cover the signature) cancelled the pending request before the signature was checked. | Medium | The request is cleared only after the signature verifies. |
| 3 | A peer could announce ids it never delivers and fill the whole request table, starving every other peer. | Medium | `MeshConfig::max_inflight_per_peer` (default 256). |
| 4 | Unbounded neighbour table (forged UDP source addresses, or a platform reporting endless peers). | Medium | `MeshConfig::max_peers` (default 128), `DisconnectReason::TooManyPeers` (not a ban). |
| 5 | Unbounded ban table. | Low | `MeshConfig::max_banned_peers` (default 1024); the ban ending soonest is forgotten first. The CLI's address ban list is bounded too, and only real bans are recorded. |
| 6 | `Want` packets could exceed a small link's MTU (a hostile `Inv` with 15 ids). | Low | Requests are chunked by the link's capacity. |
| 7 | After the link MTU shrank, queued fragments were still sent at the old size. | Low | Oversized queued packets are dropped on a smaller MTU. |
| 8 | CLI: remote message text was printed raw (terminal escape sequences, fake lines, bidi overrides). | Medium (CLI) | Control and invisible formatting characters are shown as `\u{..}`. |
| 9 | CLI: the key file was created with default permissions and then restricted (a readable window), written non-atomically, and read without a size limit. | Medium (CLI) | Created `0600`, written to a temporary file and renamed, size-limited read. |

## Open decisions (not changed, need your call)

- **Lifetime restarts at every hop.** Expiry is counted from the local receive time, so a relay chain can
  keep a message alive indefinitely, and an old message can be re-accepted (and re-notified) once its
  tombstone is gone. A fix is to cap expiry at `created_at_ms + lifetime + max clock skew`, but that needs
  a decision on clock skew tolerance (phones offline may have wrong clocks). Suggest an ADR.
- **Sybil flooding of transit traffic.** Signing keys are free, so per-sender limits do not stop an
  attacker; per-peer rate limits and quotas bound it, and flooding can still evict older transit
  messages. Real mitigation needs a trust model (for example, preferring messages from known contacts).
- **Ban evasion.** Bans are keyed by the platform's `PeerId`; BLE address rotation or a new UDP port gets a
  fresh identity. This is a platform-layer property.
- Fragment bookkeeping overhead is not charged against the reassembly byte budgets (bounded by
  `max_peers` and the per-peer transfer cap).

## Tests added

- `mesh/tests/robustness.rs`: random hostile event sequences (garbage, forged and valid frames, lost
  fragments, MTU changes, time going backwards) check: no failure, every packet parses and fits its link,
  per-call limits, no forged message accepted, store stays within budget; plus replay determinism.
- `mesh/tests/hardening.rs`, `storage/tests/pinning.rs`: regression tests for findings 1-7.
- `crypto/tests/robustness.rs`: garbage cards/boxes/envelopes, small-order keys, non-canonical signatures.
- `proto/tests/properties.rs`: canonical-form and message-id-coverage properties.
- `cli`: terminal sanitising and key file tests.

## Fuzzing

`core/fuzz` is a `cargo-fuzz` crate outside the main workspace (nightly toolchain). Targets:
`packet_decode`, `frame_decode`, `fragment_roundtrip`, `crypto_inputs`, `storage_ops`, `mesh_events`.

```text
cd core
cargo +nightly fuzz run mesh_events -- -max_total_time=60
```

CI runs each target for 30 seconds (`fuzz-smoke`). The mdBook documentation stays postponed.

## Deliberately unchanged: phase C6 (FFI) is next.
