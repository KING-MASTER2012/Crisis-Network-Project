//! The whole mesh state machine under arbitrary event sequences, with both raw garbage and
//! well-formed (valid or forged) frames. It must not panic or fail, must only send packets
//! that parse, and must never accept a forged message.
#![no_main]

use std::sync::OnceLock;

use arbitrary::Arbitrary;
use crypto::Identity;
use libfuzzer_sys::fuzz_target;
use mesh::{Action, Event, Mesh, MeshConfig, Notification, PeerId};
use proto::link::Packet;
use proto::limits::MAX_HOPS;
use proto::{Envelope, Frame, split};
use storage::{Config as StoreConfig, Storage};

const FORGED: &[u8] = b"forged-";

#[derive(Arbitrary, Debug)]
enum Op {
    Seen { peer: u8, mtu: u16 },
    Lost { peer: u8 },
    Bytes { peer: u8, bytes: Vec<u8> },
    Frame { peer: u8, pick: u8, forged: bool, hops: u8, mtu: u16, drop_mask: u16 },
    Advance(u32),
    Tick,
}

struct Pool {
    valid: Vec<Envelope>,
    forged: Vec<Envelope>,
}

fn pool() -> &'static Pool {
    static POOL: OnceLock<Pool> = OnceLock::new();
    POOL.get_or_init(|| {
        let signer = |s: u8| Identity::from_seed(&[s; 32]).expect("seed");
        let valid = (0..6u8)
            .map(|i| {
                let payload = vec![i; 30 + usize::from(i) * 150];
                signer(50 + i % 3).broadcast(&payload, 10_000, 3600).expect("sign")
            })
            .collect();
        let forged = (0..3u8)
            .map(|i| {
                let mut text = FORGED.to_vec();
                text.push(i);
                let mut env = signer(60 + i).broadcast(&text, 10_000, 3600).expect("sign");
                env.signature[usize::from(i)] ^= 0x80;
                env
            })
            .collect();
        Pool { valid, forged }
    })
}

fuzz_target!(|ops: Vec<Op>| {
    let id = Identity::from_seed(&[1; 32]).expect("seed");
    let storage = Storage::open_in_memory(StoreConfig {
        max_total_bytes: 20_000,
        local_node: Some(id.node_id()),
        ..StoreConfig::default()
    })
    .expect("store");
    let mut mesh = Mesh::new(MeshConfig::default(), id, storage, 10_000).expect("mesh");
    let mut now = 10_000u64;

    for op in ops {
        let events = match op {
            Op::Seen { peer, mtu } => vec![Event::PeerSeen { peer: PeerId(u64::from(peer)), link_mtu: usize::from(mtu) }],
            Op::Lost { peer } => vec![Event::PeerLost { peer: PeerId(u64::from(peer)) }],
            Op::Bytes { peer, bytes } => vec![Event::PacketReceived { peer: PeerId(u64::from(peer)), bytes }],
            Op::Advance(ms) => {
                now = now.saturating_add(u64::from(ms));
                vec![Event::Tick]
            }
            Op::Tick => vec![Event::Tick],
            Op::Frame { peer, pick, forged, hops, mtu, drop_mask } => {
                let p = pool();
                let source = if forged { &p.forged } else { &p.valid };
                let frame = Frame {
                    hops_left: hops.min(MAX_HOPS),
                    envelope: source[usize::from(pick) % source.len()].clone(),
                };
                let size = usize::from(mtu).max(64) - 1;
                let Ok(parts) = split(&frame, size) else { continue };
                parts
                    .into_iter()
                    .enumerate()
                    .filter(|(i, _)| (drop_mask >> (i % 16)) & 1 == 0)
                    .map(|(_, f)| Event::PacketReceived {
                        peer: PeerId(u64::from(peer)),
                        bytes: Packet::Fragment(f).encode().expect("fragment encodes"),
                    })
                    .collect()
            }
        };
        for event in events {
            let actions = mesh.handle(now, event).expect("the mesh must not fail");
            for action in actions {
                match action {
                    Action::SendPacket { bytes, .. } => {
                        Packet::decode(&bytes).expect("the mesh sent an unparseable packet");
                    }
                    Action::Notify(Notification::MessageReceived { body, .. }) => {
                        assert!(!body.starts_with(FORGED), "a forged message was accepted");
                    }
                    _ => {}
                }
            }
        }
    }
    mesh.storage().check_integrity().expect("store integrity");
});
