//! Conversations: backward paging, the conversation list, read markers and message notes.

mod common;

use common::{envelope, node, store, store_with};
use proto::Kind;
use proto::limits::MAX_PLAINTEXT_LEN;
use storage::{
    Config, Conversation, ConversationMessages, InsertOutcome, MessageNote, Storage, StorageError,
    UnreadCounts,
};

const NOW: u64 = 1_000;
const HOUR: u32 = 3600;

/// A store of node 1 ("me").
fn my_store() -> Storage {
    store_with(Config {
        local_node: Some(node(1)),
        ..Config::default()
    })
}

/// Inserts a message and returns its log position.
fn put(s: &mut Storage, kind: Kind, sender: u8, recipient: u8, nonce: u32) -> u64 {
    let message = envelope(kind, sender, recipient, nonce, 300, HOUR);
    match s.insert(&message, NOW).unwrap() {
        InsertOutcome::Inserted { seq, .. } => seq,
        other => panic!("the message was not stored: {other:?}"),
    }
}

fn seqs(page: &ConversationMessages) -> Vec<u64> {
    page.messages.iter().map(|m| m.seq).collect()
}

#[test]
fn a_conversation_is_paged_backwards_and_each_page_reads_oldest_first() {
    let mut s = my_store();
    let mut chat_seqs = Vec::new();
    for i in 0..5u32 {
        // The chat with node 2 goes both ways...
        let (from, to) = if i % 2 == 0 { (1, 2) } else { (2, 1) };
        chat_seqs.push(put(&mut s, Kind::Direct, from, to, 10 + i));
        // ...and is surrounded by things that are not part of it.
        put(&mut s, Kind::Direct, 1, 3, 100 + i); // another chat
        put(&mut s, Kind::Direct, 4, 5, 200 + i); // only passing through
        put(&mut s, Kind::Broadcast, 2, 0, 300 + i); // an announcement
        put(&mut s, Kind::Ack, 2, 1, 400 + i); // an acknowledgement
    }
    let chat = Conversation::Direct(node(2));

    let newest = s.conversation_messages(&chat, None, 2, NOW).unwrap();
    assert_eq!(seqs(&newest), chat_seqs[3..].to_vec());
    assert!(newest.more_before);

    let middle = s
        .conversation_messages(&chat, Some(newest.messages[0].seq), 2, NOW)
        .unwrap();
    assert_eq!(seqs(&middle), chat_seqs[1..3].to_vec());
    assert!(middle.more_before);

    let oldest = s
        .conversation_messages(&chat, Some(middle.messages[0].seq), 2, NOW)
        .unwrap();
    assert_eq!(seqs(&oldest), chat_seqs[..1].to_vec());
    assert!(!oldest.more_before);

    // A page that ends exactly at the beginning does not claim there is more.
    let all = s.conversation_messages(&chat, None, 5, NOW).unwrap();
    assert_eq!(seqs(&all), chat_seqs);
    assert!(!all.more_before);

    // Nothing at all before the first message.
    let none = s
        .conversation_messages(&chat, Some(chat_seqs[0]), 5, NOW)
        .unwrap();
    assert!(none.messages.is_empty());
    assert!(!none.more_before);
}

#[test]
fn the_announcements_hold_own_and_foreign_broadcasts_only() {
    let mut s = my_store();
    let mine = put(&mut s, Kind::Broadcast, 1, 0, 1);
    let theirs = put(&mut s, Kind::Broadcast, 2, 0, 2);
    put(&mut s, Kind::Direct, 2, 1, 3);
    put(&mut s, Kind::Ack, 2, 1, 4);

    let page = s
        .conversation_messages(&Conversation::Broadcasts, None, 10, NOW)
        .unwrap();
    assert_eq!(seqs(&page), vec![mine, theirs]);
    assert!(!page.more_before);
}

#[test]
fn expired_messages_are_not_part_of_a_conversation() {
    let mut s = my_store();
    // One second of life, stored at NOW.
    s.insert(&envelope(Kind::Direct, 2, 1, 1, 300, 1), NOW)
        .unwrap();
    let kept = put(&mut s, Kind::Direct, 2, 1, 2);
    let later = NOW + 5_000;

    let page = s
        .conversation_messages(&Conversation::Direct(node(2)), None, 10, later)
        .unwrap();
    assert_eq!(seqs(&page), vec![kept]);
    let rows = s.conversations(10, later).unwrap();
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].message_count, 1);
    assert_eq!(s.unread_counts(later).unwrap().direct, 1);
}

#[test]
fn the_list_orders_by_newest_message_and_counts_unread_incoming_messages() {
    let mut s = my_store();
    put(&mut s, Kind::Direct, 2, 1, 1); // from node 2
    let to_two = put(&mut s, Kind::Direct, 1, 2, 2); // to node 2
    put(&mut s, Kind::Direct, 3, 1, 3); // from node 3
    put(&mut s, Kind::Broadcast, 4, 0, 4); // an announcement of somebody else
    let own_broadcast = put(&mut s, Kind::Broadcast, 1, 0, 5); // my own, the newest of all
    put(&mut s, Kind::Direct, 6, 7, 6); // passing through: no conversation

    let rows = s.conversations(10, NOW).unwrap();
    assert_eq!(rows.len(), 3);

    assert_eq!(rows[0].conversation, Conversation::Broadcasts);
    assert_eq!(rows[0].last_seq, own_broadcast);
    assert_eq!(rows[0].message_count, 2);
    assert_eq!(rows[0].unread, 1, "my own announcement is never unread");

    assert_eq!(rows[1].conversation, Conversation::Direct(node(3)));
    assert_eq!(rows[1].message_count, 1);
    assert_eq!(rows[1].unread, 1);

    assert_eq!(rows[2].conversation, Conversation::Direct(node(2)));
    assert_eq!(rows[2].last_seq, to_two);
    assert_eq!(rows[2].message_count, 2);
    assert_eq!(rows[2].unread, 1, "what I wrote myself is never unread");

    assert_eq!(
        s.unread_counts(NOW).unwrap(),
        UnreadCounts {
            direct: 2,
            broadcasts: 1
        }
    );
    assert_eq!(s.conversations(2, NOW).unwrap().len(), 2);
    assert!(s.conversations(0, NOW).unwrap().is_empty());
}

#[test]
fn read_markers_only_move_forward_and_belong_to_one_conversation() {
    let mut s = my_store();
    let first = put(&mut s, Kind::Direct, 2, 1, 1);
    let second = put(&mut s, Kind::Direct, 2, 1, 2);
    put(&mut s, Kind::Direct, 3, 1, 3);
    let chat = Conversation::Direct(node(2));

    assert_eq!(s.read_mark(&chat).unwrap(), 0);
    s.mark_read(&chat, first).unwrap();
    assert_eq!(s.read_mark(&chat).unwrap(), first);
    let rows = s.conversations(10, NOW).unwrap();
    let in_chat = rows.iter().find(|r| r.conversation == chat).unwrap();
    assert_eq!(in_chat.unread, 1);

    s.mark_read(&chat, second).unwrap();
    assert_eq!(s.unread_counts(NOW).unwrap().direct, 1, "node 3 is still unread");

    // An older position is ignored: read messages never become unread again.
    s.mark_read(&chat, first).unwrap();
    assert_eq!(s.read_mark(&chat).unwrap(), second);

    // A message that arrives after the marker is unread.
    put(&mut s, Kind::Direct, 2, 1, 4);
    assert_eq!(s.unread_counts(NOW).unwrap().direct, 2);

    // The other conversations keep their own markers.
    assert_eq!(s.read_mark(&Conversation::Direct(node(3))).unwrap(), 0);
    assert_eq!(s.read_mark(&Conversation::Broadcasts).unwrap(), 0);
}

#[test]
fn the_announcements_have_their_own_marker() {
    let mut s = my_store();
    put(&mut s, Kind::Broadcast, 2, 0, 1);
    put(&mut s, Kind::Broadcast, 3, 0, 2);
    assert_eq!(s.unread_counts(NOW).unwrap().broadcasts, 2);

    let head = s.head_seq().unwrap();
    s.mark_read(&Conversation::Broadcasts, head).unwrap();
    assert_eq!(s.unread_counts(NOW).unwrap().broadcasts, 0);
    assert_eq!(s.conversations(10, NOW).unwrap()[0].unread, 0);

    put(&mut s, Kind::Broadcast, 2, 0, 3);
    assert_eq!(s.unread_counts(NOW).unwrap().broadcasts, 1);
}

#[test]
fn notes_keep_the_sent_text_and_the_first_delivery_time() {
    let mut s = my_store();
    let sent = envelope(Kind::Direct, 1, 2, 1, 300, HOUR);
    let id = sent.message_id().unwrap();
    s.insert(&sent, NOW).unwrap();
    assert_eq!(s.note(&id).unwrap(), MessageNote::default());

    assert!(s.set_plaintext(&id, b"hello").unwrap());
    assert!(s.mark_delivered(&id, NOW + 10).unwrap());
    assert!(s.mark_delivered(&id, NOW + 99).unwrap());
    assert_eq!(
        s.note(&id).unwrap(),
        MessageNote {
            plaintext: Some(b"hello".to_vec()),
            delivered_at_ms: Some(NOW + 10),
        }
    );

    // Replacing the text keeps the delivery time.
    assert!(s.set_plaintext(&id, b"changed").unwrap());
    let note = s.note(&id).unwrap();
    assert_eq!(note.plaintext.as_deref(), Some(&b"changed"[..]));
    assert_eq!(note.delivered_at_ms, Some(NOW + 10));
}

#[test]
fn a_delivery_can_be_recorded_before_any_text_exists() {
    let mut s = my_store();
    let sent = envelope(Kind::Direct, 1, 2, 1, 300, HOUR);
    let id = sent.message_id().unwrap();
    s.insert(&sent, NOW).unwrap();
    assert!(s.mark_delivered(&id, NOW + 5).unwrap());
    let note = s.note(&id).unwrap();
    assert_eq!(note.plaintext, None);
    assert_eq!(note.delivered_at_ms, Some(NOW + 5));
}

#[test]
fn notes_for_unknown_messages_are_refused_quietly() {
    let mut s = my_store();
    let id = envelope(Kind::Direct, 1, 2, 1, 300, HOUR)
        .message_id()
        .unwrap();
    assert!(!s.set_plaintext(&id, b"nothing to attach to").unwrap());
    assert!(!s.mark_delivered(&id, NOW).unwrap());
    assert_eq!(s.note(&id).unwrap(), MessageNote::default());
}

#[test]
fn the_text_of_a_note_is_limited_to_what_a_direct_message_carries() {
    let mut s = my_store();
    let sent = envelope(Kind::Direct, 1, 2, 1, 300, HOUR);
    let id = sent.message_id().unwrap();
    s.insert(&sent, NOW).unwrap();

    assert!(s.set_plaintext(&id, &vec![7u8; MAX_PLAINTEXT_LEN]).unwrap());
    assert!(matches!(
        s.set_plaintext(&id, &vec![7u8; MAX_PLAINTEXT_LEN + 1]),
        Err(StorageError::InvalidArgument(_))
    ));
    // The refused call changed nothing.
    assert_eq!(
        s.note(&id).unwrap().plaintext.map(|p| p.len()),
        Some(MAX_PLAINTEXT_LEN)
    );
}

#[test]
fn a_note_disappears_with_its_message() {
    let mut s = my_store();

    // Deleted by hand.
    let deleted = envelope(Kind::Direct, 1, 2, 1, 300, HOUR);
    let deleted_id = deleted.message_id().unwrap();
    s.insert(&deleted, NOW).unwrap();
    s.set_plaintext(&deleted_id, b"gone soon").unwrap();
    assert!(s.delete(&deleted_id, NOW).unwrap());
    assert_eq!(s.note(&deleted_id).unwrap(), MessageNote::default());

    // Expired.
    let short = envelope(Kind::Direct, 1, 2, 2, 300, 1);
    let short_id = short.message_id().unwrap();
    s.insert(&short, NOW).unwrap();
    s.set_plaintext(&short_id, b"short lived").unwrap();
    s.mark_delivered(&short_id, NOW + 1).unwrap();
    s.purge_expired(NOW + 5_000).unwrap();
    assert_eq!(s.note(&short_id).unwrap(), MessageNote::default());

    s.check_integrity().unwrap();
}

#[test]
fn conversations_need_the_local_node() {
    let s = store();
    assert!(matches!(
        s.conversations(10, NOW),
        Err(StorageError::InvalidArgument(_))
    ));
    assert!(matches!(
        s.conversation_messages(&Conversation::Broadcasts, None, 10, NOW),
        Err(StorageError::InvalidArgument(_))
    ));
    assert!(matches!(
        s.unread_counts(NOW),
        Err(StorageError::InvalidArgument(_))
    ));
}

#[test]
fn markers_and_notes_survive_a_restart() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("crisis.db");
    let config = Config {
        local_node: Some(node(1)),
        ..Config::default()
    };
    let sent = envelope(Kind::Direct, 1, 2, 1, 300, HOUR);
    let id = sent.message_id().unwrap();
    {
        let mut s = Storage::open(&path, config.clone(), None).unwrap();
        s.insert(&sent, NOW).unwrap();
        s.set_plaintext(&id, b"kept").unwrap();
        s.mark_delivered(&id, NOW + 3).unwrap();
        s.mark_read(&Conversation::Direct(node(2)), 7).unwrap();
    }
    let s = Storage::open(&path, config, None).unwrap();
    let note = s.note(&id).unwrap();
    assert_eq!(note.plaintext.as_deref(), Some(&b"kept"[..]));
    assert_eq!(note.delivered_at_ms, Some(NOW + 3));
    assert_eq!(s.read_mark(&Conversation::Direct(node(2))).unwrap(), 7);
}
