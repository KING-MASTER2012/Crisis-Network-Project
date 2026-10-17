//! The message store under arbitrary operation sequences: the byte budget and the
//! internal counters must always hold.
#![no_main]

use arbitrary::Arbitrary;
use libfuzzer_sys::fuzz_target;
use proto::limits::MAX_LIFETIME_SECS;
use proto::{Envelope, EnvelopeBody, Kind, NodeId};
use storage::{Config, Storage};

const BUDGET: u64 = 6_000;

#[derive(Arbitrary, Debug)]
struct Item {
    kind: u8,
    sender: u8,
    recipient: u8,
    nonce: u32,
    payload_len: u16,
    lifetime: u32,
}

#[derive(Arbitrary, Debug)]
enum Op {
    Insert(Item),
    Delete(Item),
    Purge,
    Advance(u32),
}

fn node(b: u8) -> NodeId {
    NodeId::new([b % 4; 32])
}

fn envelope(item: &Item) -> Envelope {
    let (kind, recipient, mut payload) = match item.kind % 3 {
        0 => (Kind::Broadcast, None, vec![0u8; usize::from(item.payload_len) % 600]),
        1 => (Kind::Direct, Some(node(item.recipient)), vec![0u8; 48 + usize::from(item.payload_len) % 600]),
        _ => (Kind::Ack, Some(node(item.recipient)), vec![0u8; 32]),
    };
    let nonce = item.nonce.to_be_bytes();
    let n = payload.len().min(4);
    payload[..n].copy_from_slice(&nonce[..n]);
    Envelope {
        body: EnvelopeBody {
            kind,
            sender: node(item.sender),
            recipient,
            created_at_ms: u64::from(item.nonce),
            lifetime_secs: item.lifetime % MAX_LIFETIME_SECS + 1,
            ext: vec![],
            payload,
        },
        signature: [0xAA; 64],
    }
}

fuzz_target!(|ops: Vec<Op>| {
    let mut store = Storage::open_in_memory(Config {
        max_total_bytes: BUDGET,
        max_inbound_own_bytes: 1_500,
        max_tombstones: 50,
        local_node: Some(node(0)),
        ..Config::default()
    })
    .expect("in-memory store");
    let mut now = 1_000u64;
    for op in &ops {
        match op {
            Op::Insert(item) => {
                store.insert(&envelope(item), now).expect("insert");
            }
            Op::Delete(item) => {
                let id = envelope(item).message_id().expect("id");
                store.delete(&id, now).expect("delete");
            }
            Op::Purge => {
                store.purge_expired(now).expect("purge");
            }
            Op::Advance(ms) => now = now.saturating_add(u64::from(*ms)),
        }
        assert!(store.stats().expect("stats").total_bytes <= BUDGET);
    }
    store.check_integrity().expect("integrity");
});
