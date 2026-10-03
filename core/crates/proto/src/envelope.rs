//! The signed envelope: the persistent message that travels through the network.
//!
//! Encoded layout (all integers big-endian):
//!
//! ```text
//! version        u8      = 1
//! kind           u8      1=Broadcast 2=Direct 3=Ack
//! flags          u8      must be 0 in v1
//! sender         [32]    the sender's Ed25519 public key
//! recipient      [32]    only for Direct and Ack
//! created_at_ms  u64     milliseconds since the Unix epoch (sender clock, UNTRUSTED)
//! lifetime_secs  u32     1..=MAX_LIFETIME_SECS
//! ext_len        u8      0..=MAX_EXT_LEN
//! ext            [ext_len]
//! payload_len    u16     0..=MAX_PAYLOAD_LEN
//! payload        [payload_len]
//! signature      [64]    Ed25519 over (DOMAIN_ENVELOPE_SIG || all fields above)
//! ```

use sha2::{Digest, Sha256};

use crate::codec::Reader;
use crate::domain::{DOMAIN_ENVELOPE_SIG, DOMAIN_MSG_ID};
use crate::error::{ProtoError, Result};
use crate::id::{MessageId, NodeId};
use crate::limits::{
    ACK_PAYLOAD_LEN, MAX_ENVELOPE_LEN, MAX_EXT_LEN, MAX_LIFETIME_SECS, MAX_PAYLOAD_LEN,
    NODE_ID_LEN, PROTOCOL_VERSION, SEAL_OVERHEAD, SIGNATURE_LEN,
};

/// Message kind.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum Kind {
    /// A public announcement: signed but not encrypted.
    Broadcast = 1,
    /// A message encrypted for a single recipient (sealed box).
    Direct = 2,
    /// Confirmation that a message arrived; the payload is the acknowledged [`MessageId`].
    Ack = 3,
}

impl Kind {
    /// Converts a wire value into a kind.
    pub fn from_u8(v: u8) -> Result<Self> {
        match v {
            1 => Ok(Kind::Broadcast),
            2 => Ok(Kind::Direct),
            3 => Ok(Kind::Ack),
            other => Err(ProtoError::UnknownKind(other)),
        }
    }

    /// Whether this kind carries a `recipient` field.
    pub fn has_recipient(self) -> bool {
        !matches!(self, Kind::Broadcast)
    }
}

/// The signed part of an envelope.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EnvelopeBody {
    /// Message kind.
    pub kind: Kind,
    /// The sender.
    pub sender: NodeId,
    /// The recipient (required for Direct/Ack, forbidden for Broadcast).
    pub recipient: Option<NodeId>,
    /// Creation time in milliseconds since the Unix epoch. This is the sender's
    /// (untrusted) clock: informational only.
    pub created_at_ms: u64,
    /// How long the message should live, in seconds; nodes may drop it afterwards.
    pub lifetime_secs: u32,
    /// Opaque, signed field reserved for future versions. v1 attaches no meaning
    /// to it but preserves it.
    pub ext: Vec<u8>,
    /// The body. For Direct this is the sealed-box output, for Ack the
    /// acknowledged message id.
    pub payload: Vec<u8>,
}

impl EnvelopeBody {
    /// Checks that the fields obey the per-kind and size rules.
    pub fn validate(&self) -> Result<()> {
        match (self.kind.has_recipient(), self.recipient.is_some()) {
            (true, false) => return Err(ProtoError::InvalidField("recipient is required")),
            (false, true) => {
                return Err(ProtoError::InvalidField(
                    "recipient is not allowed on a broadcast",
                ));
            }
            _ => {}
        }
        if self.lifetime_secs == 0 || self.lifetime_secs > MAX_LIFETIME_SECS {
            return Err(ProtoError::InvalidLifetime);
        }
        if self.ext.len() > MAX_EXT_LEN {
            return Err(ProtoError::TooLong {
                field: "ext",
                len: self.ext.len(),
                max: MAX_EXT_LEN,
            });
        }
        if self.payload.len() > MAX_PAYLOAD_LEN {
            return Err(ProtoError::TooLong {
                field: "payload",
                len: self.payload.len(),
                max: MAX_PAYLOAD_LEN,
            });
        }
        match self.kind {
            Kind::Direct if self.payload.len() < SEAL_OVERHEAD => Err(ProtoError::InvalidField(
                "direct payload is shorter than the sealed-box overhead",
            )),
            Kind::Ack if self.payload.len() != ACK_PAYLOAD_LEN => {
                Err(ProtoError::InvalidField("ack payload must be 32 bytes"))
            }
            _ => Ok(()),
        }
    }

    /// Encodes the body canonically. Signatures and ids are computed over these bytes.
    pub fn encode(&self) -> Result<Vec<u8>> {
        self.validate()?;
        let mut out = Vec::with_capacity(MAX_ENVELOPE_LEN);
        out.push(PROTOCOL_VERSION);
        out.push(self.kind as u8);
        out.push(0); // flags
        out.extend_from_slice(self.sender.as_bytes());
        if let Some(r) = &self.recipient {
            out.extend_from_slice(r.as_bytes());
        }
        out.extend_from_slice(&self.created_at_ms.to_be_bytes());
        out.extend_from_slice(&self.lifetime_secs.to_be_bytes());
        out.push(self.ext.len() as u8); // validate() guarantees <= MAX_EXT_LEN
        out.extend_from_slice(&self.ext);
        out.extend_from_slice(&(self.payload.len() as u16).to_be_bytes()); // <= 1024
        out.extend_from_slice(&self.payload);
        Ok(out)
    }

    /// The signature input: `DOMAIN_ENVELOPE_SIG || encode()`.
    pub fn signing_input(&self) -> Result<Vec<u8>> {
        let body = self.encode()?;
        let mut v = Vec::with_capacity(DOMAIN_ENVELOPE_SIG.len() + body.len());
        v.extend_from_slice(DOMAIN_ENVELOPE_SIG);
        v.extend_from_slice(&body);
        Ok(v)
    }

    /// The message id: `SHA-256(DOMAIN_MSG_ID || encode())`.
    pub fn message_id(&self) -> Result<MessageId> {
        let body = self.encode()?;
        let mut h = Sha256::new();
        h.update(DOMAIN_MSG_ID);
        h.update(&body);
        let digest: [u8; 32] = h.finalize().into();
        Ok(MessageId::new(digest))
    }

    /// The moment (in ms) the message expires according to the *sender's* clock.
    /// Saturates at `u64::MAX`. Receivers normally compute expiry from their own
    /// receive time instead.
    pub fn expires_at_ms(&self) -> u64 {
        self.created_at_ms
            .saturating_add(u64::from(self.lifetime_secs) * 1000)
    }

    fn read_from(r: &mut Reader<'_>) -> Result<Self> {
        let version = r.u8()?;
        if version != PROTOCOL_VERSION {
            return Err(ProtoError::UnsupportedVersion(version));
        }
        let kind = Kind::from_u8(r.u8()?)?;
        let flags = r.u8()?;
        if flags != 0 {
            return Err(ProtoError::NonZeroFlags(flags));
        }
        let sender = NodeId::new(r.array::<NODE_ID_LEN>()?);
        let recipient = if kind.has_recipient() {
            Some(NodeId::new(r.array::<NODE_ID_LEN>()?))
        } else {
            None
        };
        let created_at_ms = r.u64()?;
        let lifetime_secs = r.u32()?;
        let ext_len = usize::from(r.u8()?);
        if ext_len > MAX_EXT_LEN {
            return Err(ProtoError::TooLong {
                field: "ext",
                len: ext_len,
                max: MAX_EXT_LEN,
            });
        }
        let ext = r.take(ext_len)?.to_vec();
        let payload_len = usize::from(r.u16()?);
        if payload_len > MAX_PAYLOAD_LEN {
            return Err(ProtoError::TooLong {
                field: "payload",
                len: payload_len,
                max: MAX_PAYLOAD_LEN,
            });
        }
        let payload = r.take(payload_len)?.to_vec();
        let body = Self {
            kind,
            sender,
            recipient,
            created_at_ms,
            lifetime_secs,
            ext,
            payload,
        };
        body.validate()?;
        Ok(body)
    }
}

/// A signed envelope.
///
/// The signature is **not** checked here; use `crypto::verify_envelope`. A node
/// must neither store nor forward an envelope whose signature has not been verified.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Envelope {
    /// The signed body.
    pub body: EnvelopeBody,
    /// The Ed25519 signature.
    pub signature: [u8; SIGNATURE_LEN],
}

impl Envelope {
    /// Encodes the envelope canonically.
    pub fn encode(&self) -> Result<Vec<u8>> {
        let mut out = self.body.encode()?;
        out.extend_from_slice(&self.signature);
        Ok(out)
    }

    /// Strict parsing: truncated or trailing bytes, unknown version/kind, non-zero
    /// flags, size violations and kind/field mismatches all yield `Err`.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        let mut r = Reader::new(bytes);
        let env = Self::read_from(&mut r)?;
        r.finish()?;
        Ok(env)
    }

    pub(crate) fn read_from(r: &mut Reader<'_>) -> Result<Self> {
        let body = EnvelopeBody::read_from(r)?;
        let signature = r.array::<SIGNATURE_LEN>()?;
        Ok(Self { body, signature })
    }

    /// The message id (see [`EnvelopeBody::message_id`]).
    pub fn message_id(&self) -> Result<MessageId> {
        self.body.message_id()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn broadcast() -> Envelope {
        Envelope {
            body: EnvelopeBody {
                kind: Kind::Broadcast,
                sender: NodeId::new([1; 32]),
                recipient: None,
                created_at_ms: 1_700_000_000_000,
                lifetime_secs: 600,
                ext: vec![],
                payload: b"hello".to_vec(),
            },
            signature: [9; 64],
        }
    }

    #[test]
    fn roundtrip() {
        let e = broadcast();
        let bytes = e.encode().unwrap();
        assert_eq!(Envelope::decode(&bytes).unwrap(), e);
    }

    #[test]
    fn layout_is_stable() {
        let bytes = broadcast().encode().unwrap();
        assert_eq!(&bytes[..3], &[1, 1, 0]); // version, kind, flags
        assert_eq!(bytes.len(), 3 + 32 + 8 + 4 + 1 + 2 + 5 + 64);
    }

    #[test]
    fn rejects_trailing_bytes() {
        let mut bytes = broadcast().encode().unwrap();
        bytes.push(0);
        assert_eq!(Envelope::decode(&bytes), Err(ProtoError::TrailingBytes));
    }

    #[test]
    fn rejects_truncated_at_every_length() {
        let bytes = broadcast().encode().unwrap();
        for n in 0..bytes.len() {
            assert!(Envelope::decode(&bytes[..n]).is_err(), "n={n}");
        }
    }

    #[test]
    fn rejects_bad_version_kind_flags() {
        let good = broadcast().encode().unwrap();
        let mut b = good.clone();
        b[0] = 2;
        assert_eq!(Envelope::decode(&b), Err(ProtoError::UnsupportedVersion(2)));
        let mut b = good.clone();
        b[1] = 9;
        assert_eq!(Envelope::decode(&b), Err(ProtoError::UnknownKind(9)));
        let mut b = good;
        b[2] = 1;
        assert_eq!(Envelope::decode(&b), Err(ProtoError::NonZeroFlags(1)));
    }

    #[test]
    fn rejects_invalid_bodies() {
        let mut e = broadcast();
        e.body.recipient = Some(NodeId::new([2; 32]));
        assert!(e.encode().is_err());

        let mut e = broadcast();
        e.body.lifetime_secs = 0;
        assert_eq!(e.encode(), Err(ProtoError::InvalidLifetime));

        let mut e = broadcast();
        e.body.lifetime_secs = MAX_LIFETIME_SECS + 1;
        assert_eq!(e.encode(), Err(ProtoError::InvalidLifetime));

        let mut e = broadcast();
        e.body.payload = vec![0; MAX_PAYLOAD_LEN + 1];
        assert!(matches!(e.encode(), Err(ProtoError::TooLong { .. })));

        let mut e = broadcast();
        e.body.kind = Kind::Ack;
        e.body.recipient = Some(NodeId::new([2; 32]));
        assert!(e.encode().is_err()); // ack payload is not 32 bytes
    }

    #[test]
    fn message_id_ignores_signature_but_not_content() {
        let a = broadcast();
        let mut b = broadcast();
        b.signature = [7; 64];
        assert_eq!(a.message_id().unwrap(), b.message_id().unwrap());
        b.body.payload = b"hellp".to_vec();
        assert_ne!(a.message_id().unwrap(), b.message_id().unwrap());
    }
}
