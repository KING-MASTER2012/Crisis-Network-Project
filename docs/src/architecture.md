# Architecture

The core is a set of small crates. Dependencies point downwards.

| Crate | Responsibility |
|---|---|
| `proto` | Wire format: envelope, message id, hop frame, fragmentation. Sans-IO; no signing. |
| `crypto` | Identity, signatures, contact cards, sealed-box encryption. |
| `storage` | On-device SQLite message store: log, tombstones, quotas, peers. |
| `mesh` | Sans-IO propagation, synchronisation, fragment reassembly and abuse protection. |
| `engine` | High-level API tying identity, storage and mesh together. |
| `sim` | Deterministic network simulator: many engines, one virtual clock. |
| `cli` | The `crisis` command-line tool: `simulate`, `identity`, `node`. |
| `ffi` | The versioned UniFFI surface for the Kotlin and Swift clients (library `crisis_core`). |

## Sans-IO

`mesh` and `engine` never touch a radio, a socket or a clock. The platform feeds them events
together with the current time and carries out the actions they return (see
[ADR-0001](adr/0001-sans-io-core.md)). This makes the whole network testable in the simulator,
deterministically and byte for byte.

## Trust boundary

`storage` does not verify signatures. Only envelopes accepted by `crypto::verify_envelope`
may be passed to it. Signatures are always verified before a message is stored or forwarded.
