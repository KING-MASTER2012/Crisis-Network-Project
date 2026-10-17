//! Remote-controlled crypto inputs: cards, sealed boxes and envelope signatures.
#![no_main]

use arbitrary::Arbitrary;
use crypto::{ContactCard, Identity, seal, verify_envelope};
use libfuzzer_sys::fuzz_target;
use proto::{Envelope, NodeId};

#[derive(Arbitrary, Debug)]
struct Input {
    card: Vec<u8>,
    envelope: Vec<u8>,
    sealed: Vec<u8>,
    sender: [u8; 32],
}

fuzz_target!(|input: Input| {
    if let Ok(card) = ContactCard::from_bytes(&input.card) {
        assert_eq!(card.to_bytes().as_slice(), input.card.as_slice());
    }
    if let Ok(envelope) = Envelope::decode(&input.envelope) {
        let _ = verify_envelope(&envelope);
    }
    let me = Identity::from_seed(&[7; 32]).expect("fixed seed");
    let _ = seal::open(&me, &NodeId::new(input.sender), &input.sealed);
});
