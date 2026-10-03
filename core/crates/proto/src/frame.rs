//! Hop cercevesi: zarfin ag uzerinde tasinan hali.
//!
//! ```text
//! hops_left  u8   0..=MAX_HOPS   (IMZA DISINDA: her sicramada azalir)
//! envelope   ...  bkz. envelope.rs
//! ```
//!
//! `hops_left` bilerek imzanin disindadir; aksi halde her sicramada imza
//! bozulurdu. Sonucu: aradaki bir dugum `hops_left`'i artirabilir. Bu bir
//! hizmet-aksatma (flood) riskidir, butunluk riski degil; bkz. `THREAT_MODEL.md`.

use crate::codec::Reader;
use crate::envelope::Envelope;
use crate::error::{ProtoError, Result};
use crate::limits::MAX_HOPS;

/// Tasima cercevesi.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Frame {
    /// Kalan sicrama hakki.
    pub hops_left: u8,
    /// Tasinan zarf.
    pub envelope: Envelope,
}

impl Frame {
    /// Kanonik baytlara cevirir.
    pub fn encode(&self) -> Result<Vec<u8>> {
        if self.hops_left > MAX_HOPS {
            return Err(ProtoError::InvalidHops(self.hops_left));
        }
        let env = self.envelope.encode()?;
        let mut out = Vec::with_capacity(1 + env.len());
        out.push(self.hops_left);
        out.extend_from_slice(&env);
        Ok(out)
    }

    /// Siki ayristirma.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        let mut r = Reader::new(bytes);
        let hops_left = r.u8()?;
        if hops_left > MAX_HOPS {
            return Err(ProtoError::InvalidHops(hops_left));
        }
        let envelope = Envelope::read_from(&mut r)?;
        r.finish()?;
        Ok(Self {
            hops_left,
            envelope,
        })
    }

    /// Bir sonraki dugume iletilecek kopya: `hops_left` bir azalir.
    /// `hops_left == 0` ise `None` (artik iletilmez).
    pub fn forwarded(&self) -> Option<Self> {
        let hops_left = self.hops_left.checked_sub(1)?;
        Some(Self {
            hops_left,
            envelope: self.envelope.clone(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::envelope::{EnvelopeBody, Kind};
    use crate::id::NodeId;

    fn frame(hops: u8) -> Frame {
        Frame {
            hops_left: hops,
            envelope: Envelope {
                body: EnvelopeBody {
                    kind: Kind::Broadcast,
                    sender: NodeId::new([1; 32]),
                    recipient: None,
                    created_at_ms: 1,
                    lifetime_secs: 60,
                    ext: vec![],
                    payload: vec![1, 2, 3],
                },
                signature: [0; 64],
            },
        }
    }

    #[test]
    fn roundtrip_and_hops_limit() {
        let f = frame(5);
        assert_eq!(Frame::decode(&f.encode().unwrap()).unwrap(), f);
        assert_eq!(
            frame(MAX_HOPS + 1).encode(),
            Err(ProtoError::InvalidHops(MAX_HOPS + 1))
        );
        let mut b = frame(5).encode().unwrap();
        b[0] = MAX_HOPS + 1;
        assert_eq!(
            Frame::decode(&b),
            Err(ProtoError::InvalidHops(MAX_HOPS + 1))
        );
    }

    #[test]
    fn forwarding_decrements_and_stops_at_zero() {
        assert_eq!(frame(2).forwarded().unwrap().hops_left, 1);
        assert!(frame(0).forwarded().is_none());
    }

    #[test]
    fn message_id_is_unaffected_by_hops() {
        let a = frame(7);
        let b = a.forwarded().unwrap();
        assert_eq!(
            a.envelope.message_id().unwrap(),
            b.envelope.message_id().unwrap()
        );
    }
}
