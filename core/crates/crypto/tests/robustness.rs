//! Hostile-input tests: nothing a remote party can send may panic the crypto layer, and
//! the strict verification rules must hold.

use crypto::seal::open;
use crypto::{ContactCard, CryptoError, Identity, verify_envelope};
use proptest::collection::vec;
use proptest::prelude::*;
use proto::{Envelope, EnvelopeBody, Kind, NodeId};

/// The group order of Ed25519 (little-endian), used to build a non-canonical signature.
const GROUP_ORDER_LE: [u8; 32] = [
    0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10,
];

proptest! {
    #![proptest_config(ProptestConfig::with_cases(64))]

    #[test]
    fn contact_cards_from_garbage_are_rejected_without_panicking(bytes in vec(any::<u8>(), 0..300)) {
        // A random 128-byte string carries a valid self-signature with negligible probability.
        prop_assert!(ContactCard::from_bytes(&bytes).is_err());
    }

    #[test]
    fn opening_garbage_never_panics_and_never_succeeds(
        seed in any::<[u8; 32]>(),
        sender in any::<[u8; 32]>(),
        sealed in vec(any::<u8>(), 0..1100),
    ) {
        let me = Identity::from_seed(&seed).unwrap();
        prop_assert_eq!(
            open(&me, &NodeId::new(sender), &sealed).unwrap_err(),
            CryptoError::Decrypt
        );
    }

    #[test]
    fn random_envelopes_never_verify(
        sender in any::<[u8; 32]>(),
        sig in vec(any::<u8>(), 64),
        payload in vec(any::<u8>(), 0..64),
    ) {
        let mut signature = [0u8; 64];
        signature.copy_from_slice(&sig);
        let env = Envelope {
            body: EnvelopeBody {
                kind: Kind::Broadcast,
                sender: NodeId::new(sender),
                recipient: None,
                created_at_ms: 1,
                lifetime_secs: 60,
                ext: vec![],
                payload,
            },
            signature,
        };
        prop_assert!(verify_envelope(&env).is_err());
    }
}

fn broadcast_with_signature(sender: [u8; 32], signature: [u8; 64]) -> Envelope {
    Envelope {
        body: EnvelopeBody {
            kind: Kind::Broadcast,
            sender: NodeId::new(sender),
            recipient: None,
            created_at_ms: 1,
            lifetime_secs: 60,
            ext: vec![],
            payload: b"x".to_vec(),
        },
        signature,
    }
}

#[test]
fn small_order_sender_keys_are_rejected_even_with_an_all_zero_signature() {
    // The identity point and the other small-order encodings make a classic forgery:
    // with such a key many (message, signature) pairs verify under lenient rules.
    let mut identity_point = [0u8; 32];
    identity_point[0] = 1;
    let mut minus_identity = [0xffu8; 32];
    minus_identity[0] = 0xec;
    minus_identity[31] = 0x7f;
    let zero_point = [0u8; 32];
    for key in [identity_point, minus_identity, zero_point] {
        let env = broadcast_with_signature(key, [0u8; 64]);
        assert!(verify_envelope(&env).is_err(), "key {key:02x?}");
    }
}

#[test]
fn a_signature_with_a_non_reduced_scalar_is_rejected() {
    let id = Identity::from_seed(&[5; 32]).unwrap();
    let env = id.broadcast(b"malleable?", 1, 60).unwrap();
    verify_envelope(&env).unwrap();

    // Add the group order to S: the same signature in a different encoding.
    let mut tampered = env.clone();
    let mut carry = 0u16;
    for (i, order_byte) in GROUP_ORDER_LE.iter().enumerate() {
        let sum = u16::from(env.signature[32 + i]) + u16::from(*order_byte) + carry;
        tampered.signature[32 + i] = (sum & 0xff) as u8;
        carry = sum >> 8;
    }
    assert_eq!(carry, 0, "S + L must still fit in 32 bytes");
    assert_ne!(tampered.signature, env.signature);
    assert!(verify_envelope(&tampered).is_err());
}

#[test]
fn a_low_order_ephemeral_key_cannot_be_used_to_open_a_box() {
    let me = Identity::from_seed(&[8; 32]).unwrap();
    let sender = NodeId::new([1; 32]);
    // 32 zero bytes are the u = 0 point; the rest is arbitrary ciphertext.
    let sealed = vec![0u8; 80];
    assert_eq!(
        open(&me, &sender, &sealed).unwrap_err(),
        CryptoError::Decrypt
    );
}
