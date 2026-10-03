//! Insertion, retention, deletion and query behaviour.

mod common;

use common::*;
use proto::limits::MAX_LIFETIME_SECS;
use proto::{Kind, MessageId};
use storage::{InsertOutcome, Stats};

const T0: u64 = 1_000;

#[test]
fn insert_then_get_roundtrip() {
    let mut s = store();
    let env = broadcast(1);
    let id = env.message_id().unwrap();

    let out = s.insert(&env, T0).unwrap();
    assert!(matches!(
        out,
        InsertOutcome::Inserted { seq: 1, evicted: 0 }
    ));

    let got = s.get(&id, T0).unwrap().unwrap();
    assert_eq!(got.envelope, env);
    assert_eq!(got.id, id);
    assert_eq!(got.received_at_ms, T0);
    assert_eq!(got.expires_at_ms, T0 + 3600 * 1000);
    assert_eq!(got.uploaded_at_ms, None);
    assert!(s.contains(&id, T0).unwrap());
    s.check_integrity().unwrap();
}

#[test]
fn duplicate_insert_changes_nothing() {
    let mut s = store();
    let env = broadcast(1);
    s.insert(&env, T0).unwrap();
    let before = s.stats().unwrap();

    assert_eq!(s.insert(&env, T0 + 500).unwrap(), InsertOutcome::Duplicate);
    assert_eq!(s.stats().unwrap(), before);
    // The original receive time is kept; a re-delivery does not extend the lifetime.
    let got = s
        .get(&env.message_id().unwrap(), T0 + 500)
        .unwrap()
        .unwrap();
    assert_eq!(got.received_at_ms, T0);
}

#[test]
fn expiry_is_counted_from_receive_time_not_sender_clock() {
    let mut s = store();
    let mut env = envelope(Kind::Broadcast, 1, 0, 1, 200, 10);
    env.body.created_at_ms = 1; // a wildly wrong sender clock
    s.insert(&env, T0).unwrap();
    let id = env.message_id().unwrap();

    assert!(s.get(&id, T0 + 9_999).unwrap().is_some());
    assert!(s.get(&id, T0 + 10_000).unwrap().is_none());
    assert!(!s.contains(&id, T0 + 10_000).unwrap());
}

#[test]
fn purge_removes_expired_and_leaves_tombstones() {
    let mut s = store();
    let env = envelope(Kind::Broadcast, 1, 0, 1, 200, 10);
    let id = env.message_id().unwrap();
    s.insert(&env, T0).unwrap();

    let now = T0 + 10_000;
    let report = s.purge_expired(now).unwrap();
    assert_eq!(report.messages_expired, 1);
    assert_eq!(s.stats().unwrap().message_count, 0);
    assert!(s.is_tombstoned(&id, now).unwrap());

    // A peer that still has a copy cannot bring it back.
    assert_eq!(s.insert(&env, now).unwrap(), InsertOutcome::Tombstoned);
    s.check_integrity().unwrap();
}

#[test]
fn tombstones_expire_after_the_maximum_lifetime() {
    let mut s = store();
    let env = broadcast(1);
    let id = env.message_id().unwrap();
    s.insert(&env, T0).unwrap();
    assert!(s.delete(&id, T0).unwrap());
    assert_eq!(s.insert(&env, T0 + 1).unwrap(), InsertOutcome::Tombstoned);

    let later = T0 + u64::from(MAX_LIFETIME_SECS) * 1000;
    let report = s.purge_expired(later).unwrap();
    assert_eq!(report.tombstones_expired, 1);
    assert!(matches!(
        s.insert(&env, later).unwrap(),
        InsertOutcome::Inserted { .. }
    ));
}

#[test]
fn delete_of_unknown_message_is_a_noop() {
    let mut s = store();
    let id = broadcast(9).message_id().unwrap();
    assert!(!s.delete(&id, T0).unwrap());
    assert_eq!(s.stats().unwrap().tombstone_count, 0);
    // So the message can still be accepted later.
    assert!(matches!(
        s.insert(&broadcast(9), T0).unwrap(),
        InsertOutcome::Inserted { .. }
    ));
}

#[test]
fn log_is_ordered_and_pages_without_gaps_or_repeats() {
    let mut s = store();
    for n in 1..=10 {
        s.insert(&broadcast(n), T0).unwrap();
    }
    let mut seen = Vec::new();
    let mut after = 0;
    loop {
        let page = s.list_after(after, 3, T0).unwrap();
        if page.is_empty() {
            break;
        }
        after = page.last().unwrap().seq;
        seen.extend(page.into_iter().map(|m| m.seq));
    }
    assert_eq!(seen, (1..=10).collect::<Vec<u64>>());

    let ids = s.ids_after(7, 100, T0).unwrap();
    assert_eq!(
        ids.iter().map(|(q, _)| *q).collect::<Vec<_>>(),
        vec![8, 9, 10]
    );
    assert_eq!(ids[0].1, broadcast(8).message_id().unwrap());
}

#[test]
fn expired_messages_are_hidden_from_listings() {
    let mut s = store();
    s.insert(&envelope(Kind::Broadcast, 1, 0, 1, 200, 10), T0)
        .unwrap();
    s.insert(&envelope(Kind::Broadcast, 1, 0, 2, 200, 100), T0)
        .unwrap();
    let now = T0 + 20_000;
    let listed = s.list_after(0, 10, now).unwrap();
    assert_eq!(listed.len(), 1);
    assert_eq!(listed[0].seq, 2);
    assert_eq!(s.ids_after(0, 10, now).unwrap().len(), 1);
}

#[test]
fn list_for_recipient_filters() {
    let mut s = store();
    s.insert(&envelope(Kind::Direct, 1, 2, 1, 300, 3600), T0)
        .unwrap();
    s.insert(&envelope(Kind::Direct, 1, 3, 2, 300, 3600), T0)
        .unwrap();
    s.insert(&envelope(Kind::Direct, 4, 2, 3, 300, 3600), T0)
        .unwrap();
    s.insert(&broadcast(4), T0).unwrap();

    let for2 = s.list_for_recipient(&node(2), 0, 10, T0).unwrap();
    assert_eq!(for2.iter().map(|m| m.seq).collect::<Vec<_>>(), vec![1, 3]);
    assert!(
        s.list_for_recipient(&node(9), 0, 10, T0)
            .unwrap()
            .is_empty()
    );
}

#[test]
fn missing_returns_only_unknown_ids_in_order_without_duplicates() {
    let mut s = store();
    let have = broadcast(1);
    let deleted = broadcast(2);
    s.insert(&have, T0).unwrap();
    s.insert(&deleted, T0).unwrap();
    s.delete(&deleted.message_id().unwrap(), T0).unwrap();

    let a = broadcast(3).message_id().unwrap();
    let b = broadcast(4).message_id().unwrap();
    let offered: Vec<MessageId> = vec![
        a,
        have.message_id().unwrap(),
        b,
        a,
        deleted.message_id().unwrap(),
    ];
    assert_eq!(s.missing(&offered, T0).unwrap(), vec![a, b]);
    assert!(s.missing(&[], T0).unwrap().is_empty());
}

#[test]
fn upload_marking() {
    let mut s = store();
    let envs: Vec<_> = (1..=3).map(broadcast).collect();
    for e in &envs {
        s.insert(e, T0).unwrap();
    }
    let ids: Vec<_> = envs.iter().map(|e| e.message_id().unwrap()).collect();

    assert_eq!(s.pending_upload(10, T0).unwrap().len(), 3);
    assert_eq!(s.mark_uploaded(&ids[..2], T0 + 5).unwrap(), 2);
    assert_eq!(s.mark_uploaded(&ids[..2], T0 + 6).unwrap(), 0); // already marked
    let pending = s.pending_upload(10, T0).unwrap();
    assert_eq!(pending.len(), 1);
    assert_eq!(pending[0].id, ids[2]);
    let done = s.get(&ids[0], T0).unwrap().unwrap();
    assert_eq!(done.uploaded_at_ms, Some(T0 + 5));
}

#[test]
fn stats_track_counts_and_bytes() {
    let mut s = store();
    assert_eq!(
        s.stats().unwrap(),
        Stats {
            message_count: 0,
            total_bytes: 0,
            tombstone_count: 0,
            peer_count: 0
        }
    );
    let env = envelope(Kind::Broadcast, 1, 0, 1, 500, 3600);
    let size = env.encode().unwrap().len() as u64;
    s.insert(&env, T0).unwrap();
    let st = s.stats().unwrap();
    assert_eq!((st.message_count, st.total_bytes), (1, size));
    s.delete(&env.message_id().unwrap(), T0).unwrap();
    let st = s.stats().unwrap();
    assert_eq!(
        (st.message_count, st.total_bytes, st.tombstone_count),
        (0, 0, 1)
    );
}
