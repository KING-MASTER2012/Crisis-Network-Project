//! Regression tests for the resource-exhaustion and request-cancellation problems found
//! during the phase C5 security review.

mod common;

use common::*;
use mesh::{Action, DisconnectReason, Event, Mesh, MeshConfig, PeerId};
use proto::link::Packet;
use proto::{Frame, MessageId, split};

const NOW: u64 = 5_000;
const MTU: usize = 247;

fn connect(m: &mut Mesh, peer: PeerId) -> Vec<Action> {
    m.handle(
        NOW,
        Event::PeerSeen {
            peer,
            link_mtu: MTU,
        },
    )
    .unwrap()
}

fn deliver(m: &mut Mesh, now: u64, peer: PeerId, bytes: Vec<u8>) -> Vec<Action> {
    m.handle(now, Event::PacketReceived { peer, bytes })
        .unwrap()
}

/// The ids requested by the `Want` packets among `actions`, with the peer each went to.
fn wants(actions: &[Action]) -> Vec<(PeerId, MessageId)> {
    let mut out = Vec::new();
    for action in actions {
        if let Action::SendPacket { peer, bytes } = action
            && let Ok(Packet::Want(ids)) = Packet::decode(bytes)
        {
            out.extend(ids.into_iter().map(|id| (*peer, id)));
        }
    }
    out
}

fn signed_frame(text: &[u8]) -> Frame {
    let signer = crypto::Identity::from_seed(&[77; 32]).unwrap();
    Frame {
        hops_left: 3,
        envelope: signer.broadcast(text, NOW, 3600).unwrap(),
    }
}

fn frame_packets(frame: &Frame) -> Vec<Vec<u8>> {
    split(frame, MTU - 1)
        .unwrap()
        .into_iter()
        .map(|f| Packet::Fragment(f).encode().unwrap())
        .collect()
}

#[test]
fn a_forged_frame_does_not_cancel_a_pending_request() {
    let honest = PeerId(9);
    let attacker = PeerId(10);
    let mut m = new_mesh(0, MeshConfig::default(), NOW);
    connect(&mut m, honest);
    connect(&mut m, attacker);

    // The honest peer announces a message; we ask it for the message.
    let frame = signed_frame(b"real message");
    let id = frame.envelope.message_id().unwrap();
    let actions = deliver(&mut m, NOW, honest, Packet::Inv(vec![id]).encode().unwrap());
    assert_eq!(wants(&actions), vec![(honest, id)]);

    // The attacker answers first with the same body but a forged signature. The message
    // id does not cover the signature, so the frame carries the id we are waiting for.
    let mut forged = frame.clone();
    forged.envelope.signature[0] ^= 1;
    for packet in frame_packets(&forged) {
        deliver(&mut m, NOW + 10, attacker, packet);
    }
    assert_eq!(m.stats().invalid_signatures, 1);
    assert!(!m.storage().contains(&id, NOW + 10).unwrap());

    // Our request is still pending: when it times out it is sent again.
    let later = NOW + m.config().want_timeout_ms + 1;
    let actions = m.handle(later, Event::Tick).unwrap();
    assert_eq!(wants(&actions), vec![(honest, id)]);
    assert_eq!(m.stats().want_gave_up, 0);

    // And the genuine frame still completes the transfer afterwards.
    for packet in frame_packets(&frame) {
        deliver(&mut m, later + 1, honest, packet);
    }
    assert!(m.storage().contains(&id, later + 1).unwrap());
}

#[test]
fn one_peer_cannot_fill_the_request_table() {
    let config = MeshConfig {
        max_inflight: 100,
        max_inflight_per_peer: 10,
        ..MeshConfig::default()
    };
    let greedy = PeerId(1);
    let other = PeerId(2);
    let mut m = new_mesh(0, config, NOW);
    connect(&mut m, greedy);
    connect(&mut m, other);

    // The greedy peer announces 45 messages it will never deliver.
    let mut asked = Vec::new();
    for chunk in (1..=45u8).collect::<Vec<_>>().chunks(15) {
        let ids: Vec<MessageId> = chunk.iter().map(|b| MessageId::new([*b; 32])).collect();
        let actions = deliver(&mut m, NOW, greedy, Packet::Inv(ids).encode().unwrap());
        asked.extend(wants(&actions));
    }
    assert_eq!(asked.len(), 10, "only the per-peer share may be requested");
    assert!(asked.iter().all(|(peer, _)| *peer == greedy));
    assert!(m.stats().inflight_full > 0);

    // Everybody else still gets served.
    let ids: Vec<MessageId> = (100..105u8).map(|b| MessageId::new([b; 32])).collect();
    let actions = deliver(
        &mut m,
        NOW,
        other,
        Packet::Inv(ids.clone()).encode().unwrap(),
    );
    let got: Vec<MessageId> = wants(&actions).into_iter().map(|(_, id)| id).collect();
    assert_eq!(got, ids);
}

#[test]
fn neighbours_beyond_the_limit_are_turned_away() {
    let config = MeshConfig {
        max_peers: 2,
        ..MeshConfig::default()
    };
    let mut m = new_mesh(0, config, NOW);
    assert!(connect(&mut m, PeerId(1)).iter().all(|a| !is_disconnect(a)));
    assert!(connect(&mut m, PeerId(2)).iter().all(|a| !is_disconnect(a)));

    let actions = connect(&mut m, PeerId(3));
    assert!(actions.contains(&Action::Disconnect {
        peer: PeerId(3),
        reason: DisconnectReason::TooManyPeers,
    }));
    assert_eq!(m.connected_peers(), vec![PeerId(1), PeerId(2)]);

    // A neighbour we already serve may report new link parameters.
    assert!(connect(&mut m, PeerId(1)).iter().all(|a| !is_disconnect(a)));

    // Once a slot is free, the turned-away neighbour is accepted. This was no ban.
    m.handle(NOW + 1, Event::PeerLost { peer: PeerId(1) })
        .unwrap();
    let actions = m
        .handle(
            NOW + 2,
            Event::PeerSeen {
                peer: PeerId(3),
                link_mtu: MTU,
            },
        )
        .unwrap();
    assert!(actions.iter().all(|a| !is_disconnect(a)));
    assert_eq!(m.connected_peers(), vec![PeerId(2), PeerId(3)]);
}

fn is_disconnect(action: &Action) -> bool {
    matches!(action, Action::Disconnect { .. })
}

#[test]
fn the_ban_table_is_bounded_and_forgets_the_soonest_ending_ban_first() {
    let config = MeshConfig {
        max_banned_peers: 3,
        ..MeshConfig::default()
    };
    let mut m = new_mesh(0, config, NOW);

    // Ban peers 1..=4, one second apart.
    for i in 1..=4u64 {
        let peer = PeerId(i);
        let now = NOW + i * 1_000;
        m.handle(
            now,
            Event::PeerSeen {
                peer,
                link_mtu: MTU,
            },
        )
        .unwrap();
        for _ in 0..30 {
            deliver(&mut m, now, peer, vec![0xEE; 8]);
        }
    }
    assert_eq!(m.stats().peers_banned, 4);
    assert!(m.connected_peers().is_empty());

    let probe = NOW + 10_000;
    // Peer 1's ban ended soonest, so it was forgotten to make room: it may reconnect.
    let actions = m
        .handle(
            probe,
            Event::PeerSeen {
                peer: PeerId(1),
                link_mtu: MTU,
            },
        )
        .unwrap();
    assert!(actions.iter().all(|a| !is_disconnect(a)));
    // Peer 4 is still banned.
    let actions = m
        .handle(
            probe,
            Event::PeerSeen {
                peer: PeerId(4),
                link_mtu: MTU,
            },
        )
        .unwrap();
    assert!(actions.contains(&Action::Disconnect {
        peer: PeerId(4),
        reason: DisconnectReason::Banned,
    }));
}

#[test]
fn requests_fit_a_small_link_however_many_ids_the_peer_announces() {
    const SMALL: usize = 64; // room for exactly one id per packet
    let peer = PeerId(1);
    let mut m = new_mesh(0, MeshConfig::default(), NOW);
    m.handle(
        NOW,
        Event::PeerSeen {
            peer,
            link_mtu: SMALL,
        },
    )
    .unwrap();

    // A hostile peer packs the maximum number of ids into one announcement.
    let ids: Vec<MessageId> = (1..=15u8).map(|b| MessageId::new([b; 32])).collect();
    let mut sent = deliver(
        &mut m,
        NOW,
        peer,
        Packet::Inv(ids.clone()).encode().unwrap(),
    );
    for step in 1..=10u64 {
        sent.extend(m.handle(NOW + step, Event::Tick).unwrap());
    }

    let mut requested = Vec::new();
    for action in &sent {
        if let Action::SendPacket { bytes, .. } = action {
            assert!(
                bytes.len() <= SMALL,
                "a {}-byte packet on a {SMALL}-byte link",
                bytes.len()
            );
        }
    }
    requested.extend(wants(&sent).into_iter().map(|(_, id)| id));
    assert_eq!(requested, ids);
}

#[test]
fn a_smaller_link_drops_fragments_queued_for_the_old_one() {
    let peer = PeerId(1);
    let signer = crypto::Identity::from_seed(&[1; 32]).unwrap(); // the identity of `new_mesh(0, ..)`
    let mut m = new_mesh(0, MeshConfig::default(), NOW);
    m.handle(
        NOW,
        Event::PeerSeen {
            peer,
            link_mtu: 128,
        },
    )
    .unwrap();

    // A large message of ours; the peer asks for it. At 128 bytes per packet it needs more
    // fragments than one flush sends, so some stay queued.
    let envelope = signer.broadcast(&[7u8; 1_000], NOW, 3600).unwrap();
    let id = envelope.message_id().unwrap();
    m.handle(
        NOW,
        Event::Publish {
            envelope: Box::new(envelope),
        },
    )
    .unwrap();
    let first = deliver(&mut m, NOW, peer, Packet::Want(vec![id]).encode().unwrap());
    let fragments_sent = first
        .iter()
        .filter(|a| matches!(a, Action::SendPacket { bytes, .. } if bytes.first() == Some(&3)))
        .count();
    assert!(fragments_sent > 0);

    // The link now only carries 64 bytes. Nothing larger may be sent from here on.
    m.handle(NOW + 1, Event::PeerSeen { peer, link_mtu: 64 })
        .unwrap();
    for step in 2..=20u64 {
        for action in m.handle(NOW + step, Event::Tick).unwrap() {
            if let Action::SendPacket { bytes, .. } = action {
                assert!(
                    bytes.len() <= 64,
                    "a {}-byte packet on a 64-byte link",
                    bytes.len()
                );
            }
        }
    }
}
