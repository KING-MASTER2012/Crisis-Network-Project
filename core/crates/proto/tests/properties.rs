//! Ozellik (property) testleri: kodlama/cozme ve parcalama degismezleri.

use proptest::collection::vec;
use proptest::prelude::*;
use proto::limits::*;
use proto::{Envelope, EnvelopeBody, Fragment, Frame, Kind, NodeId, reassemble, split};

fn arb_sig() -> impl Strategy<Value = [u8; 64]> {
    // proptest, 32'den buyuk diziler icin Arbitrary saglamaz.
    vec(any::<u8>(), 64).prop_map(|v| {
        let mut a = [0u8; 64];
        a.copy_from_slice(&v);
        a
    })
}

fn arb_body() -> impl Strategy<Value = EnvelopeBody> {
    (
        0u8..3,
        any::<[u8; 32]>(),
        any::<[u8; 32]>(),
        any::<u64>(),
        1u32..=MAX_LIFETIME_SECS,
        vec(any::<u8>(), 0..=MAX_EXT_LEN),
        vec(any::<u8>(), 0..=MAX_PAYLOAD_LEN),
        any::<[u8; 32]>(),
    )
        .prop_map(
            |(k, s, r, created_at_ms, lifetime_secs, ext, payload, ack_id)| {
                let (kind, recipient, payload) = match k {
                    0 => (Kind::Broadcast, None, payload),
                    1 => {
                        let mut p = payload;
                        if p.len() < SEAL_OVERHEAD {
                            p.resize(SEAL_OVERHEAD, 0);
                        }
                        (Kind::Direct, Some(NodeId::new(r)), p)
                    }
                    _ => (Kind::Ack, Some(NodeId::new(r)), ack_id.to_vec()),
                };
                EnvelopeBody {
                    kind,
                    sender: NodeId::new(s),
                    recipient,
                    created_at_ms,
                    lifetime_secs,
                    ext,
                    payload,
                }
            },
        )
}

fn arb_frame() -> impl Strategy<Value = Frame> {
    (arb_body(), arb_sig(), 0..=MAX_HOPS).prop_map(|(body, signature, hops_left)| Frame {
        hops_left,
        envelope: Envelope { body, signature },
    })
}

/// Deterministik Fisher-Yates (test icin harici rastgelelik gerekmesin).
fn shuffle<T>(v: &mut [T], mut seed: u64) {
    for i in (1..v.len()).rev() {
        seed = seed
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        let j = (seed >> 33) as usize % (i + 1);
        v.swap(i, j);
    }
}

proptest! {
    #[test]
    fn envelope_roundtrip(body in arb_body(), signature in arb_sig()) {
        let env = Envelope { body, signature };
        let bytes = env.encode().unwrap();
        prop_assert!(bytes.len() <= MAX_ENVELOPE_LEN);
        prop_assert_eq!(Envelope::decode(&bytes).unwrap(), env);
    }

    #[test]
    fn message_id_is_deterministic_and_signature_independent(
        body in arb_body(), s1 in arb_sig(), s2 in arb_sig()
    ) {
        let a = Envelope { body: body.clone(), signature: s1 };
        let b = Envelope { body, signature: s2 };
        prop_assert_eq!(a.message_id().unwrap(), b.message_id().unwrap());
    }

    #[test]
    fn frame_roundtrip(f in arb_frame()) {
        let bytes = f.encode().unwrap();
        prop_assert!(bytes.len() <= MAX_FRAME_LEN);
        prop_assert_eq!(Frame::decode(&bytes).unwrap(), f);
    }

    #[test]
    fn decoders_never_panic_on_garbage(bytes in vec(any::<u8>(), 0..2048)) {
        let _ = Envelope::decode(&bytes);
        let _ = Frame::decode(&bytes);
        let _ = Fragment::decode(&bytes);
    }

    #[test]
    fn any_prefix_of_valid_frame_is_rejected(f in arb_frame(), cut in any::<prop::sample::Index>()) {
        let bytes = f.encode().unwrap();
        let n = cut.index(bytes.len()); // 0..len-1
        prop_assert!(Frame::decode(&bytes[..n]).is_err());
    }

    #[test]
    fn split_reassemble_is_order_independent(
        f in arb_frame(),
        size in (FRAGMENT_HEADER_LEN + 8)..600usize,
        seed in any::<u64>(),
    ) {
        let mut parts = split(&f, size).unwrap();
        prop_assert!(parts.iter().all(|p| p.encode().len() <= size));
        shuffle(&mut parts, seed);
        prop_assert_eq!(reassemble(&parts).unwrap(), f);
    }

    #[test]
    fn dropping_any_fragment_is_detected(
        f in arb_frame(), size in (FRAGMENT_HEADER_LEN + 8)..200usize, drop_at in any::<prop::sample::Index>()
    ) {
        let mut parts = split(&f, size).unwrap();
        let i = drop_at.index(parts.len());
        parts.remove(i);
        let r = reassemble(&parts);
        prop_assert!(r.is_err());
    }
}
