//! Fragmentation: arbitrary fragment sets never panic; split + reassemble is lossless.
#![no_main]

use arbitrary::Arbitrary;
use libfuzzer_sys::fuzz_target;
use proto::limits::FRAGMENT_HEADER_LEN;
use proto::{Fragment, Frame, reassemble, split};

#[derive(Arbitrary, Debug)]
struct Part {
    transfer_id: [u8; 8],
    index: u8,
    total: u8,
    data: Vec<u8>,
}

#[derive(Arbitrary, Debug)]
struct Input {
    frame_bytes: Vec<u8>,
    mtu: u16,
    parts: Vec<Part>,
}

fuzz_target!(|input: Input| {
    let parts: Vec<Fragment> = input
        .parts
        .into_iter()
        .map(|p| Fragment {
            transfer_id: p.transfer_id,
            index: p.index,
            total: p.total,
            data: p.data,
        })
        .collect();
    let _ = reassemble(&parts);

    if let Ok(frame) = Frame::decode(&input.frame_bytes) {
        let size = usize::from(input.mtu).max(FRAGMENT_HEADER_LEN + 1);
        if let Ok(pieces) = split(&frame, size) {
            assert_eq!(reassemble(&pieces).expect("own fragments reassemble"), frame);
        }
    }
});
