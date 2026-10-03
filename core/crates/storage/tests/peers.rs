//! Peer bookkeeping.

mod common;

use common::*;
use storage::{Config, TrustState};

#[test]
fn touch_creates_and_updates_without_moving_backwards() {
    let mut s = store();
    let n = node(1);
    assert!(s.get_peer(&n).unwrap().is_none());

    s.touch_peer(&n, 100).unwrap();
    s.touch_peer(&n, 300).unwrap();
    s.touch_peer(&n, 200).unwrap(); // an older observation must not rewind last_seen
    let p = s.get_peer(&n).unwrap().unwrap();
    assert_eq!((p.first_seen_ms, p.last_seen_ms), (100, 300));
    assert_eq!(p.trust, TrustState::Unknown);
    assert_eq!(p.contact_card, None);
}

#[test]
fn trust_and_contact_card() {
    let mut s = store();
    let n = node(1);
    s.set_trust(&n, TrustState::Blocked, 50).unwrap();
    assert_eq!(s.get_peer(&n).unwrap().unwrap().trust, TrustState::Blocked);

    // Touching must not reset trust.
    s.touch_peer(&n, 60).unwrap();
    assert_eq!(s.get_peer(&n).unwrap().unwrap().trust, TrustState::Blocked);

    let card = vec![9u8; 128];
    s.set_contact_card(&n, &card, 70).unwrap();
    assert_eq!(s.get_peer(&n).unwrap().unwrap().contact_card, Some(card));
    assert!(s.set_contact_card(&n, &[1, 2, 3], 80).is_err());
}

#[test]
fn list_is_most_recent_first_and_forget_works() {
    let mut s = store();
    s.touch_peer(&node(1), 10).unwrap();
    s.touch_peer(&node(2), 30).unwrap();
    s.touch_peer(&node(3), 20).unwrap();
    let order: Vec<_> = s
        .list_peers(10)
        .unwrap()
        .into_iter()
        .map(|p| p.node_id)
        .collect();
    assert_eq!(order, vec![node(2), node(3), node(1)]);
    assert_eq!(s.list_peers(2).unwrap().len(), 2);

    assert!(s.forget_peer(&node(2)).unwrap());
    assert!(!s.forget_peer(&node(2)).unwrap());
    assert_eq!(s.stats().unwrap().peer_count, 2);
}

#[test]
fn peer_budget_trims_only_unknown_peers_least_recent_first() {
    let mut s = store_with(Config {
        max_peers: 3,
        ..Config::default()
    });
    s.set_trust(&node(1), TrustState::Verified, 1).unwrap(); // oldest, but protected
    s.touch_peer(&node(2), 2).unwrap();
    s.touch_peer(&node(3), 3).unwrap();
    s.touch_peer(&node(4), 4).unwrap(); // over budget: the least recent Unknown goes

    assert!(s.get_peer(&node(1)).unwrap().is_some());
    assert!(s.get_peer(&node(2)).unwrap().is_none());
    assert!(s.get_peer(&node(3)).unwrap().is_some());
    assert!(s.get_peer(&node(4)).unwrap().is_some());
    assert_eq!(s.stats().unwrap().peer_count, 3);
}
