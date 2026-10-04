//! Link-layer packets exchanged directly between two neighbouring nodes.
//!
//! Every packet starts with a one-byte tag:
//!
//! ```text
//! 1  Inv       count u8 (1..=15), then count x MessageId   "I have these messages"
//! 2  Want      count u8 (1..=15), then count x MessageId   "please send me these"
//! 3  Fragment  an encoded Fragment                          a piece of a Frame
//! ```
//!
//! A packet must fit one transport MTU. `Inv` and `Want` are sized by their id
//! count; `Fragment` packets are produced by [`crate::split`] with a size limit of
//! `mtu - 1` (the tag takes one byte).

use crate::codec::Reader;
use crate::error::{ProtoError, Result};
use crate::fragment::Fragment;
use crate::id::MessageId;
use crate::limits::MESSAGE_ID_LEN;

/// Maximum number of ids in one `Inv` or `Want` packet.
pub const MAX_IDS_PER_PACKET: usize = 15;

const TAG_INV: u8 = 1;
const TAG_WANT: u8 = 2;
const TAG_FRAGMENT: u8 = 3;

/// A link-layer packet.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Packet {
    /// The sender holds these messages.
    Inv(Vec<MessageId>),
    /// The sender asks for these messages.
    Want(Vec<MessageId>),
    /// A fragment of a frame.
    Fragment(Fragment),
}

fn encode_ids(tag: u8, ids: &[MessageId]) -> Result<Vec<u8>> {
    if ids.is_empty() || ids.len() > MAX_IDS_PER_PACKET {
        return Err(ProtoError::InvalidField("id list length is out of range"));
    }
    let mut out = Vec::with_capacity(2 + ids.len() * MESSAGE_ID_LEN);
    out.push(tag);
    out.push(ids.len() as u8); // at most MAX_IDS_PER_PACKET
    for id in ids {
        out.extend_from_slice(id.as_bytes());
    }
    Ok(out)
}

fn read_ids(r: &mut Reader<'_>) -> Result<Vec<MessageId>> {
    let count = usize::from(r.u8()?);
    if count == 0 || count > MAX_IDS_PER_PACKET {
        return Err(ProtoError::InvalidField("id list length is out of range"));
    }
    let mut ids = Vec::with_capacity(count);
    for _ in 0..count {
        ids.push(MessageId::new(r.array::<MESSAGE_ID_LEN>()?));
    }
    Ok(ids)
}

impl Packet {
    /// Encodes the packet.
    pub fn encode(&self) -> Result<Vec<u8>> {
        match self {
            Packet::Inv(ids) => encode_ids(TAG_INV, ids),
            Packet::Want(ids) => encode_ids(TAG_WANT, ids),
            Packet::Fragment(f) => {
                let body = f.encode();
                let mut out = Vec::with_capacity(1 + body.len());
                out.push(TAG_FRAGMENT);
                out.extend_from_slice(&body);
                Ok(out)
            }
        }
    }

    /// Strict parsing: unknown tags, empty or oversized id lists, truncated and
    /// trailing bytes are all errors.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        let mut r = Reader::new(bytes);
        match r.u8()? {
            TAG_INV => {
                let ids = read_ids(&mut r)?;
                r.finish()?;
                Ok(Packet::Inv(ids))
            }
            TAG_WANT => {
                let ids = read_ids(&mut r)?;
                r.finish()?;
                Ok(Packet::Want(ids))
            }
            TAG_FRAGMENT => Ok(Packet::Fragment(Fragment::decode(&bytes[1..])?)),
            other => Err(ProtoError::UnknownKind(other)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ids(n: usize) -> Vec<MessageId> {
        (0..n).map(|i| MessageId::new([i as u8; 32])).collect()
    }

    #[test]
    fn id_packets_roundtrip() {
        for n in [1, 2, MAX_IDS_PER_PACKET] {
            let inv = Packet::Inv(ids(n));
            assert_eq!(Packet::decode(&inv.encode().unwrap()).unwrap(), inv);
            let want = Packet::Want(ids(n));
            assert_eq!(Packet::decode(&want.encode().unwrap()).unwrap(), want);
        }
    }

    #[test]
    fn fragment_packet_roundtrip() {
        let f = Fragment {
            transfer_id: [1; 8],
            index: 0,
            total: 2,
            data: vec![9, 8, 7],
        };
        let p = Packet::Fragment(f);
        assert_eq!(Packet::decode(&p.encode().unwrap()).unwrap(), p);
    }

    #[test]
    fn rejects_bad_id_lists() {
        assert!(Packet::Inv(vec![]).encode().is_err());
        assert!(Packet::Want(ids(MAX_IDS_PER_PACKET + 1)).encode().is_err());
        // Count byte says 2 but only one id follows.
        let mut b = Packet::Inv(ids(1)).encode().unwrap();
        b[1] = 2;
        assert_eq!(Packet::decode(&b), Err(ProtoError::Truncated));
        // Zero count.
        assert!(Packet::decode(&[TAG_INV, 0]).is_err());
        // Count above the maximum.
        assert!(Packet::decode(&[TAG_WANT, 200]).is_err());
    }

    #[test]
    fn rejects_trailing_truncated_and_unknown() {
        let mut b = Packet::Inv(ids(2)).encode().unwrap();
        b.push(0);
        assert_eq!(Packet::decode(&b), Err(ProtoError::TrailingBytes));
        assert!(Packet::decode(&[]).is_err());
        assert_eq!(Packet::decode(&[9, 0, 0]), Err(ProtoError::UnknownKind(9)));
        assert!(Packet::decode(&[TAG_FRAGMENT]).is_err());
    }
}
