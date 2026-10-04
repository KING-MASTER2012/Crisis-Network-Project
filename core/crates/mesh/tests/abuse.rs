//! Behaviour against hostile or broken peers.

mod common;

use common::*;
use mesh::{Action, DisconnectReason, Event, MeshConfig, Notification, PeerId, PublishError};
use proto::link::Packet;
use proto::{Frame, split};

const NOW: u64 = 5_000;
const PEER: PeerId = PeerId(9);

fn mesh_with_peer(config: MeshConfig) -> mesh::Mesh {
    let mut m = new_mesh(0, config, NOW);
    m.handle(
        NOW,
        Event::PeerSeen {
            peer: PEER,
            link_mtu: 247,
        },
    )
    .unwrap();
    m
}

fn deliver(m: &mut mesh::Mesh, now: u64, bytes: Vec<u8>) -> Vec<Action> {
    m.handle(now, Event::PacketReceived { peer: PEER, bytes })
        .unwrap()
}

fn frame_packets(frame: &Frame) -> Vec<Vec<u8>> {
    split(frame, 246)
        .unwrap()
        .into_iter()
        .map(|f| Packet::Fragment(f).encode().unwrap())
        .collect()
}

fn signed_frame(text: &[u8]) -> Frame {
    let signer = crypto::Identity::from_seed(&[77; 32]).unwrap();
    Frame {
        hops_left: 3,
        envelope: signer.broadcast(text, NOW, 3600).unwrap(),
    }
}

#[test]
fn garbage_gets_a_peer_banned_and_it_cannot_come_back() {
    let mut m = mesh_with_peer(MeshConfig::default());
    let mut disconnected = false;
    for _ in 0..30 {
        let actions = deliver(&mut m, NOW, vec![0xEE; 8]);
        disconnected |= actions.iter().any(|a| {
            matches!(
                a,
                Action::Disconnect {
                    reason: DisconnectReason::Banned,
                    ..
                }
            )
        });
    }
    assert!(disconnected);
    assert_eq!(m.stats().peers_banned, 1);
    assert!(m.connected_peers().is_empty());

    // Reconnecting while banned is refused.
    let actions = m
        .handle(
            NOW + 1_000,
            Event::PeerSeen {
                peer: PEER,
                link_mtu: 247,
            },
        )
        .unwrap();
    assert!(actions.contains(&Action::Disconnect {
        peer: PEER,
        reason: DisconnectReason::Banned
    }));

    // After the ban expires the peer is accepted again.
    let later = NOW + m.config().ban_duration_ms + 1;
    m.handle(later, Event::Tick).unwrap();
    m.handle(
        later,
        Event::PeerSeen {
            peer: PEER,
            link_mtu: 247,
        },
    )
    .unwrap();
    assert_eq!(m.connected_peers(), vec![PEER]);
}

#[test]
fn occasional_garbage_is_forgiven_over_time() {
    let mut m = mesh_with_peer(MeshConfig::default());
    // One bad packet every 10 seconds costs 5 points but 10 points decay in between.
    for i in 0..50u64 {
        deliver(&mut m, NOW + i * 10_000, vec![0xEE; 8]);
    }
    assert_eq!(m.stats().peers_banned, 0);
    assert_eq!(m.stats().malformed_packets, 50);
}

#[test]
fn a_flooding_peer_is_rate_limited_and_eventually_banned() {
    let config = MeshConfig {
        packet_burst: 10,
        packet_rate_per_sec: 1,
        ..MeshConfig::default()
    };
    let mut m = mesh_with_peer(config);
    let ping = Packet::Inv(vec![proto::MessageId::new([1; 32])])
        .encode()
        .unwrap();
    for _ in 0..200 {
        deliver(&mut m, NOW, ping.clone());
    }
    assert!(m.stats().rate_limited > 0);
    assert_eq!(m.stats().peers_banned, 1);
}

#[test]
fn a_bad_signature_is_rejected_and_penalised() {
    let mut m = mesh_with_peer(MeshConfig::default());
    let mut frame = signed_frame(b"forged");
    let id = frame.envelope.message_id().unwrap();
    frame.envelope.signature[0] ^= 1;
    for packet in frame_packets(&frame) {
        deliver(&mut m, NOW, packet);
    }
    assert_eq!(m.stats().invalid_signatures, 1);
    assert_eq!(m.stats().frames_accepted, 0);
    assert!(!m.storage().contains(&id, NOW).unwrap());
    assert_eq!(m.stats().peers_banned, 0);
}

#[test]
fn a_correctly_signed_frame_is_accepted_even_if_unsolicited() {
    let mut m = mesh_with_peer(MeshConfig::default());
    let frame = signed_frame(b"legit");
    let id = frame.envelope.message_id().unwrap();
    let mut notes = Vec::new();
    for packet in frame_packets(&frame) {
        for action in deliver(&mut m, NOW, packet) {
            if let Action::Notify(n) = action {
                notes.push(n);
            }
        }
    }
    assert_eq!(m.stats().frames_accepted, 1);
    assert!(m.storage().contains(&id, NOW).unwrap());
    assert!(matches!(
        notes.as_slice(),
        [Notification::MessageReceived { body, .. }] if body == b"legit"
    ));
}

#[test]
fn inflated_hop_counts_are_capped() {
    let config = MeshConfig {
        max_hops: 3,
        default_hops: 2,
        fallback_hops: 1,
        ..MeshConfig::default()
    };
    let mut m = mesh_with_peer(config);
    let mut frame = signed_frame(b"far reaching");
    frame.hops_left = 16; // a hostile relay claims a huge budget
    let id = frame.envelope.message_id().unwrap();
    for packet in frame_packets(&frame) {
        deliver(&mut m, NOW, packet);
    }
    assert!(m.storage().contains(&id, NOW).unwrap());

    // A second peer asks for it: the frame goes out with the capped budget minus one.
    let other = PeerId(10);
    m.handle(
        NOW,
        Event::PeerSeen {
            peer: other,
            link_mtu: 247,
        },
    )
    .unwrap();
    let actions = m
        .handle(
            NOW,
            Event::PacketReceived {
                peer: other,
                bytes: Packet::Want(vec![id]).encode().unwrap(),
            },
        )
        .unwrap();
    let sent: Vec<Vec<u8>> = actions
        .into_iter()
        .filter_map(|a| match a {
            Action::SendPacket { peer, bytes } if peer == other => Some(bytes),
            _ => None,
        })
        .collect();
    let fragments: Vec<proto::Fragment> = sent
        .iter()
        .filter_map(|b| match Packet::decode(b) {
            Ok(Packet::Fragment(f)) => Some(f),
            _ => None,
        })
        .collect();
    let out = proto::reassemble(&fragments).unwrap();
    assert_eq!(out.hops_left, 2);
}

#[test]
fn conflicting_fragments_are_penalised() {
    let mut m = mesh_with_peer(MeshConfig::default());
    let frame = signed_frame(&vec![5u8; 600]);
    let parts = split(&frame, 64).unwrap();
    deliver(
        &mut m,
        NOW,
        Packet::Fragment(parts[0].clone()).encode().unwrap(),
    );
    let mut evil = parts[0].clone();
    evil.data[0] ^= 1;
    deliver(&mut m, NOW, Packet::Fragment(evil).encode().unwrap());
    assert_eq!(m.stats().fragments_rejected, 1);
}

#[test]
fn incomplete_transfers_expire() {
    let mut m = mesh_with_peer(MeshConfig::default());
    let frame = signed_frame(&vec![5u8; 600]);
    let parts = split(&frame, 64).unwrap();
    deliver(
        &mut m,
        NOW,
        Packet::Fragment(parts[0].clone()).encode().unwrap(),
    );
    let later = NOW + m.config().reassembly_timeout_ms;
    m.handle(later, Event::Tick).unwrap();
    assert_eq!(m.stats().expired_transfers, 1);
}

#[test]
fn a_peer_with_a_tiny_mtu_is_refused() {
    let mut m = new_mesh(0, MeshConfig::default(), NOW);
    let actions = m
        .handle(
            NOW,
            Event::PeerSeen {
                peer: PEER,
                link_mtu: 20,
            },
        )
        .unwrap();
    assert!(actions.contains(&Action::Disconnect {
        peer: PEER,
        reason: DisconnectReason::MtuTooSmall
    }));
    assert!(m.connected_peers().is_empty());
}

#[test]
fn only_our_own_signed_messages_can_be_published() {
    let mut m = new_mesh(0, MeshConfig::default(), NOW);
    let stranger = crypto::Identity::from_seed(&[55; 32]).unwrap();
    let foreign = stranger.broadcast(b"not mine", NOW, 60).unwrap();
    let actions = m
        .handle(
            NOW,
            Event::Publish {
                envelope: Box::new(foreign),
            },
        )
        .unwrap();
    assert!(actions.iter().any(|a| matches!(
        a,
        Action::Notify(Notification::PublishRejected {
            reason: PublishError::Invalid,
            ..
        })
    )));

    let own = crypto::Identity::from_seed(&[1; 32])
        .unwrap()
        .broadcast(b"mine", NOW, 60)
        .unwrap();
    let published = m
        .handle(
            NOW,
            Event::Publish {
                envelope: Box::new(own.clone()),
            },
        )
        .unwrap();
    assert!(
        published
            .iter()
            .any(|a| matches!(a, Action::Notify(Notification::Published { .. })))
    );
    // Publishing it twice is reported, not silently accepted.
    let again = m
        .handle(
            NOW,
            Event::Publish {
                envelope: Box::new(own),
            },
        )
        .unwrap();
    assert!(again.iter().any(|a| matches!(
        a,
        Action::Notify(Notification::PublishRejected {
            reason: PublishError::Duplicate,
            ..
        })
    )));
}

#[test]
fn a_mesh_refuses_a_store_that_belongs_to_another_identity() {
    let identity = crypto::Identity::from_seed(&[1; 32]).unwrap();
    let storage = storage::Storage::open_in_memory(storage::Config::default()).unwrap();
    let result = mesh::Mesh::new(MeshConfig::default(), identity, storage, NOW);
    assert!(matches!(result, Err(mesh::MeshError::LocalNodeMismatch)));
}
