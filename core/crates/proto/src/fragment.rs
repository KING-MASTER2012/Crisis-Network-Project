//! Fragmentation: cuts a [`Frame`] into pieces that fit a BLE MTU.
//!
//! ```text
//! transfer_id  [8]  the first 8 bytes of the MessageId
//! index        u8   0..total
//! total        u8   1..=255
//! data         [..] at least 1 byte
//! ```
//!
//! This module is **stateless**: [`split`] and [`reassemble`] are pure functions.
//! Timeouts, memory limits and per-peer tracking of which fragment came from whom
//! belong to the `mesh` crate (phase C3).

use crate::error::{ProtoError, Result};
use crate::frame::Frame;
use crate::limits::{FRAGMENT_HEADER_LEN, MAX_FRAGMENTS, MAX_FRAME_LEN};

/// One fragment.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Fragment {
    /// Tag that groups the fragments of one message (first 8 bytes of its `MessageId`).
    pub transfer_id: [u8; 8],
    /// Position of this fragment (starts at 0).
    pub index: u8,
    /// Total number of fragments.
    pub total: u8,
    /// The slice of the frame bytes carried by this fragment.
    pub data: Vec<u8>,
}

impl Fragment {
    /// Encodes the fragment canonically.
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(FRAGMENT_HEADER_LEN + self.data.len());
        out.extend_from_slice(&self.transfer_id);
        out.push(self.index);
        out.push(self.total);
        out.extend_from_slice(&self.data);
        out
    }

    /// Strict parsing.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() <= FRAGMENT_HEADER_LEN {
            return Err(ProtoError::Fragment(
                "fragment is empty or shorter than its header",
            ));
        }
        let mut transfer_id = [0u8; 8];
        transfer_id.copy_from_slice(&bytes[..8]);
        let index = bytes[8];
        let total = bytes[9];
        if total == 0 || index >= total {
            return Err(ProtoError::Fragment("invalid index/total"));
        }
        let data = &bytes[FRAGMENT_HEADER_LEN..];
        if data.len() > MAX_FRAME_LEN {
            return Err(ProtoError::TooLong {
                field: "fragment data",
                len: data.len(),
                max: MAX_FRAME_LEN,
            });
        }
        Ok(Self {
            transfer_id,
            index,
            total,
            data: data.to_vec(),
        })
    }
}

/// Splits a frame so that every encoded fragment is at most `max_fragment_size`
/// bytes (header included).
pub fn split(frame: &Frame, max_fragment_size: usize) -> Result<Vec<Fragment>> {
    if max_fragment_size <= FRAGMENT_HEADER_LEN {
        return Err(ProtoError::Fragment(
            "max_fragment_size must be larger than the header",
        ));
    }
    let bytes = frame.encode()?;
    let transfer_id = frame.envelope.message_id()?.transfer_tag();
    let chunk = max_fragment_size - FRAGMENT_HEADER_LEN;
    let total = bytes.len().div_ceil(chunk);
    if total > MAX_FRAGMENTS {
        return Err(ProtoError::Fragment("too many fragments (>255)"));
    }
    Ok(bytes
        .chunks(chunk)
        .enumerate()
        .map(|(i, c)| Fragment {
            transfer_id,
            index: i as u8, // total <= 255, so i <= 254
            total: total as u8,
            data: c.to_vec(),
        })
        .collect())
}

/// Rebuilds the frame from **all** fragments of one message.
///
/// Order does not matter. An exact duplicate of a fragment is accepted; a
/// duplicate with different content, a missing fragment, mixed `transfer_id`/`total`
/// values, or a `transfer_id` that does not match the id of the reassembled
/// frame are all errors.
pub fn reassemble(fragments: &[Fragment]) -> Result<Frame> {
    let first = fragments
        .first()
        .ok_or(ProtoError::Fragment("fragment list is empty"))?;
    let total = usize::from(first.total);
    let mut slots: Vec<Option<&[u8]>> = vec![None; total];
    let mut size = 0usize;
    for f in fragments {
        if f.transfer_id != first.transfer_id || f.total != first.total {
            return Err(ProtoError::Fragment(
                "fragments belong to different transfers",
            ));
        }
        let slot = slots
            .get_mut(usize::from(f.index))
            .ok_or(ProtoError::Fragment("index exceeds total"))?;
        match *slot {
            Some(existing) if existing != f.data.as_slice() => {
                return Err(ProtoError::Fragment("conflicting data for the same index"));
            }
            Some(_) => {}
            None => {
                size += f.data.len();
                if size > MAX_FRAME_LEN {
                    return Err(ProtoError::Fragment(
                        "combined size exceeds the maximum frame length",
                    ));
                }
                *slot = Some(f.data.as_slice());
            }
        }
    }
    let mut bytes = Vec::with_capacity(size);
    for s in &slots {
        bytes.extend_from_slice(s.ok_or(ProtoError::Fragment("missing fragment"))?);
    }
    let frame = Frame::decode(&bytes)?;
    if frame.envelope.message_id()?.transfer_tag() != first.transfer_id {
        return Err(ProtoError::Fragment(
            "transfer_id does not match the message id",
        ));
    }
    Ok(frame)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::envelope::{Envelope, EnvelopeBody, Kind};
    use crate::id::NodeId;

    fn frame(payload_len: usize) -> Frame {
        Frame {
            hops_left: 4,
            envelope: Envelope {
                body: EnvelopeBody {
                    kind: Kind::Broadcast,
                    sender: NodeId::new([3; 32]),
                    recipient: None,
                    created_at_ms: 42,
                    lifetime_secs: 60,
                    ext: vec![],
                    payload: (0..payload_len).map(|i| i as u8).collect(),
                },
                signature: [5; 64],
            },
        }
    }

    #[test]
    fn split_then_reassemble() {
        let f = frame(900);
        let parts = split(&f, 100).unwrap();
        assert!(parts.len() > 1);
        assert!(parts.iter().all(|p| p.encode().len() <= 100));
        assert_eq!(reassemble(&parts).unwrap(), f);
    }

    #[test]
    fn reversed_order_and_duplicates_are_fine() {
        let f = frame(300);
        let mut parts = split(&f, 64).unwrap();
        parts.reverse();
        let dup = parts[0].clone();
        parts.push(dup);
        assert_eq!(reassemble(&parts).unwrap(), f);
    }

    #[test]
    fn missing_conflicting_and_mixed_are_errors() {
        let f = frame(300);
        let parts = split(&f, 64).unwrap();
        assert!(reassemble(&parts[1..]).is_err());

        let mut bad = parts.clone();
        bad[1].data[0] ^= 1;
        let dup = parts[1].clone();
        bad.push(dup);
        assert!(reassemble(&bad).is_err());

        let other = split(&frame(301), 64).unwrap();
        let mut mixed = parts.clone();
        mixed[0] = other[0].clone();
        assert!(reassemble(&mixed).is_err());
    }

    #[test]
    fn tampered_data_never_panics() {
        let f = frame(300);
        let mut parts = split(&f, 64).unwrap();
        let last = parts.len() - 1;
        parts[last].data[0] ^= 0xff;
        // Touching the body changes the id and is rejected here; touching only the
        // signature keeps the id, and `crypto::verify_envelope` rejects it later.
        let _ = reassemble(&parts); // must not panic
    }

    #[test]
    fn size_limits() {
        let f = frame(10);
        assert!(split(&f, FRAGMENT_HEADER_LEN).is_err());
        assert!(Fragment::decode(&[0; FRAGMENT_HEADER_LEN]).is_err());
        let mut b = vec![0u8; FRAGMENT_HEADER_LEN + 1];
        b[8] = 3;
        b[9] = 3; // index >= total
        assert!(Fragment::decode(&b).is_err());
        let parts = split(&f, 64).unwrap();
        let p = &parts[0];
        assert_eq!(&Fragment::decode(&p.encode()).unwrap(), p);
    }
}
