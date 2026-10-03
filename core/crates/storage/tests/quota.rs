//! Byte-budget behaviour and the eviction policy.

mod common;

use common::*;
use proto::Kind;
use storage::{Config, InsertOutcome, Priority};

const T0: u64 = 1_000;
/// Every message built with this size encodes to exactly 500 bytes.
const SZ: usize = 500;

fn inserted(o: &InsertOutcome) -> bool {
    matches!(o, InsertOutcome::Inserted { .. })
}

#[test]
fn oldest_message_of_the_lowest_priority_is_evicted_first() {
    // Room for exactly four 500-byte messages.
    let mut s = store_with(small(2_000));
    let b1 = envelope(Kind::Broadcast, 1, 0, 1, SZ, 3600);
    let d2 = envelope(Kind::Direct, 1, 2, 2, SZ, 3600);
    let b3 = envelope(Kind::Broadcast, 1, 0, 3, SZ, 3600);
    let d4 = envelope(Kind::Direct, 1, 2, 4, SZ, 3600);
    for (i, e) in [&b1, &d2, &b3, &d4].into_iter().enumerate() {
        assert!(inserted(&s.insert(e, T0 + i as u64).unwrap()));
    }
    assert_eq!(s.stats().unwrap().total_bytes, 2_000);

    // A new Direct evicts a Broadcast (lower priority) - the oldest one, b1.
    let d5 = envelope(Kind::Direct, 1, 2, 5, SZ, 3600);
    let out = s.insert(&d5, T0 + 10).unwrap();
    assert!(matches!(out, InsertOutcome::Inserted { evicted: 1, .. }));
    assert!(!s.contains(&b1.message_id().unwrap(), T0 + 10).unwrap());
    assert!(s.contains(&b3.message_id().unwrap(), T0 + 10).unwrap());
    assert!(s.contains(&d2.message_id().unwrap(), T0 + 10).unwrap());
    s.check_integrity().unwrap();
}

#[test]
fn a_lower_priority_message_cannot_evict_a_higher_one() {
    let mut s = store_with(small(2_000));
    for n in 1..=4 {
        assert!(inserted(
            &s.insert(&envelope(Kind::Direct, 1, 2, n, SZ, 3600), T0)
                .unwrap()
        ));
    }
    let before = s.stats().unwrap();
    // A Broadcast (priority 1) may not push out Directs (priority 2).
    let out = s
        .insert(&envelope(Kind::Broadcast, 1, 0, 99, SZ, 3600), T0)
        .unwrap();
    assert_eq!(out, InsertOutcome::QuotaExceeded);
    assert_eq!(s.stats().unwrap(), before);
    s.check_integrity().unwrap();
}

#[test]
fn equal_priority_evicts_the_oldest() {
    let mut s = store_with(small(2_000));
    let first = envelope(Kind::Broadcast, 1, 0, 1, SZ, 3600);
    s.insert(&first, T0).unwrap();
    for n in 2..=4 {
        s.insert(
            &envelope(Kind::Broadcast, 1, 0, n, SZ, 3600),
            T0 + u64::from(n),
        )
        .unwrap();
    }
    let out = s
        .insert(&envelope(Kind::Broadcast, 1, 0, 5, SZ, 3600), T0 + 10)
        .unwrap();
    assert!(matches!(out, InsertOutcome::Inserted { evicted: 1, .. }));
    assert!(!s.contains(&first.message_id().unwrap(), T0 + 10).unwrap());
}

#[test]
fn own_messages_are_never_evicted_by_quota_pressure() {
    let me = node(7);
    let mut s = store_with(Config {
        max_total_bytes: 2_000,
        local_node: Some(me),
        ..Config::default()
    });
    // Two messages sent by us and two addressed to us (Own priority, 4 x 500 bytes).
    let own = [
        envelope(Kind::Broadcast, 7, 0, 1, SZ, 3600),
        envelope(Kind::Direct, 7, 2, 2, SZ, 3600),
        envelope(Kind::Direct, 2, 7, 3, SZ, 3600),
        envelope(Kind::Direct, 3, 7, 4, SZ, 3600),
    ];
    for e in &own {
        assert!(inserted(&s.insert(e, T0).unwrap()));
        assert_eq!(s.priority_of(&e.body), Priority::Own);
    }
    // Neither a transit message nor another Own message can displace them.
    for e in [
        envelope(Kind::Direct, 5, 6, 10, SZ, 3600),
        envelope(Kind::Direct, 7, 8, 11, SZ, 3600),
    ] {
        assert_eq!(s.insert(&e, T0 + 1).unwrap(), InsertOutcome::QuotaExceeded);
    }
    for e in &own {
        assert!(s.contains(&e.message_id().unwrap(), T0 + 1).unwrap());
    }
    s.check_integrity().unwrap();
}

#[test]
fn an_own_message_evicts_transit_traffic() {
    let me = node(7);
    let mut s = store_with(Config {
        max_total_bytes: 2_000,
        local_node: Some(me),
        ..Config::default()
    });
    for n in 1..=4 {
        s.insert(&envelope(Kind::Direct, 1, 2, n, SZ, 3600), T0)
            .unwrap();
    }
    let mine = envelope(Kind::Direct, 7, 2, 50, SZ, 3600);
    let out = s.insert(&mine, T0 + 1).unwrap();
    assert!(matches!(out, InsertOutcome::Inserted { evicted: 1, .. }));
    assert!(s.contains(&mine.message_id().unwrap(), T0 + 1).unwrap());
}

#[test]
fn a_failed_insert_evicts_nothing() {
    // Budget: 1_300. One Own message (500) + one Ack (178) already fit.
    let me = node(7);
    let mut s = store_with(Config {
        max_total_bytes: 1_300,
        local_node: Some(me),
        ..Config::default()
    });
    let own = envelope(Kind::Direct, 7, 2, 1, SZ, 3600);
    let ack = envelope(Kind::Ack, 1, 2, 2, 0, 3600);
    s.insert(&own, T0).unwrap();
    s.insert(&ack, T0).unwrap();
    let before = s.stats().unwrap();

    // A maximum-size (1_170-byte) Direct can never fit next to the Own message
    // (1_170 + 500 > 1_300), even if the Ack were evicted. The insert must fail
    // without evicting the Ack.
    let big = envelope(Kind::Direct, 1, 2, 3, 1_170, 3600);
    assert_eq!(s.insert(&big, T0).unwrap(), InsertOutcome::QuotaExceeded);
    assert_eq!(s.stats().unwrap(), before);
    assert!(s.contains(&ack.message_id().unwrap(), T0).unwrap());
}

#[test]
fn tombstone_budget_trims_the_oldest() {
    let mut s = store_with(Config {
        max_tombstones: 2,
        ..Config::default()
    });
    let mut ids = Vec::new();
    for n in 1..=4 {
        let e = broadcast(n);
        s.insert(&e, T0).unwrap();
        ids.push(e.message_id().unwrap());
        s.delete(ids.last().unwrap(), T0 + u64::from(n)).unwrap();
    }
    assert_eq!(s.stats().unwrap().tombstone_count, 4);
    let report = s.purge_expired(T0 + 100).unwrap();
    assert_eq!(report.tombstones_trimmed, 2);
    assert_eq!(s.stats().unwrap().tombstone_count, 2);
    // The two oldest tombstones are gone, the two newest remain.
    assert!(!s.is_tombstoned(&ids[0], T0 + 100).unwrap());
    assert!(!s.is_tombstoned(&ids[1], T0 + 100).unwrap());
    assert!(s.is_tombstoned(&ids[2], T0 + 100).unwrap());
    assert!(s.is_tombstoned(&ids[3], T0 + 100).unwrap());
}

#[test]
fn config_validation() {
    assert!(
        storage::Storage::open_in_memory(Config {
            max_total_bytes: 10,
            ..Config::default()
        })
        .is_err()
    );
    assert!(
        storage::Storage::open_in_memory(Config {
            max_peers: 0,
            ..Config::default()
        })
        .is_err()
    );
}
