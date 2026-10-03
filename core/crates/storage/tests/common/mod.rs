//! Shared helpers for the integration tests.
//!
//! `storage` never verifies signatures, so the envelopes built here carry a dummy
//! signature. What matters is that they are structurally valid and have distinct ids.

#![allow(dead_code, missing_docs)]

use proto::{Envelope, EnvelopeBody, Kind, NodeId};
use storage::{Config, Storage};

pub fn node(b: u8) -> NodeId {
    NodeId::new([b; 32])
}

/// Builds an envelope. `size` is the desired *encoded* size for Broadcast and
/// Direct messages (Ack envelopes always encode to 178 bytes). `nonce` makes the
/// message unique. `recipient` is only used for Direct and Ack.
pub fn envelope(
    kind: Kind,
    sender: u8,
    recipient: u8,
    nonce: u32,
    size: usize,
    lifetime_secs: u32,
) -> Envelope {
    let (recipient, payload) = match kind {
        Kind::Broadcast => {
            let mut p = vec![0u8; size.saturating_sub(114).max(4)];
            p[..4].copy_from_slice(&nonce.to_be_bytes());
            (None, p)
        }
        Kind::Direct => {
            let mut p = vec![0u8; size.saturating_sub(146).max(48)];
            p[..4].copy_from_slice(&nonce.to_be_bytes());
            (Some(node(recipient)), p)
        }
        Kind::Ack => {
            let mut p = vec![0u8; 32];
            p[..4].copy_from_slice(&nonce.to_be_bytes());
            (Some(node(recipient)), p)
        }
    };
    Envelope {
        body: EnvelopeBody {
            kind,
            sender: node(sender),
            recipient,
            created_at_ms: u64::from(nonce),
            lifetime_secs,
            ext: vec![],
            payload,
        },
        signature: [0xAA; 64],
    }
}

pub fn broadcast(nonce: u32) -> Envelope {
    envelope(Kind::Broadcast, 1, 0, nonce, 200, 3600)
}

pub fn store() -> Storage {
    Storage::open_in_memory(Config::default()).unwrap()
}

pub fn store_with(config: Config) -> Storage {
    Storage::open_in_memory(config).unwrap()
}

/// A configuration with a small budget so quota behaviour is easy to trigger.
pub fn small(max_total_bytes: u64) -> Config {
    Config {
        max_total_bytes,
        ..Config::default()
    }
}
