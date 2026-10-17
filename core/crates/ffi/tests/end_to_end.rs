//! The interface exercised the way a platform client uses it: bytes in, commands out.
//! Two nodes are wired together by a small loop that plays the part of the radio.

use crisis_core::{
    CoreError, CoreNotification, CoreOutput, CrisisNode, MessageDirection, MessageKind,
    NodeSettings, TransportCommand, TrustLevel, core_info, default_settings, generate_database_key,
    generate_seed, inspect_contact_card, require_api_version,
};
use std::sync::Arc;

const T0: u64 = 1_000_000;
const MTU: u32 = 185;

fn seed(n: u8) -> Vec<u8> {
    vec![n; 32]
}

fn node(n: u8) -> Arc<CrisisNode> {
    CrisisNode::in_memory(seed(n), default_settings(), T0).unwrap()
}

/// Two nodes, `a` seeing `b` as neighbour 2 and `b` seeing `a` as neighbour 1.
struct Pair {
    a: Arc<CrisisNode>,
    b: Arc<CrisisNode>,
    now: u64,
    notes_a: Vec<CoreNotification>,
    notes_b: Vec<CoreNotification>,
    queue: Vec<(bool, Vec<u8>)>, // (towards b?, bytes)
}

impl Pair {
    fn new() -> Self {
        let mut pair = Self {
            a: node(1),
            b: node(2),
            now: T0,
            notes_a: Vec::new(),
            notes_b: Vec::new(),
            queue: Vec::new(),
        };
        let out = pair.a.peer_seen(T0, 2, MTU).unwrap();
        pair.absorb(true, out);
        let out = pair.b.peer_seen(T0, 1, MTU).unwrap();
        pair.absorb(false, out);
        pair
    }

    /// Takes the effects of an output produced by `a` (`from_a`) or `b`.
    fn absorb(&mut self, from_a: bool, output: CoreOutput) {
        for command in output.transport {
            if let TransportCommand::SendPacket { bytes, .. } = command {
                self.queue.push((from_a, bytes));
            }
        }
        if from_a {
            self.notes_a.extend(output.notifications);
        } else {
            self.notes_b.extend(output.notifications);
        }
    }

    /// Delivers packets and ticks both nodes until the network is quiet.
    fn settle(&mut self) {
        for _ in 0..300 {
            self.now += 50;
            let packets = std::mem::take(&mut self.queue);
            for (from_a, bytes) in packets {
                if from_a {
                    let out = self.b.packet_received(self.now, 1, bytes).unwrap();
                    self.absorb(false, out);
                } else {
                    let out = self.a.packet_received(self.now, 2, bytes).unwrap();
                    self.absorb(true, out);
                }
            }
            let out = self.a.tick(self.now).unwrap();
            self.absorb(true, out);
            let out = self.b.tick(self.now).unwrap();
            self.absorb(false, out);
        }
    }
}

#[test]
fn a_direct_message_travels_decrypted_and_is_acknowledged() {
    let mut pair = Pair::new();
    pair.a.add_contact(pair.now, pair.b.contact_card()).unwrap();
    pair.b.add_contact(pair.now, pair.a.contact_card()).unwrap();

    let sent = pair
        .a
        .publish_direct(pair.now, pair.b.node_id(), b"meet at the square".to_vec())
        .unwrap();
    pair.absorb(true, sent.output);
    pair.settle();

    // The recipient reads it...
    let received = pair.notes_b.iter().find_map(|n| match n {
        CoreNotification::MessageReceived {
            id,
            sender,
            kind,
            body,
        } => Some((id.clone(), sender.clone(), *kind, body.clone())),
        _ => None,
    });
    let (id, sender, kind, body) = received.expect("b received the message");
    assert_eq!(id, sent.message_id);
    assert_eq!(sender, pair.a.node_id());
    assert_eq!(kind, MessageKind::Direct);
    assert_eq!(body, b"meet at the square");

    // ...and the sender learns that it arrived.
    assert!(
        pair.notes_a
            .iter()
            .any(|n| matches!(n, CoreNotification::Delivered { id } if *id == sent.message_id))
    );

    // The history shows it from both sides.
    let theirs = pair.b.history(0, 50, pair.now).unwrap();
    let item = theirs
        .entries
        .iter()
        .find(|e| e.id == sent.message_id)
        .unwrap();
    assert_eq!(item.direction, MessageDirection::Incoming);
    assert_eq!(item.body.as_deref(), Some(&b"meet at the square"[..]));
    let ours = pair.a.history(0, 50, pair.now).unwrap();
    let item = ours
        .entries
        .iter()
        .find(|e| e.id == sent.message_id)
        .unwrap();
    assert_eq!(item.direction, MessageDirection::Outgoing);
    assert_eq!(item.counterpart.as_deref(), Some(&pair.b.node_id()[..]));
}

#[test]
fn a_broadcast_reaches_the_neighbour() {
    let mut pair = Pair::new();
    let sent = pair
        .a
        .publish_broadcast(pair.now, b"water at the school".to_vec())
        .unwrap();
    pair.absorb(true, sent.output);
    pair.settle();
    assert!(pair.notes_b.iter().any(|n| matches!(
        n,
        CoreNotification::MessageReceived { kind: MessageKind::Broadcast, body, .. }
            if body == b"water at the school"
    )));
    let stats = pair.b.stats().unwrap();
    assert!(stats.frames_accepted >= 1);
    assert!(stats.stored_messages >= 1 && stats.stored_bytes > 0);
}

#[test]
fn contacts_and_trust_are_managed_through_bytes() {
    let a = node(1);
    let b = node(2);
    let details = a.add_contact(T0, b.contact_card()).unwrap();
    assert_eq!(details.node_id, b.node_id());
    assert_eq!(details.node_id_hex, b.node_id_hex());
    assert_eq!(details.safety_number, b.safety_number());
    assert_eq!(inspect_contact_card(b.contact_card()).unwrap(), details);

    a.set_trust(T0 + 1, b.node_id(), TrustLevel::Verified)
        .unwrap();
    let contacts = a.contacts(10).unwrap();
    let contact = contacts.iter().find(|c| c.node_id == b.node_id()).unwrap();
    assert_eq!(contact.trust, TrustLevel::Verified);
    assert_eq!(contact.card.as_deref(), Some(&b.contact_card()[..]));
}

#[test]
fn bad_input_is_reported_not_trusted() {
    let a = node(1);
    // Identity seed of the wrong size.
    assert!(matches!(
        CrisisNode::in_memory(vec![1; 31], default_settings(), T0),
        Err(CoreError::InvalidArgument { .. })
    ));
    // Database key of the wrong size.
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("x.db").to_string_lossy().into_owned();
    assert!(matches!(
        CrisisNode::open(seed(1), path, Some(vec![0; 5]), default_settings(), T0),
        Err(CoreError::InvalidArgument { .. })
    ));
    // Ids of the wrong size, garbage cards, unknown recipients.
    assert!(matches!(
        a.publish_direct(T0, vec![1; 5], b"x".to_vec()),
        Err(CoreError::InvalidArgument { .. })
    ));
    assert!(matches!(
        a.set_trust(T0, vec![], TrustLevel::Blocked),
        Err(CoreError::InvalidArgument { .. })
    ));
    assert_eq!(
        a.add_contact(T0, vec![0; 128]).unwrap_err(),
        CoreError::InvalidContactCard
    );
    assert_eq!(
        inspect_contact_card(vec![1, 2, 3]).unwrap_err(),
        CoreError::InvalidContactCard
    );
    assert_eq!(
        a.publish_direct(T0, node(9).node_id(), b"x".to_vec())
            .unwrap_err(),
        CoreError::UnknownContact
    );
    // A payload beyond the protocol limit.
    let too_big = vec![0u8; core_info().max_payload_bytes as usize + 1];
    assert!(a.publish_broadcast(T0, too_big).is_err());
    // A hostile packet from a neighbour never fails the call.
    a.peer_seen(T0, 7, MTU).unwrap();
    a.packet_received(T0, 7, vec![0xEE; 40]).unwrap();
    assert_eq!(a.stats().unwrap().malformed_packets, 1);
}

#[test]
fn settings_are_applied_and_checked() {
    let tiny = NodeSettings {
        max_store_bytes: 0,
        ..default_settings()
    };
    assert!(matches!(
        CrisisNode::in_memory(seed(1), tiny, T0),
        Err(CoreError::InvalidConfig { .. } | CoreError::Storage { .. })
    ));
    let one_peer = NodeSettings {
        max_peers: 1,
        ..default_settings()
    };
    let n = CrisisNode::in_memory(seed(1), one_peer, T0).unwrap();
    n.peer_seen(T0, 1, MTU).unwrap();
    let out = n.peer_seen(T0, 2, MTU).unwrap();
    assert!(
        out.transport
            .iter()
            .any(|c| matches!(c, TransportCommand::Disconnect { peer: 2, .. }))
    );
    assert_eq!(n.connected_peers().unwrap(), vec![1]);
}

#[test]
fn the_database_survives_a_restart_with_the_same_identity() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("node.db").to_string_lossy().into_owned();
    let first_id;
    {
        let n = CrisisNode::open(seed(4), path.clone(), None, default_settings(), T0).unwrap();
        first_id = n.node_id();
        n.publish_broadcast(T0, b"still here".to_vec()).unwrap();
    }
    let n = CrisisNode::open(seed(4), path, None, default_settings(), T0 + 1_000).unwrap();
    assert_eq!(n.node_id(), first_id);
    let page = n.history(0, 10, T0 + 1_000).unwrap();
    assert_eq!(page.entries.len(), 1);
    assert_eq!(page.entries[0].body.as_deref(), Some(&b"still here"[..]));
}

#[test]
fn history_is_paged() {
    let n = node(1);
    for i in 0..5u8 {
        n.publish_broadcast(T0 + u64::from(i), vec![i; 3]).unwrap();
    }
    let first = n.history(0, 2, T0 + 10).unwrap();
    assert_eq!(first.entries.len(), 2);
    let second = n.history(first.next_after_seq, 10, T0 + 10).unwrap();
    assert_eq!(second.entries.len(), 3);
    assert!(second.entries[0].seq > first.entries[1].seq);
}

#[test]
fn generated_secrets_are_fresh_and_the_right_size() {
    let (s1, s2) = (generate_seed().unwrap(), generate_seed().unwrap());
    assert_eq!((s1.len(), s2.len()), (32, 32));
    assert_ne!(s1, s2);
    let (k1, k2) = (
        generate_database_key().unwrap(),
        generate_database_key().unwrap(),
    );
    assert_eq!((k1.len(), k2.len()), (32, 32));
    assert_ne!(k1, k2);
    // A generated seed makes a working node.
    CrisisNode::in_memory(s1, default_settings(), T0).unwrap();
}

#[test]
fn the_interface_version_is_enforced() {
    let info = core_info();
    require_api_version(info.api_version).unwrap();
    assert!(matches!(
        require_api_version(info.api_version + 1),
        Err(CoreError::VersionMismatch { .. })
    ));
    assert_eq!(info.protocol_version, 1);
}

#[test]
fn the_node_can_be_shared_between_threads() {
    let n = node(1);
    let handles: Vec<_> = (0..4u64)
        .map(|i| {
            let n = Arc::clone(&n);
            std::thread::spawn(move || {
                for j in 0..20u64 {
                    n.publish_broadcast(T0 + i * 100 + j, vec![u8::try_from(i).unwrap(); 8])
                        .unwrap();
                    n.tick(T0 + i * 100 + j).unwrap();
                }
            })
        })
        .collect();
    for h in handles {
        h.join().unwrap();
    }
    assert_eq!(n.history(0, 1000, T0 + 1000).unwrap().entries.len(), 80);
}
