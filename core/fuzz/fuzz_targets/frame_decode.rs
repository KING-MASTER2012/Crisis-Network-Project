//! Envelopes and frames: strict, canonical parsing; ids and signing input never panic.
#![no_main]

use libfuzzer_sys::fuzz_target;
use proto::{Envelope, Frame};

fuzz_target!(|data: &[u8]| {
    if let Ok(envelope) = Envelope::decode(data) {
        assert_eq!(envelope.encode().expect("parsed envelope encodes"), data);
        envelope.message_id().expect("parsed envelope has an id");
        envelope.body.signing_input().expect("parsed body has a signing input");
        let _ = crypto::verify_envelope(&envelope);
    }
    if let Ok(frame) = Frame::decode(data) {
        assert_eq!(frame.encode().expect("parsed frame encodes"), data);
        let _ = frame.forwarded();
    }
});
