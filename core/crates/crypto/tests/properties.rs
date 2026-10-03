//! Property tests: signature, encryption and tamper-detection invariants.

use crypto::seal::{open, seal};
use crypto::{CryptoError, Identity, verify_envelope};
use proptest::collection::vec;
use proptest::prelude::*;
use proto::Envelope;
use proto::limits::{MAX_LIFETIME_SECS, MAX_PLAINTEXT_LEN};

proptest! {
    #![proptest_config(ProptestConfig::with_cases(32))]

    #[test]
    fn direct_roundtrip_over_the_wire(
        seed_a in any::<[u8; 32]>(),
        seed_b in any::<[u8; 32]>(),
        pt in vec(any::<u8>(), 0..=MAX_PLAINTEXT_LEN),
        t in any::<u64>(),
        life in 1u32..=MAX_LIFETIME_SECS,
    ) {
        let a = Identity::from_seed(&seed_a).unwrap();
        let b = Identity::from_seed(&seed_b).unwrap();
        let env = a.direct(&b.contact_card(), &pt, t, life).unwrap();
        let wire = env.encode().unwrap();
        let decoded = Envelope::decode(&wire).unwrap();
        prop_assert!(verify_envelope(&decoded).is_ok());
        let opened = b.open_direct(&decoded).unwrap();
        prop_assert_eq!(opened.as_slice(), pt.as_slice());
    }

    #[test]
    fn wrong_recipient_cannot_open(
        seed_a in any::<[u8; 32]>(),
        seed_b in any::<[u8; 32]>(),
        seed_c in any::<[u8; 32]>(),
        pt in vec(any::<u8>(), 0..64),
    ) {
        prop_assume!(seed_b != seed_c);
        let a = Identity::from_seed(&seed_a).unwrap();
        let b = Identity::from_seed(&seed_b).unwrap();
        let c = Identity::from_seed(&seed_c).unwrap();
        let env = a.direct(&b.contact_card(), &pt, 1, 60).unwrap();
        prop_assert_eq!(c.open_direct(&env).unwrap_err(), CryptoError::NotForMe);
        // The box cannot be opened directly with someone else's key either.
        prop_assert_eq!(
            open(&c, &a.node_id(), &env.body.payload).unwrap_err(),
            CryptoError::Decrypt
        );
    }

    #[test]
    fn any_single_bit_flip_is_detected(
        seed_a in any::<[u8; 32]>(),
        seed_b in any::<[u8; 32]>(),
        pt in vec(any::<u8>(), 0..128),
        pos in any::<prop::sample::Index>(),
        bit in 0u8..8,
    ) {
        let a = Identity::from_seed(&seed_a).unwrap();
        let b = Identity::from_seed(&seed_b).unwrap();
        let env = a.direct(&b.contact_card(), &pt, 7, 60).unwrap();
        let mut wire = env.encode().unwrap();
        let i = pos.index(wire.len());
        wire[i] ^= 1 << bit;
        // Every byte must be caught either by parsing or by signature verification.
        let outcome = Envelope::decode(&wire).map_err(|_| ()).and_then(|e| verify_envelope(&e).map_err(|_| ()));
        prop_assert!(outcome.is_err());
    }

    #[test]
    fn sealed_box_tampering_is_detected(
        seed_a in any::<[u8; 32]>(),
        seed_b in any::<[u8; 32]>(),
        pt in vec(any::<u8>(), 0..128),
        pos in any::<prop::sample::Index>(),
        bit in 0u8..8,
    ) {
        let a = Identity::from_seed(&seed_a).unwrap();
        let b = Identity::from_seed(&seed_b).unwrap();
        let mut sealed = seal(&a.node_id(), &b.contact_card(), &pt).unwrap();
        let i = pos.index(sealed.len());
        sealed[i] ^= 1 << bit;
        prop_assert_eq!(open(&b, &a.node_id(), &sealed).unwrap_err(), CryptoError::Decrypt);
    }

    #[test]
    fn sealed_box_is_bound_to_sender(
        seed_a in any::<[u8; 32]>(),
        seed_b in any::<[u8; 32]>(),
        seed_x in any::<[u8; 32]>(),
        pt in vec(any::<u8>(), 0..64),
    ) {
        prop_assume!(seed_a != seed_x);
        let a = Identity::from_seed(&seed_a).unwrap();
        let b = Identity::from_seed(&seed_b).unwrap();
        let x = Identity::from_seed(&seed_x).unwrap();
        let sealed = seal(&a.node_id(), &b.contact_card(), &pt).unwrap();
        // The same box presented on behalf of a different sender does not open (aad).
        prop_assert!(open(&b, &x.node_id(), &sealed).is_err());
    }
}

#[test]
fn fresh_seals_differ_but_both_open() {
    let a = Identity::generate().unwrap();
    let b = Identity::generate().unwrap();
    let s1 = seal(&a.node_id(), &b.contact_card(), b"same").unwrap();
    let s2 = seal(&a.node_id(), &b.contact_card(), b"same").unwrap();
    assert_ne!(s1, s2, "the ephemeral key must be fresh every time");
    assert_eq!(open(&b, &a.node_id(), &s1).unwrap().as_slice(), b"same");
    assert_eq!(open(&b, &a.node_id(), &s2).unwrap().as_slice(), b"same");
}

#[test]
fn oversize_plaintext_is_rejected() {
    let a = Identity::generate().unwrap();
    let b = Identity::generate().unwrap();
    let big = vec![0u8; MAX_PLAINTEXT_LEN + 1];
    assert!(a.direct(&b.contact_card(), &big, 1, 60).is_err());
    let max = vec![0u8; MAX_PLAINTEXT_LEN];
    let env = a.direct(&b.contact_card(), &max, 1, 60).unwrap();
    assert!(env.encode().is_ok());
}

#[test]
fn ack_roundtrip() {
    let a = Identity::generate().unwrap();
    let b = Identity::generate().unwrap();
    let msg = a.broadcast(b"x", 1, 60).unwrap();
    let id = msg.message_id().unwrap();
    let ack = b.ack(&a.node_id(), &id, 2, 60).unwrap();
    verify_envelope(&Envelope::decode(&ack.encode().unwrap()).unwrap()).unwrap();
    assert_eq!(ack.body.payload, id.as_bytes().to_vec());
}
