//! Dugumun gizli kimligi ve zarf imzalama/dogrulama.

use core::fmt;

use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use proto::{Envelope, EnvelopeBody, Kind, MessageId, NodeId};
use zeroize::Zeroizing;

use crate::contact::ContactCard;
use crate::error::{CryptoError, Result};
use crate::{kdf, seal, x25519};

/// Bir dugumun gizli kimligi.
///
/// 32 baytlik **tohum** her seyin kaynagidir: Ed25519 imza anahtari tohumun kendisi,
/// X25519 sifreleme anahtari ise tohumdan HKDF ile turetilir. Tohumu yedeklemek
/// kimligi geri getirir. `Debug` ciktisi gizli veri icermez; `Clone` bilerek yoktur.
pub struct Identity {
    seed: Zeroizing<[u8; 32]>,
    signing: SigningKey,
    exchange_secret: Zeroizing<[u8; 32]>,
}

impl fmt::Debug for Identity {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Identity")
            .field("node_id", &self.node_id())
            .finish_non_exhaustive()
    }
}

impl Identity {
    /// Isletim sistemi rastgeleligiyle yeni kimlik uretir.
    pub fn generate() -> Result<Self> {
        let mut seed = Zeroizing::new([0u8; 32]);
        getrandom::fill(&mut seed[..]).map_err(|_| CryptoError::Rng)?;
        Self::from_seed(&seed)
    }

    /// Tohumdan kimligi (yeniden) kurar. Ayni tohum her zaman ayni kimligi verir.
    pub fn from_seed(seed: &[u8; 32]) -> Result<Self> {
        Ok(Self {
            seed: Zeroizing::new(*seed),
            signing: SigningKey::from_bytes(seed),
            exchange_secret: kdf::derive_exchange_secret(seed)?,
        })
    }

    /// Yedeklenecek/`KeyStore`'a yazilacak tohum.
    pub fn seed(&self) -> &[u8; 32] {
        &self.seed
    }

    /// Bu kimligin agdaki adresi.
    pub fn node_id(&self) -> NodeId {
        NodeId::new(self.signing.verifying_key().to_bytes())
    }

    /// X25519 acik anahtari.
    pub fn exchange_public(&self) -> [u8; 32] {
        x25519::public_from_secret(&self.exchange_secret)
    }

    pub(crate) fn exchange_secret(&self) -> &[u8; 32] {
        &self.exchange_secret
    }

    /// Paylasilabilir, kendinden imzali kimlik karti.
    pub fn contact_card(&self) -> ContactCard {
        let node_id = self.node_id();
        let exchange_key = self.exchange_public();
        let sig = self
            .signing
            .sign(&ContactCard::signing_input(&node_id, &exchange_key));
        ContactCard::from_parts(node_id, exchange_key, sig.to_bytes())
    }

    /// Hazir bir govdeyi imzalar. `body.sender` bu kimlik olmalidir.
    pub fn sign_body(&self, body: EnvelopeBody) -> Result<Envelope> {
        if body.sender != self.node_id() {
            return Err(CryptoError::SenderMismatch);
        }
        let input = body.signing_input()?;
        let signature = self.signing.sign(&input).to_bytes();
        Ok(Envelope { body, signature })
    }

    /// Imzali, sifresiz duyuru olusturur.
    pub fn broadcast(
        &self,
        payload: &[u8],
        created_at_ms: u64,
        lifetime_secs: u32,
    ) -> Result<Envelope> {
        self.sign_body(EnvelopeBody {
            kind: Kind::Broadcast,
            sender: self.node_id(),
            recipient: None,
            created_at_ms,
            lifetime_secs,
            ext: Vec::new(),
            payload: payload.to_vec(),
        })
    }

    /// `to`'ya sifreli (sealed box) ve imzali ozel mesaj olusturur.
    pub fn direct(
        &self,
        to: &ContactCard,
        plaintext: &[u8],
        created_at_ms: u64,
        lifetime_secs: u32,
    ) -> Result<Envelope> {
        let payload = seal::seal(&self.node_id(), to, plaintext)?;
        self.sign_body(EnvelopeBody {
            kind: Kind::Direct,
            sender: self.node_id(),
            recipient: Some(to.node_id()),
            created_at_ms,
            lifetime_secs,
            ext: Vec::new(),
            payload,
        })
    }

    /// `acked` mesajinin ulastigini `to`'ya bildiren imzali Ack olusturur.
    pub fn ack(
        &self,
        to: &NodeId,
        acked: &MessageId,
        created_at_ms: u64,
        lifetime_secs: u32,
    ) -> Result<Envelope> {
        self.sign_body(EnvelopeBody {
            kind: Kind::Ack,
            sender: self.node_id(),
            recipient: Some(*to),
            created_at_ms,
            lifetime_secs,
            ext: Vec::new(),
            payload: acked.as_bytes().to_vec(),
        })
    }

    /// Bu kimlige gelen `Direct` zarfi dogrular ve acar. Once imza, sonra sifre.
    pub fn open_direct(&self, env: &Envelope) -> Result<Zeroizing<Vec<u8>>> {
        verify_envelope(env)?;
        if env.body.kind != Kind::Direct || env.body.recipient != Some(self.node_id()) {
            return Err(CryptoError::NotForMe);
        }
        seal::open(self, &env.body.sender, &env.body.payload)
    }
}

/// Zarf imzasini **siki** (`verify_strict`) dogrular. Bir dugum, dogrulamadan
/// gecmeyen zarfi saklamamali ve iletmemelidir.
pub fn verify_envelope(env: &Envelope) -> Result<()> {
    let input = env.body.signing_input()?;
    let vk = VerifyingKey::from_bytes(env.body.sender.as_bytes())
        .map_err(|_| CryptoError::InvalidKey)?;
    let sig = Signature::from_bytes(&env.signature);
    vk.verify_strict(&input, &sig)
        .map_err(|_| CryptoError::BadSignature)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn h32(s: &str) -> [u8; 32] {
        let mut out = [0u8; 32];
        for (i, b) in out.iter_mut().enumerate() {
            *b = u8::from_str_radix(&s[2 * i..2 * i + 2], 16).unwrap();
        }
        out
    }

    #[test]
    fn rfc8032_test1_public_key() {
        let id = Identity::from_seed(&h32(
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
        ))
        .unwrap();
        assert_eq!(
            *id.node_id().as_bytes(),
            h32("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        );
    }

    #[test]
    fn same_seed_same_identity_and_debug_hides_secrets() {
        let a = Identity::from_seed(&[1; 32]).unwrap();
        let b = Identity::from_seed(&[1; 32]).unwrap();
        assert_eq!(a.node_id(), b.node_id());
        assert_eq!(a.exchange_public(), b.exchange_public());
        let dbg = format!("{a:?}");
        assert!(!dbg.contains("seed") && !dbg.contains("secret"));
    }

    #[test]
    fn sign_body_rejects_foreign_sender() {
        let a = Identity::from_seed(&[1; 32]).unwrap();
        let b = Identity::from_seed(&[2; 32]).unwrap();
        let body = EnvelopeBody {
            kind: Kind::Broadcast,
            sender: b.node_id(),
            recipient: None,
            created_at_ms: 1,
            lifetime_secs: 10,
            ext: vec![],
            payload: vec![],
        };
        assert_eq!(a.sign_body(body).unwrap_err(), CryptoError::SenderMismatch);
    }

    #[test]
    fn contact_card_roundtrip_and_tamper() {
        let a = Identity::from_seed(&[1; 32]).unwrap();
        let card = a.contact_card();
        let bytes = card.to_bytes();
        assert_eq!(ContactCard::from_bytes(&bytes).unwrap(), card);
        assert_eq!(card.safety_number().len(), 39);
        for i in 0..bytes.len() {
            let mut bad = bytes;
            bad[i] ^= 1;
            assert!(ContactCard::from_bytes(&bad).is_err(), "bayt {i}");
        }
        assert!(ContactCard::from_bytes(&bytes[..127]).is_err());
    }
}
