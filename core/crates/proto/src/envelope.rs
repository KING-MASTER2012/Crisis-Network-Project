//! Imzali zarf: aga cikan kalici mesaj.
//!
//! Kodlanmis bicim (tum tamsayilar big-endian):
//!
//! ```text
//! version        u8      = 1
//! kind           u8      1=Broadcast 2=Direct 3=Ack
//! flags          u8      v1'de 0 olmali
//! sender         [32]    gonderenin Ed25519 acik anahtari
//! recipient      [32]    yalnizca Direct ve Ack'te
//! created_at_ms  u64     Unix epoch'tan beri ms (gonderenin saati, GUVENILMEZ)
//! lifetime_secs  u32     1..=MAX_LIFETIME_SECS
//! ext_len        u8      0..=MAX_EXT_LEN
//! ext            [ext_len]
//! payload_len    u16     0..=MAX_PAYLOAD_LEN
//! payload        [payload_len]
//! signature      [64]    Ed25519 (DOMAIN_ENVELOPE_SIG || yukaridaki alanlar)
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

/// Mesaj turu.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum Kind {
    /// Herkese acik, imzali ama sifresiz duyuru.
    Broadcast = 1,
    /// Tek aliciya sifreli mesaj (sealed box).
    Direct = 2,
    /// Bir mesajin ulastiginin onayi; payload = onaylanan [`MessageId`].
    Ack = 3,
}

impl Kind {
    /// Tel degerinden tur'e.
    pub fn from_u8(v: u8) -> Result<Self> {
        match v {
            1 => Ok(Kind::Broadcast),
            2 => Ok(Kind::Direct),
            3 => Ok(Kind::Ack),
            other => Err(ProtoError::UnknownKind(other)),
        }
    }

    /// Bu turde `recipient` alani var mi?
    pub fn has_recipient(self) -> bool {
        !matches!(self, Kind::Broadcast)
    }
}

/// Zarfin imzalanan kismi.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EnvelopeBody {
    /// Mesaj turu.
    pub kind: Kind,
    /// Gonderen.
    pub sender: NodeId,
    /// Alici (Direct/Ack'te zorunlu, Broadcast'ta yasak).
    pub recipient: Option<NodeId>,
    /// Gonderenin olusturma zamani (ms, Unix epoch). Guvenilmez saat: yalnizca
    /// bilgi ve omur hesabi icindir.
    pub created_at_ms: u64,
    /// Mesajin omru (saniye); bitince dugumler mesaji silebilir.
    pub lifetime_secs: u32,
    /// Gelecek surumler icin imzali, opak alan. v1 anlamlandirmaz ama korur.
    pub ext: Vec<u8>,
    /// Govde. Direct'te sealed-box ciktisi, Ack'te onaylanan mesaj kimligi.
    pub payload: Vec<u8>,
}

impl EnvelopeBody {
    /// Alanlarin tur ve sinir kurallarina uydugunu denetler.
    pub fn validate(&self) -> Result<()> {
        match (self.kind.has_recipient(), self.recipient.is_some()) {
            (true, false) => return Err(ProtoError::InvalidField("recipient zorunlu")),
            (false, true) => {
                return Err(ProtoError::InvalidField("broadcast'ta recipient olamaz"));
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
                "direct payload sealed-box ek yukunden kisa",
            )),
            Kind::Ack if self.payload.len() != ACK_PAYLOAD_LEN => {
                Err(ProtoError::InvalidField("ack payload 32 bayt olmali"))
            }
            _ => Ok(()),
        }
    }

    /// Govdeyi kanonik baytlara cevirir (imza ve kimlik bunun uzerinden hesaplanir).
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
        out.push(self.ext.len() as u8); // validate(): <= MAX_EXT_LEN
        out.extend_from_slice(&self.ext);
        out.extend_from_slice(&(self.payload.len() as u16).to_be_bytes()); // <= 1024
        out.extend_from_slice(&self.payload);
        Ok(out)
    }

    /// Imzanin girdisi: `DOMAIN_ENVELOPE_SIG || encode()`.
    pub fn signing_input(&self) -> Result<Vec<u8>> {
        let body = self.encode()?;
        let mut v = Vec::with_capacity(DOMAIN_ENVELOPE_SIG.len() + body.len());
        v.extend_from_slice(DOMAIN_ENVELOPE_SIG);
        v.extend_from_slice(&body);
        Ok(v)
    }

    /// Mesaj kimligi: `SHA-256(DOMAIN_MSG_ID || encode())`.
    pub fn message_id(&self) -> Result<MessageId> {
        let body = self.encode()?;
        let mut h = Sha256::new();
        h.update(DOMAIN_MSG_ID);
        h.update(&body);
        let digest: [u8; 32] = h.finalize().into();
        Ok(MessageId::new(digest))
    }

    /// Mesajin dusecegi an (ms). Tasmada `u64::MAX`'te doyar.
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

/// Imzali zarf.
///
/// Imzanin gecerliligi **burada denetlenmez**; `kriz_crypto::verify_envelope`
/// kullanilmalidir. Bir dugum imzasi dogrulanmamis zarfi saklamamali ve
/// iletmemelidir.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Envelope {
    /// Imzalanan govde.
    pub body: EnvelopeBody,
    /// Ed25519 imzasi.
    pub signature: [u8; SIGNATURE_LEN],
}

impl Envelope {
    /// Kanonik baytlara cevirir.
    pub fn encode(&self) -> Result<Vec<u8>> {
        let mut out = self.body.encode()?;
        out.extend_from_slice(&self.signature);
        Ok(out)
    }

    /// Siki ayristirma: eksik, fazla, bilinmeyen surum/tur, sifir olmayan flags,
    /// sinir asimi ve tur-alan uyumsuzlugu `Err` dondurur.
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

    /// Mesaj kimligi (bkz. [`EnvelopeBody::message_id`]).
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
        assert!(e.encode().is_err()); // ack payload != 32 bayt
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
