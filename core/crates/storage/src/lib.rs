//! # storage
//!
//! The on-device message store (SQLite). It persists:
//!
//! * an **append-only message log** with idempotent insertion (the same
//!   [`proto::MessageId`] is never stored twice),
//! * **tombstones**, so a deleted or expired message does not come back from
//!   another node,
//! * **quotas and retention**: a byte budget with a deterministic eviction policy,
//!   plus time-based expiry,
//! * **peers**: last-seen time, trust state and an opaque contact card,
//! * an **upload marker** per message, for the later internet-sync phase.
//!
//! The crate stays deterministic: it never reads the clock. Every operation that
//! depends on time takes `now_ms` from the caller, in the spirit of the sans-IO
//! core (see ADR-0001).
//!
//! # Trust boundary
//!
//! `storage` does **not** verify signatures. Callers must pass
//! only envelopes that `crypto::verify_envelope` has accepted. The
//! only integrity check performed here is that a stored blob still hashes to its
//! recorded [`proto::MessageId`] when it is read back.
//!
//! # Threading
//!
//! [`Storage`] owns one SQLite connection and is not `Sync`. Use one writer;
//! wrap it in a `Mutex` at a higher layer if several threads need it.
#![forbid(unsafe_code)]

mod config;
mod error;
mod key;
mod messages;
mod migrations;
mod peers;
mod store;
mod types;

pub use config::Config;
pub use error::{Result, StorageError};
pub use key::DbKey;
pub use store::Storage;
pub use types::{InsertOutcome, Peer, Priority, PurgeReport, Stats, StoredMessage, TrustState};
