//! Link packets from the air: parsing must never panic, and whatever parses must be canonical.
//!
//! Run: `cargo +nightly fuzz run packet_decode` from `core/fuzz`'s parent (`core/`).
#![no_main]

use libfuzzer_sys::fuzz_target;
use proto::Packet;

fuzz_target!(|data: &[u8]| {
    if let Ok(packet) = Packet::decode(data) {
        let again = packet.encode().expect("a parsed packet must encode");
        assert_eq!(again, data, "parsing is not canonical");
    }
});
