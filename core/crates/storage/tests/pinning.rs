//! Other nodes must not be able to pin the whole store with unevictable data.
//!
//! Messages addressed to the local node (and the acks it creates) get `Own` priority only
//! up to `Config::max_inbound_own_bytes`; beyond that they are ordinary, evictable traffic.

mod common;

use common::*;
use proto::Kind;
use storage::{Config, InsertOutcome};

const T0: u64 = 1_000;
/// Every message built with this size encodes to exactly 500 bytes.
const SZ: usize = 500;
/// An Ack envelope always encodes to 178 bytes.
const ACK: u64 = 178;

fn inserted(o: &InsertOutcome) -> bool {
    matches!(o, InsertOutcome::Inserted { .. })
}

#[test]
fn inbound_messages_are_pinned_only_up_to_the_budget() {
    let me = node(7);
    let mut s = store_with(Config {
        max_total_bytes: 2_000,
        max_inbound_own_bytes: 1_000,
        local_node: Some(me),
        ..Config::default()
    });
    // Two inbound messages fill the pinning budget exactly.
    let a = envelope(Kind::Direct, 2, 7, 1, SZ, 3600);
    let b = envelope(Kind::Direct, 3, 7, 2, SZ, 3600);
    // The next two are still stored, but as ordinary transit traffic.
    let c = envelope(Kind::Direct, 4, 7, 3, SZ, 3600);
    let d = envelope(Kind::Direct, 5, 7, 4, SZ, 3600);
    for (i, e) in [&a, &b, &c, &d].into_iter().enumerate() {
        assert!(inserted(&s.insert(e, T0 + i as u64).unwrap()));
    }
    assert_eq!(s.stats().unwrap().total_bytes, 2_000);

    // One more inbound message needs room: it evicts the oldest *unpinned* one, `c`.
    let e = envelope(Kind::Direct, 6, 7, 5, SZ, 3600);
    let out = s.insert(&e, T0 + 10).unwrap();
    assert!(matches!(out, InsertOutcome::Inserted { evicted: 1, .. }));
    assert!(!s.contains(&c.message_id().unwrap(), T0 + 10).unwrap());
    for kept in [&a, &b, &d, &e] {
        assert!(s.contains(&kept.message_id().unwrap(), T0 + 10).unwrap());
    }
    s.check_integrity().unwrap();
}

#[test]
fn a_flood_of_inbound_messages_cannot_block_the_users_own_messages() {
    let me = node(7);
    let mut s = store_with(Config {
        max_total_bytes: 10_000,
        max_inbound_own_bytes: 2_000,
        local_node: Some(me),
        ..Config::default()
    });
    // Strangers address far more data to us than the store can hold.
    for n in 0..60u32 {
        let sender = u8::try_from(10 + n % 100).unwrap();
        let _ = s
            .insert(
                &envelope(Kind::Direct, sender, 7, n, SZ, 3600),
                T0 + u64::from(n),
            )
            .unwrap();
        assert!(s.stats().unwrap().total_bytes <= 10_000);
    }
    // The store is full, yet the user can still write a message: the flood is evictable.
    let mine = envelope(Kind::Broadcast, 7, 0, 1_000, SZ, 3600);
    let out = s.insert(&mine, T0 + 100).unwrap();
    assert!(inserted(&out), "got {out:?}");
    assert!(s.contains(&mine.message_id().unwrap(), T0 + 100).unwrap());
    s.check_integrity().unwrap();
}

#[test]
fn acks_created_locally_count_towards_the_budget() {
    let me = node(7);
    let mut s = store_with(Config {
        max_total_bytes: 1_000,
        // Room for two acks (2 x 178 = 356), not for three.
        max_inbound_own_bytes: 2 * ACK + 10,
        local_node: Some(me),
        ..Config::default()
    });
    let ack1 = envelope(Kind::Ack, 7, 2, 1, SZ, 3600);
    let ack2 = envelope(Kind::Ack, 7, 3, 2, SZ, 3600);
    let ack3 = envelope(Kind::Ack, 7, 4, 3, SZ, 3600);
    for e in [&ack1, &ack2, &ack3] {
        assert!(inserted(&s.insert(e, T0).unwrap()));
    }
    // A 500-byte broadcast does not fit (534 + 500 > 1_000). The third ack is no longer
    // pinned, so it is the one that goes.
    let big = envelope(Kind::Broadcast, 1, 0, 9, SZ, 3600);
    let out = s.insert(&big, T0 + 1).unwrap();
    assert!(matches!(out, InsertOutcome::Inserted { evicted: 1, .. }));
    assert!(!s.contains(&ack3.message_id().unwrap(), T0 + 1).unwrap());
    assert!(s.contains(&ack1.message_id().unwrap(), T0 + 1).unwrap());
    assert!(s.contains(&ack2.message_id().unwrap(), T0 + 1).unwrap());
    s.check_integrity().unwrap();
}

#[test]
fn messages_the_user_wrote_are_never_demoted() {
    let me = node(7);
    let mut s = store_with(Config {
        max_total_bytes: 2_000,
        max_inbound_own_bytes: 0,
        local_node: Some(me),
        ..Config::default()
    });
    let mine: Vec<_> = (1..=4)
        .map(|n| envelope(Kind::Direct, 7, 2, n, SZ, 3600))
        .collect();
    for e in &mine {
        assert!(inserted(&s.insert(e, T0).unwrap()));
    }
    // Everything the user wrote stays; an inbound message (unpinned with a zero budget)
    // cannot displace it.
    let inbound = envelope(Kind::Direct, 2, 7, 50, SZ, 3600);
    assert_eq!(
        s.insert(&inbound, T0 + 1).unwrap(),
        InsertOutcome::QuotaExceeded
    );
    for e in &mine {
        assert!(s.contains(&e.message_id().unwrap(), T0 + 1).unwrap());
    }
}

#[test]
fn deleting_pinned_messages_frees_budget_again() {
    let me = node(7);
    let mut s = store_with(Config {
        max_total_bytes: 10_000,
        max_inbound_own_bytes: SZ as u64,
        local_node: Some(me),
        ..Config::default()
    });
    let first = envelope(Kind::Direct, 2, 7, 1, SZ, 3600);
    let second = envelope(Kind::Direct, 3, 7, 2, SZ, 3600);
    assert!(inserted(&s.insert(&first, T0).unwrap()));
    assert!(inserted(&s.insert(&second, T0).unwrap())); // stored unpinned
    assert!(s.delete(&first.message_id().unwrap(), T0 + 1).unwrap());

    // The budget is free again, so the next inbound message is pinned. Fill the store
    // with transit traffic and check that it survives.
    let third = envelope(Kind::Direct, 4, 7, 3, SZ, 3600);
    assert!(inserted(&s.insert(&third, T0 + 2).unwrap()));
    for n in 0..40u32 {
        let _ = s
            .insert(
                &envelope(Kind::Direct, 20, 21, 100 + n, SZ, 3600),
                T0 + 3 + u64::from(n),
            )
            .unwrap();
    }
    assert!(s.contains(&third.message_id().unwrap(), T0 + 100).unwrap());
    s.check_integrity().unwrap();
}
