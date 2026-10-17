//! Property tests: the store against a simple model, and quota invariants.

mod common;

use std::collections::hash_map::Entry;
use std::collections::{HashMap, HashSet};

use common::*;
use proptest::collection::vec;
use proptest::prelude::*;
use proto::{Kind, MessageId};
use storage::{Config, InsertOutcome};

const T0: u64 = 1_000;

#[derive(Clone, Debug)]
enum Op {
    Insert(u8),
    Delete(u8),
}

fn arb_ops() -> impl Strategy<Value = Vec<Op>> {
    vec(
        prop_oneof![
            (0u8..12).prop_map(Op::Insert),
            (0u8..12).prop_map(Op::Delete)
        ],
        0..60,
    )
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(48))]

    /// With a budget that never binds and no clock movement, the store must behave
    /// like a set of live ids plus a set of tombstoned ids.
    #[test]
    fn behaves_like_a_set_with_tombstones(ops in arb_ops()) {
        let mut s = store();
        let mut live: HashMap<u8, MessageId> = HashMap::new();
        let mut dead: HashSet<u8> = HashSet::new();

        for op in ops {
            match op {
                Op::Insert(n) => {
                    let env = broadcast(u32::from(n));
                    let out = s.insert(&env, T0).unwrap();
                    if dead.contains(&n) {
                        prop_assert_eq!(out, InsertOutcome::Tombstoned);
                    } else if let Entry::Vacant(slot) = live.entry(n) {
                        let inserted = matches!(out, InsertOutcome::Inserted { .. });
                        prop_assert!(inserted);
                        slot.insert(env.message_id().unwrap());
                    } else {
                        prop_assert_eq!(out, InsertOutcome::Duplicate);
                    }
                }
                Op::Delete(n) => {
                    let id = broadcast(u32::from(n)).message_id().unwrap();
                    let removed = s.delete(&id, T0).unwrap();
                    prop_assert_eq!(removed, live.remove(&n).is_some());
                    if removed {
                        dead.insert(n);
                    }
                }
            }
            prop_assert_eq!(s.stats().unwrap().message_count, live.len() as u64);
            s.check_integrity().unwrap();
        }
        for n in 0u8..12 {
            let id = broadcast(u32::from(n)).message_id().unwrap();
            prop_assert_eq!(s.contains(&id, T0).unwrap(), live.contains_key(&n));
            prop_assert_eq!(s.is_tombstoned(&id, T0).unwrap(), dead.contains(&n));
        }
        // The log lists exactly the live messages, in strictly increasing order.
        let listed = s.list_after(0, 1000, T0).unwrap();
        prop_assert_eq!(listed.len(), live.len());
        prop_assert!(listed.windows(2).all(|w| w[0].seq < w[1].seq));
    }
}

#[derive(Clone, Debug)]
struct Item {
    kind: u8,
    sender: u8,
    recipient: u8,
    nonce: u32,
}

fn arb_items() -> impl Strategy<Value = Vec<Item>> {
    vec(
        (0u8..3, 1u8..4, 1u8..4, any::<u32>()).prop_map(|(kind, sender, recipient, nonce)| Item {
            kind,
            sender,
            recipient,
            nonce,
        }),
        0..80,
    )
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(48))]

    /// Whatever is inserted, the byte budget holds, the counters stay consistent,
    /// and an Own message that was accepted is never evicted.
    #[test]
    fn quota_invariants_hold(items in arb_items()) {
        let me = node(1);
        let mut s = store_with(Config {
            max_total_bytes: 3_000,
            local_node: Some(me),
            ..Config::default()
        });
        let mut own_ids = Vec::new();

        for (i, it) in items.iter().enumerate() {
            let kind = match it.kind {
                0 => Kind::Broadcast,
                1 => Kind::Direct,
                _ => Kind::Ack,
            };
            let env = envelope(kind, it.sender, it.recipient, it.nonce, 300 + (i % 4) * 100, 3600);
            let now = T0 + i as u64;
            let out = s.insert(&env, now).unwrap();
            let is_own = env.body.sender == me || env.body.recipient == Some(me);
            if is_own && matches!(out, InsertOutcome::Inserted { .. }) {
                own_ids.push(env.message_id().unwrap());
            }
            prop_assert!(s.stats().unwrap().total_bytes <= 3_000);
            s.check_integrity().unwrap();
        }
        let now = T0 + items.len() as u64;
        for id in own_ids {
            prop_assert!(s.contains(&id, now).unwrap());
        }
    }
}
