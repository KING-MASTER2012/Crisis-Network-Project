//! Several meshes talking to each other over a simulated network.

mod common;

use common::*;
use mesh::{MeshConfig, Notification};
use proto::Kind;

fn line(n: usize, config: MeshConfig, mtu: usize) -> Sim {
    let mut sim = Sim::new(n, config, mtu);
    for i in 0..n - 1 {
        sim.connect(i, i + 1);
    }
    sim
}

#[test]
fn a_broadcast_floods_a_line_exactly_once_per_node() {
    let mut sim = line(5, MeshConfig::default(), 247);
    let env = sim.broadcast(0, b"hello crisis");
    let id = env.message_id().unwrap();
    sim.publish(0, env);
    sim.run_for(2_000, 100);

    for i in 0..5 {
        assert!(sim.has_message(i, &id), "node {i} is missing the message");
    }
    assert!(
        sim.received_bodies(0).is_empty(),
        "the author is not notified"
    );
    for i in 1..5 {
        assert_eq!(
            sim.received_bodies(i),
            vec![b"hello crisis".to_vec()],
            "node {i}"
        );
        assert_eq!(sim.nodes[i].mesh.stats().frames_accepted, 1, "node {i}");
    }
}

#[test]
fn a_ring_delivers_each_message_once_despite_two_paths() {
    let mut sim = line(6, MeshConfig::default(), 247);
    sim.connect(5, 0);
    let env = sim.broadcast(0, b"around the ring");
    let id = env.message_id().unwrap();
    sim.publish(0, env);
    sim.run_for(2_000, 100);

    for i in 1..6 {
        assert!(sim.has_message(i, &id));
        assert_eq!(
            sim.received_bodies(i).len(),
            1,
            "node {i} was notified twice"
        );
        assert_eq!(sim.nodes[i].mesh.stats().frames_accepted, 1);
    }
}

#[test]
fn the_hop_budget_stops_propagation() {
    let config = MeshConfig {
        default_hops: 2,
        ..MeshConfig::default()
    };
    let mut sim = line(8, config, 247);
    let env = sim.broadcast(0, b"short range");
    let id = env.message_id().unwrap();
    sim.publish(0, env);
    sim.run_for(2_000, 100);

    // The origin plus three relays hold it: the frame is sent with 2, then 1, then 0 hops left.
    for i in 0..=3 {
        assert!(sim.has_message(i, &id), "node {i} should have it");
    }
    for i in 4..8 {
        assert!(!sim.has_message(i, &id), "node {i} should not have it");
    }
}

#[test]
fn a_direct_message_reaches_only_its_recipient_and_is_acknowledged() {
    let mut sim = line(4, MeshConfig::default(), 247);
    let env = sim.signers[0]
        .direct(&sim.cards[3], b"for node three only", sim.now, 3600)
        .unwrap();
    let id = env.message_id().unwrap();
    sim.publish(0, env);
    sim.run_for(2_000, 100);

    assert_eq!(
        sim.received_bodies(3),
        vec![b"for node three only".to_vec()]
    );
    assert!(sim.received_bodies(1).is_empty());
    assert!(sim.received_bodies(2).is_empty());
    // The recipient's signed Ack travelled back, and the author learned about it once.
    assert_eq!(sim.delivered(0), vec![id]);
    assert_eq!(sim.nodes[3].mesh.stats().acks_sent, 1);
    // Relays stored the (opaque) message too.
    assert!(sim.has_message(1, &id) && sim.has_message(2, &id));
    match &sim.nodes[3]
        .notes
        .iter()
        .find(|n| matches!(n, Notification::MessageReceived { .. }))
    {
        Some(Notification::MessageReceived { sender, kind, .. }) => {
            assert_eq!(*sender, sim.signers[0].node_id());
            assert_eq!(*kind, Kind::Direct);
        }
        other => panic!("unexpected notification: {other:?}"),
    }
}

#[test]
fn an_ack_from_the_wrong_node_does_not_count_as_delivery() {
    // Node 2 is the real recipient but is not connected, so it can never acknowledge.
    let mut sim = Sim::new(3, MeshConfig::default(), 247);
    sim.connect(0, 1);
    let env = sim.signers[0]
        .direct(&sim.cards[2], b"secret", sim.now, 3600)
        .unwrap();
    let id = env.message_id().unwrap();
    sim.publish(0, env);
    sim.run_for(1_000, 100);
    assert!(sim.has_message(1, &id), "the relay holds the message");

    // The relay forges an Ack for a message it cannot read.
    let forged = sim.signers[1]
        .ack(&sim.signers[0].node_id(), &id, sim.now, 3600)
        .unwrap();
    sim.publish(1, forged);
    sim.run_for(2_000, 100);
    assert!(
        sim.delivered(0).is_empty(),
        "only the recipient's Ack counts"
    );
}

#[test]
fn large_messages_are_fragmented_and_reassembled_on_a_tiny_mtu() {
    let mut sim = line(3, MeshConfig::default(), 64);
    let payload = vec![0xAB; 800];
    let env = sim.broadcast(0, &payload);
    let id = env.message_id().unwrap();
    sim.publish(0, env.clone());
    sim.run_for(10_000, 100);

    let stored = sim.nodes[2]
        .mesh
        .storage()
        .get(&id, sim.now)
        .unwrap()
        .unwrap();
    assert_eq!(stored.envelope, env);
    assert_eq!(sim.received_bodies(2), vec![payload]);
}

#[test]
fn a_late_joiner_receives_the_history() {
    let mut sim = Sim::new(2, MeshConfig::default(), 247);
    let mut ids = Vec::new();
    for i in 0..5 {
        let env = sim.broadcast(0, format!("message {i}").as_bytes());
        ids.push(env.message_id().unwrap());
        sim.publish(0, env);
    }
    sim.connect(0, 1);
    sim.run_for(3_000, 100);

    for id in &ids {
        assert!(sim.has_message(1, id));
    }
    assert_eq!(sim.received_bodies(1).len(), 5);
}

#[test]
fn a_lossy_ring_still_converges_thanks_to_retries_and_periodic_resync() {
    let config = MeshConfig {
        resync_interval_ms: 5_000,
        want_timeout_ms: 1_000,
        ..MeshConfig::default()
    };
    let mut sim = line(5, config, 247);
    sim.connect(4, 0);
    sim.loss_permille = 100; // 10% of packets vanish
    let env = sim.broadcast(0, b"through the noise");
    let id = env.message_id().unwrap();
    sim.publish(0, env);
    sim.run_for(120_000, 100);

    for i in 0..5 {
        assert!(sim.has_message(i, &id), "node {i} never got the message");
    }
}

#[test]
fn a_blocked_sender_is_dropped_and_not_requested_again() {
    let mut sim = Sim::new(2, MeshConfig::default(), 247);
    sim.connect(0, 1);
    let author = sim.signers[0].node_id();
    sim.nodes[1]
        .mesh
        .storage_mut()
        .set_trust(&author, storage::TrustState::Blocked, sim.now)
        .unwrap();

    let env = sim.broadcast(0, b"unwanted");
    let id = env.message_id().unwrap();
    sim.publish(0, env);
    sim.run_for(3_000, 100);
    assert!(!sim.has_message(1, &id));
    assert_eq!(sim.nodes[1].mesh.stats().blocked_dropped, 1);

    // Past the periodic re-offer, the message is announced again but not requested.
    sim.run_for(70_000, 1_000);
    assert_eq!(sim.nodes[1].mesh.stats().blocked_dropped, 1);
}

#[test]
fn a_chatty_sender_is_rate_limited_by_receivers() {
    let config = MeshConfig {
        sender_burst: 3,
        sender_rate_per_sec: 1,
        ..MeshConfig::default()
    };
    let mut sim = Sim::new(2, config, 247);
    sim.connect(0, 1);
    for i in 0..10 {
        let env = sim.broadcast(0, format!("spam {i}").as_bytes());
        sim.publish(0, env);
    }
    let stats = sim.nodes[1].mesh.stats();
    assert_eq!(stats.frames_accepted, 3);
    assert_eq!(stats.sender_rate_limited, 7);
}

#[test]
fn the_same_events_give_the_same_results() {
    fn run() -> Vec<mesh::MeshStats> {
        let mut sim = line(4, MeshConfig::default(), 100);
        sim.connect(3, 0);
        for i in 0..3 {
            let env = sim.broadcast(i, format!("m{i}").as_bytes());
            sim.publish(i, env);
        }
        sim.run_for(5_000, 100);
        sim.nodes.iter().map(|n| n.mesh.stats().clone()).collect()
    }
    assert_eq!(run(), run());
}

#[test]
fn a_departed_peer_is_forgotten_and_its_packets_ignored() {
    let mut sim = Sim::new(2, MeshConfig::default(), 247);
    sim.connect(0, 1);
    assert_eq!(sim.nodes[0].mesh.connected_peers().len(), 1);
    sim.nodes[0]
        .mesh
        .handle(
            sim.now,
            mesh::Event::PeerLost {
                peer: mesh::PeerId(1),
            },
        )
        .unwrap();
    assert!(sim.nodes[0].mesh.connected_peers().is_empty());
    sim.nodes[0]
        .mesh
        .handle(
            sim.now,
            mesh::Event::PacketReceived {
                peer: mesh::PeerId(1),
                bytes: vec![1, 2, 3],
            },
        )
        .unwrap();
    assert_eq!(sim.nodes[0].mesh.stats().unknown_peer_packets, 1);
}
