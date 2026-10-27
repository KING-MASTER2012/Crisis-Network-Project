//! Engine behaviour: publishing, contacts, history and a two-node conversation.

use crypto::Identity;
use engine::{
    Conversation, Direction, Engine, EngineConfig, EngineError, Output, TransportAction,
    UnreadCounts,
};
use mesh::{Notification, PeerId};
use proto::Kind;
use storage::TrustState;

const T0: u64 = 10_000;

fn engine(seed: u8) -> Engine {
    Engine::in_memory(
        EngineConfig::default(),
        Identity::from_seed(&[seed; 32]).unwrap(),
        T0,
    )
    .unwrap()
}

/// Two engines wired back to back with instant, in-order delivery.
struct Pair {
    a: Engine,
    b: Engine,
    notes_a: Vec<Notification>,
    notes_b: Vec<Notification>,
    to_a: Vec<Vec<u8>>,
    to_b: Vec<Vec<u8>>,
    now: u64,
}

impl Pair {
    fn new() -> Self {
        let mut pair = Self {
            a: engine(1),
            b: engine(2),
            notes_a: Vec::new(),
            notes_b: Vec::new(),
            to_a: Vec::new(),
            to_b: Vec::new(),
            now: T0,
        };
        let out_a = pair.a.peer_seen(T0, PeerId(1), 247).unwrap();
        let out_b = pair.b.peer_seen(T0, PeerId(0), 247).unwrap();
        pair.absorb(true, out_a);
        pair.absorb(false, out_b);
        pair.settle();
        pair
    }

    /// Keeps the notifications of `output` and queues the packets it wants to send.
    fn absorb(&mut self, from_a: bool, output: Output) {
        let (notes, outbox) = if from_a {
            (&mut self.notes_a, &mut self.to_b)
        } else {
            (&mut self.notes_b, &mut self.to_a)
        };
        notes.extend(output.notifications);
        for action in output.transport {
            if let TransportAction::SendPacket { bytes, .. } = action {
                outbox.push(bytes);
            }
        }
    }

    /// Delivers packets back and forth, and ticks both sides, until nothing moves.
    fn settle(&mut self) {
        for _round in 0..200 {
            self.now += 100;
            let out_a = self.a.tick(self.now).unwrap();
            self.absorb(true, out_a);
            let out_b = self.b.tick(self.now).unwrap();
            self.absorb(false, out_b);
            while !self.to_a.is_empty() || !self.to_b.is_empty() {
                for bytes in std::mem::take(&mut self.to_b) {
                    let out = self.b.packet_received(self.now, PeerId(0), bytes).unwrap();
                    self.absorb(false, out);
                }
                for bytes in std::mem::take(&mut self.to_a) {
                    let out = self.a.packet_received(self.now, PeerId(1), bytes).unwrap();
                    self.absorb(true, out);
                }
            }
        }
    }

    fn publish_from_a_broadcast(&mut self, text: &[u8]) -> proto::MessageId {
        let (id, out) = self.a.publish_broadcast(self.now, text).unwrap();
        self.absorb(true, out);
        self.settle();
        id
    }
}

#[test]
fn a_broadcast_is_published_and_shows_up_in_the_authors_history() {
    let mut e = engine(1);
    let (id, out) = e.publish_broadcast(T0, b"hello").unwrap();
    assert!(out.notifications.contains(&Notification::Published { id }));

    let page = e.history(0, 10, T0).unwrap();
    assert_eq!(page.entries.len(), 1);
    let entry = &page.entries[0];
    assert_eq!(entry.id, id);
    assert_eq!(entry.direction, Direction::Outgoing);
    assert_eq!(entry.kind, Kind::Broadcast);
    assert_eq!(entry.counterpart, None);
    assert_eq!(entry.body.as_deref(), Some(&b"hello"[..]));

    // Publishing the same thing in the same millisecond is a duplicate, not a second message.
    let (_, again) = e.publish_broadcast(T0, b"hello").unwrap();
    assert!(
        again
            .notifications
            .iter()
            .any(|n| matches!(n, Notification::PublishRejected { .. }))
    );
}

#[test]
fn a_direct_message_needs_a_contact_and_then_travels_encrypted() {
    let mut pair = Pair::new();
    let b_id = pair.b.node_id();
    let b_card = pair.b.contact_card();

    // Without the recipient's card, publishing is refused.
    assert!(matches!(
        pair.a.publish_direct(pair.now, &b_id, b"hi"),
        Err(EngineError::UnknownContact)
    ));

    // A card scanned out of band is verified and remembered.
    let card = pair.a.add_contact(pair.now, &b_card.to_bytes()).unwrap();
    assert_eq!(card.node_id(), b_id);
    let contacts = pair.a.contacts(10).unwrap();
    assert_eq!(contacts.len(), 1);
    assert_eq!(contacts[0].card, Some(b_card));

    let (id, out) = pair
        .a
        .publish_direct(pair.now, &b_id, b"for your eyes only")
        .unwrap();
    pair.absorb(true, out);
    pair.settle();

    // B reads it; A is told it was delivered.
    let page = pair.b.history(0, 10, pair.now).unwrap();
    let incoming: Vec<_> = page
        .entries
        .iter()
        .filter(|e| e.direction == Direction::Incoming)
        .collect();
    assert_eq!(incoming.len(), 1);
    assert_eq!(
        incoming[0].body.as_deref(),
        Some(&b"for your eyes only"[..])
    );
    assert_eq!(incoming[0].counterpart, Some(pair.a.node_id()));
    assert!(pair.notes_a.contains(&Notification::Delivered { id }));

    // A's own history shows the message with the text A wrote (the envelope itself is
    // sealed to B, so the engine keeps a local copy), and when B confirmed it.
    let mine = pair.a.history(0, 10, pair.now).unwrap();
    assert_eq!(mine.entries.len(), 1);
    assert_eq!(mine.entries[0].direction, Direction::Outgoing);
    assert_eq!(mine.entries[0].counterpart, Some(b_id));
    assert_eq!(
        mine.entries[0].body.as_deref(),
        Some(&b"for your eyes only"[..])
    );
    assert!(mine.entries[0].delivered_at_ms.is_some());
    // B never sent anything, so nothing of B's is "delivered".
    assert_eq!(incoming[0].delivered_at_ms, None);
}

#[test]
fn tampered_or_foreign_contact_cards_are_refused() {
    let mut a = engine(1);
    let card = engine(2).contact_card().to_bytes();
    let mut bad = card;
    bad[40] ^= 1;
    assert!(matches!(
        a.add_contact(T0, &bad),
        Err(EngineError::InvalidContactCard)
    ));
    assert!(matches!(
        a.add_contact(T0, &card[..100]),
        Err(EngineError::InvalidContactCard)
    ));
    assert!(a.contacts(10).unwrap().is_empty());
}

#[test]
fn a_blocked_author_is_not_stored() {
    let mut pair = Pair::new();
    let author = pair.a.node_id();
    pair.b
        .set_trust(pair.now, &author, TrustState::Blocked)
        .unwrap();
    pair.publish_from_a_broadcast(b"unwanted");
    assert!(pair.b.history(0, 10, pair.now).unwrap().entries.is_empty());
    assert_eq!(pair.b.stats().blocked_dropped, 1);
}

#[test]
fn history_pages_do_not_skip_or_repeat() {
    let mut e = engine(1);
    for i in 0..10 {
        e.publish_broadcast(T0 + i, format!("message {i}").as_bytes())
            .unwrap();
    }
    let mut seen = Vec::new();
    let mut after = 0;
    loop {
        let page = e.history(after, 3, T0 + 100).unwrap();
        if page.entries.is_empty() {
            break;
        }
        after = page.next_after_seq;
        seen.extend(page.entries.into_iter().map(|e| e.body.unwrap()));
    }
    let expected: Vec<Vec<u8>> = (0..10)
        .map(|i| format!("message {i}").into_bytes())
        .collect();
    assert_eq!(seen, expected);
    assert!(e.history(0, 0, T0).unwrap().entries.is_empty());
}

#[test]
fn bad_configuration_is_rejected() {
    let config = EngineConfig {
        default_lifetime_secs: 0,
        ..EngineConfig::default()
    };
    let result = Engine::in_memory(config, Identity::from_seed(&[1; 32]).unwrap(), T0);
    assert!(matches!(result, Err(EngineError::InvalidConfig(_))));
}

#[test]
fn a_file_backed_engine_keeps_its_history_across_restarts() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("node.db");
    let id = {
        let mut e = Engine::open(
            EngineConfig::default(),
            Identity::from_seed(&[1; 32]).unwrap(),
            &path,
            None,
            T0,
        )
        .unwrap();
        e.publish_broadcast(T0, b"remember me").unwrap().0
    };
    let e = Engine::open(
        EngineConfig::default(),
        Identity::from_seed(&[1; 32]).unwrap(),
        &path,
        None,
        T0 + 1,
    )
    .unwrap();
    let page = e.history(0, 10, T0 + 1).unwrap();
    assert_eq!(page.entries.len(), 1);
    assert_eq!(page.entries[0].id, id);
}

/// Two engines that know each other's card, with a message already sent from A to B.
fn introduced_pair() -> Pair {
    let mut pair = Pair::new();
    let a_card = pair.a.contact_card().to_bytes();
    let b_card = pair.b.contact_card().to_bytes();
    pair.a.add_contact(pair.now, &b_card).unwrap();
    pair.b.add_contact(pair.now, &a_card).unwrap();
    pair
}

fn send_direct(pair: &mut Pair, from_a: bool, text: &str) -> proto::MessageId {
    let (id, out) = if from_a {
        let to = pair.b.node_id();
        pair.a
            .publish_direct(pair.now, &to, text.as_bytes())
            .unwrap()
    } else {
        let to = pair.a.node_id();
        pair.b
            .publish_direct(pair.now, &to, text.as_bytes())
            .unwrap()
    };
    pair.absorb(from_a, out);
    pair.settle();
    id
}

fn texts(page: &engine::ConversationPage) -> Vec<String> {
    page.entries
        .iter()
        .map(|e| String::from_utf8(e.body.clone().unwrap()).unwrap())
        .collect()
}

#[test]
fn a_chat_reads_backwards_in_pages_and_both_sides_see_the_same_text() {
    let mut pair = introduced_pair();
    for i in 0..5 {
        send_direct(&mut pair, i % 2 == 0, &format!("m{i}"));
    }
    let (a_id, b_id) = (pair.a.node_id(), pair.b.node_id());

    for (engine, other) in [(&pair.a, b_id), (&pair.b, a_id)] {
        let chat = Conversation::Direct(other);
        let newest = engine.conversation(&chat, None, 2, pair.now).unwrap();
        assert_eq!(texts(&newest), ["m3", "m4"]);
        let middle = engine
            .conversation(&chat, newest.older_before_seq, 2, pair.now)
            .unwrap();
        assert_eq!(texts(&middle), ["m1", "m2"]);
        let oldest = engine
            .conversation(&chat, middle.older_before_seq, 2, pair.now)
            .unwrap();
        assert_eq!(texts(&oldest), ["m0"]);
        assert_eq!(oldest.older_before_seq, None);
    }

    // Directions are right on each side.
    let page = pair
        .a
        .conversation(&Conversation::Direct(b_id), None, 10, pair.now)
        .unwrap();
    let directions: Vec<_> = page.entries.iter().map(|e| e.direction).collect();
    assert_eq!(
        directions,
        [
            Direction::Outgoing,
            Direction::Incoming,
            Direction::Outgoing,
            Direction::Incoming,
            Direction::Outgoing
        ]
    );
    // limit 0 returns nothing and keeps the cursor.
    let none = pair
        .a
        .conversation(&Conversation::Direct(b_id), Some(7), 0, pair.now)
        .unwrap();
    assert!(none.entries.is_empty());
    assert_eq!(none.older_before_seq, Some(7));
}

#[test]
fn delivery_is_remembered_in_the_store() {
    let mut pair = introduced_pair();
    let id = send_direct(&mut pair, true, "are you there?");
    assert!(pair.notes_a.contains(&Notification::Delivered { id }));

    let b_id = pair.b.node_id();
    let page = pair
        .a
        .conversation(&Conversation::Direct(b_id), None, 10, pair.now)
        .unwrap();
    assert_eq!(page.entries.len(), 1);
    let delivered = page.entries[0].delivered_at_ms.expect("delivered");
    assert!(delivered >= T0);
    assert_eq!(page.entries[0].id, id);
}

#[test]
fn unread_counts_follow_the_read_marker() {
    let mut pair = introduced_pair();
    send_direct(&mut pair, true, "one");
    send_direct(&mut pair, true, "two");
    pair.publish_from_a_broadcast(b"announcement");
    let a_id = pair.a.node_id();
    let chat = Conversation::Direct(a_id);

    // B received two direct messages and one announcement; nothing was read yet.
    assert_eq!(
        pair.b.unread_counts(pair.now).unwrap(),
        UnreadCounts {
            direct: 2,
            broadcasts: 1
        }
    );
    let list = pair.b.conversations(10, pair.now).unwrap();
    assert_eq!(list.len(), 2);
    assert_eq!(list[0].conversation, Conversation::Broadcasts);
    assert_eq!(list[0].unread, 1);
    assert_eq!(list[1].conversation, chat);
    assert_eq!(list[1].unread, 2);
    assert_eq!(list[1].message_count, 2);
    assert_eq!(
        list[1].last.as_ref().and_then(|e| e.body.as_deref()),
        Some(&b"two"[..])
    );

    // Reading up to the newest message clears the chat, not the announcements.
    let mark = pair.b.mark_read(&chat, None).unwrap();
    let page = pair.b.conversation(&chat, None, 10, pair.now).unwrap();
    assert_eq!(page.read_up_to_seq, mark);
    assert!(page.entries.iter().all(|e| e.seq <= mark));
    assert_eq!(
        pair.b.unread_counts(pair.now).unwrap(),
        UnreadCounts {
            direct: 0,
            broadcasts: 1
        }
    );

    // Marking an older position later does not make anything unread again.
    let first_seq = page.entries[0].seq;
    assert_eq!(pair.b.mark_read(&chat, Some(first_seq)).unwrap(), mark);

    // The announcements are a conversation of their own.
    pair.b.mark_read(&Conversation::Broadcasts, None).unwrap();
    assert_eq!(
        pair.b.unread_counts(pair.now).unwrap(),
        UnreadCounts::default()
    );
}

#[test]
fn announcements_page_like_a_chat() {
    let mut pair = introduced_pair();
    for i in 0..3 {
        pair.publish_from_a_broadcast(format!("news {i}").as_bytes());
    }
    let page = pair
        .a
        .conversation(&Conversation::Broadcasts, None, 2, pair.now)
        .unwrap();
    assert_eq!(texts(&page), ["news 1", "news 2"]);
    let older = pair
        .a
        .conversation(
            &Conversation::Broadcasts,
            page.older_before_seq,
            2,
            pair.now,
        )
        .unwrap();
    assert_eq!(texts(&older), ["news 0"]);
    assert_eq!(older.older_before_seq, None);
}

#[test]
fn the_text_of_a_sent_message_expires_with_the_message() {
    let config = EngineConfig {
        default_lifetime_secs: 1,
        ..EngineConfig::default()
    };
    let mut a = Engine::in_memory(config, Identity::from_seed(&[1; 32]).unwrap(), T0).unwrap();
    let card = engine(2).contact_card().to_bytes();
    let b = a.add_contact(T0, &card).unwrap().node_id();
    a.publish_direct(T0, &b, b"short lived").unwrap();

    let chat = Conversation::Direct(b);
    assert_eq!(
        a.conversation(&chat, None, 10, T0).unwrap().entries.len(),
        1
    );

    let later = T0 + 5_000;
    a.tick(later).unwrap();
    assert!(
        a.conversation(&chat, None, 10, later)
            .unwrap()
            .entries
            .is_empty()
    );
    assert!(a.conversations(10, later).unwrap().is_empty());
    // The local copy of the text went away with the message.
    a.storage().check_integrity().unwrap();
}
