//! Property tests: whatever a hostile network and a misbehaving platform throw at a
//! `Mesh`, it must not panic or fail, must keep its promises about what it sends, must
//! never accept a forged message, and must behave identically when replayed.

use std::collections::{BTreeMap, BTreeSet};

use crypto::{ContactCard, Identity};
use mesh::{Action, Event, MIN_LINK_MTU, Mesh, MeshConfig, Notification, PeerId};
use proptest::collection::vec;
use proptest::prelude::*;
use proto::limits::MAX_HOPS;
use proto::link::{MAX_IDS_PER_PACKET, Packet};
use proto::{Envelope, Frame, MessageId, split};
use storage::{Config as StoreConfig, Storage};

const START_MS: u64 = 10_000;
const STORE_BYTES: u64 = 20_000;
const PEERS: u8 = 6;
/// Payloads of forged messages start with this; no notification may ever carry it.
const FORGED_MARK: &[u8] = b"forged-";

/// A deterministic set of messages to feed the mesh.
struct Pool {
    valid: Vec<Envelope>,
    forged: Vec<Envelope>,
    ids: Vec<MessageId>,
}

fn identity(seed: u8) -> Identity {
    Identity::from_seed(&[seed; 32]).unwrap()
}

impl Pool {
    fn new(me: &ContactCard) -> Self {
        let mut valid = Vec::new();
        // Broadcasts of very different sizes, so that some need many fragments.
        for i in 0..8u8 {
            let payload = vec![i; 20 + usize::from(i) * 120];
            let mut text = format!("ok-{i}-").into_bytes();
            text.extend_from_slice(&payload);
            valid.push(
                identity(100 + i % 4)
                    .broadcast(&text, START_MS, 3600)
                    .unwrap(),
            );
        }
        // Direct messages to the node under test, with reproducible ephemeral keys.
        for i in 0..3u8 {
            let eph = [200 + i; 32];
            let text = format!("direct-{i}");
            valid.push(
                identity(110)
                    .direct_with_ephemeral(me, text.as_bytes(), START_MS, 3600, &eph)
                    .unwrap(),
            );
        }
        // An ack for a message the node under test never sent.
        let first = valid[0].message_id().unwrap();
        valid.push(
            identity(111)
                .ack(&me.node_id(), &first, START_MS, 3600)
                .unwrap(),
        );

        // Forged messages: well-formed, but the signature does not match.
        let mut forged = Vec::new();
        for i in 0..4u8 {
            let mut text = FORGED_MARK.to_vec();
            text.push(i);
            let mut env = identity(120 + i).broadcast(&text, START_MS, 3600).unwrap();
            env.signature[usize::from(i) * 7] ^= 0x40;
            forged.push(env);
        }

        let mut ids: Vec<MessageId> = valid
            .iter()
            .chain(forged.iter())
            .map(|e| e.message_id().unwrap())
            .collect();
        ids.extend((1..=4u8).map(|b| MessageId::new([b; 32])));
        Self { valid, forged, ids }
    }
}

#[derive(Clone, Debug)]
enum Step {
    Seen {
        peer: u8,
        mtu: u16,
    },
    Lost {
        peer: u8,
    },
    Garbage {
        peer: u8,
        bytes: Vec<u8>,
    },
    Ids {
        peer: u8,
        want: bool,
        picks: Vec<u8>,
    },
    Frame {
        peer: u8,
        pick: u8,
        forged: bool,
        hops: u8,
        mtu: u16,
        drop_mask: u16,
    },
    Publish {
        foreign: bool,
    },
    Tick,
    Advance(u32),
    Rewind(u32),
}

fn arb_step() -> impl Strategy<Value = Step> {
    prop_oneof![
        3 => (0..PEERS, 0u16..600).prop_map(|(peer, mtu)| Step::Seen { peer, mtu }),
        1 => (0..PEERS).prop_map(|peer| Step::Lost { peer }),
        2 => (0..PEERS, vec(any::<u8>(), 0..300))
            .prop_map(|(peer, bytes)| Step::Garbage { peer, bytes }),
        4 => (0..PEERS, any::<bool>(), vec(any::<u8>(), 1..20))
            .prop_map(|(peer, want, picks)| Step::Ids { peer, want, picks }),
        6 => (0..PEERS, any::<u8>(), any::<bool>(), 0..=MAX_HOPS, 64u16..600, any::<u16>())
            .prop_map(|(peer, pick, forged, hops, mtu, drop_mask)| Step::Frame {
                peer, pick, forged, hops, mtu, drop_mask,
            }),
        2 => any::<bool>().prop_map(|foreign| Step::Publish { foreign }),
        3 => Just(Step::Tick),
        3 => (1u32..120_000).prop_map(Step::Advance),
        1 => (1u32..60_000).prop_map(Step::Rewind),
    ]
}

fn build_mesh() -> Mesh {
    let id = identity(1);
    let storage = Storage::open_in_memory(StoreConfig {
        max_total_bytes: STORE_BYTES,
        local_node: Some(id.node_id()),
        ..StoreConfig::default()
    })
    .unwrap();
    Mesh::new(MeshConfig::default(), id, storage, START_MS).unwrap()
}

fn fail(message: impl Into<String>) -> TestCaseError {
    TestCaseError::fail(message.into())
}

/// Turns a step into the events it stands for.
fn events_for(
    step: &Step,
    pool: &Pool,
    now: u64,
    serial: usize,
) -> Result<Vec<Event>, TestCaseError> {
    let peer_id = |p: &u8| PeerId(u64::from(*p));
    Ok(match step {
        Step::Seen { peer, mtu } => vec![Event::PeerSeen {
            peer: peer_id(peer),
            link_mtu: usize::from(*mtu),
        }],
        Step::Lost { peer } => vec![Event::PeerLost {
            peer: peer_id(peer),
        }],
        Step::Garbage { peer, bytes } => vec![Event::PacketReceived {
            peer: peer_id(peer),
            bytes: bytes.clone(),
        }],
        Step::Ids { peer, want, picks } => {
            let ids: Vec<MessageId> = picks
                .iter()
                .take(MAX_IDS_PER_PACKET)
                .map(|p| pool.ids[usize::from(*p) % pool.ids.len()])
                .collect();
            let packet = if *want {
                Packet::Want(ids)
            } else {
                Packet::Inv(ids)
            };
            vec![Event::PacketReceived {
                peer: peer_id(peer),
                bytes: packet.encode().map_err(|e| fail(e.to_string()))?,
            }]
        }
        Step::Frame {
            peer,
            pick,
            forged,
            hops,
            mtu,
            drop_mask,
        } => {
            let source = if *forged { &pool.forged } else { &pool.valid };
            let envelope = source[usize::from(*pick) % source.len()].clone();
            let frame = Frame {
                hops_left: *hops,
                envelope,
            };
            let fragments =
                split(&frame, usize::from(*mtu) - 1).map_err(|e| fail(e.to_string()))?;
            let mut events = Vec::new();
            for (i, fragment) in fragments.into_iter().enumerate() {
                if (*drop_mask >> (i % 16)) & 1 == 1 {
                    continue; // this fragment is lost on the way
                }
                let bytes = Packet::Fragment(fragment)
                    .encode()
                    .map_err(|e| fail(e.to_string()))?;
                events.push(Event::PacketReceived {
                    peer: peer_id(peer),
                    bytes,
                });
            }
            events
        }
        Step::Publish { foreign } => {
            let signer = if *foreign { identity(2) } else { identity(1) };
            let text = format!("local-{serial}");
            let envelope = signer
                .broadcast(text.as_bytes(), now, 3600)
                .map_err(|e| fail(e.to_string()))?;
            vec![Event::Publish {
                envelope: Box::new(envelope),
            }]
        }
        Step::Tick | Step::Advance(_) | Step::Rewind(_) => vec![Event::Tick],
    })
}

/// Runs the steps and checks the invariants after every event. Returns everything the
/// mesh asked for, so that two runs can be compared.
fn run(steps: &[Step]) -> Result<Vec<Vec<Action>>, TestCaseError> {
    let mut mesh = build_mesh();
    let pool = Pool::new(&mesh.identity().contact_card());
    let flush_limit = mesh.config().flush_packets_per_peer;
    let mut now = START_MS;
    let mut mtus: BTreeMap<u64, usize> = BTreeMap::new();
    let mut transcript = Vec::new();

    for (serial, step) in steps.iter().enumerate() {
        match step {
            Step::Advance(ms) => now = now.saturating_add(u64::from(*ms)),
            Step::Rewind(ms) => now = now.saturating_sub(u64::from(*ms)),
            _ => {}
        }
        for event in events_for(step, &pool, now, serial)? {
            let actions = mesh
                .handle(now, event.clone())
                .map_err(|e| fail(format!("handle failed: {e}")))?;

            let connected: BTreeSet<u64> = mesh.connected_peers().iter().map(|p| p.0).collect();
            if let Event::PeerSeen { peer, link_mtu } = &event
                && connected.contains(&peer.0)
                && *link_mtu >= MIN_LINK_MTU
            {
                mtus.insert(peer.0, *link_mtu);
            }
            mtus.retain(|peer, _| connected.contains(peer));

            let mut per_peer: BTreeMap<u64, usize> = BTreeMap::new();
            for action in &actions {
                match action {
                    Action::SendPacket { peer, bytes } => {
                        *per_peer.entry(peer.0).or_default() += 1;
                        prop_assert!(
                            connected.contains(&peer.0),
                            "packet for an unconnected peer"
                        );
                        let mtu = mtus.get(&peer.0).copied().unwrap_or(usize::MAX);
                        prop_assert!(
                            bytes.len() <= mtu,
                            "{} bytes on a {mtu}-byte link",
                            bytes.len()
                        );
                        prop_assert!(
                            Packet::decode(bytes).is_ok(),
                            "the mesh sent a packet it cannot parse"
                        );
                    }
                    Action::SetTimer { at_ms } => prop_assert!(*at_ms >= now),
                    Action::Notify(Notification::MessageReceived { body, .. }) => {
                        prop_assert!(
                            !body.starts_with(FORGED_MARK),
                            "a forged message was accepted"
                        );
                    }
                    _ => {}
                }
            }
            for (peer, count) in per_peer {
                prop_assert!(
                    count <= flush_limit,
                    "peer {peer} was sent {count} packets in one call"
                );
            }
            transcript.push(actions);
        }
    }

    mesh.storage()
        .check_integrity()
        .map_err(|e| fail(format!("store corrupt: {e}")))?;
    let bytes = mesh
        .storage()
        .stats()
        .map_err(|e| fail(e.to_string()))?
        .total_bytes;
    prop_assert!(bytes <= STORE_BYTES, "store grew to {bytes} bytes");
    Ok(transcript)
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(48))]

    /// No sequence of events makes the mesh fail, send something unparseable or oversized,
    /// exceed its per-call budget, accept a forged message or outgrow its store.
    #[test]
    fn hostile_event_sequences_keep_the_invariants(steps in vec(arb_step(), 1..80)) {
        run(&steps)?;
    }

    /// The same events at the same times always produce the same actions.
    #[test]
    fn the_mesh_is_deterministic(steps in vec(arb_step(), 1..60)) {
        let first = run(&steps)?;
        let second = run(&steps)?;
        prop_assert_eq!(first, second);
    }
}
