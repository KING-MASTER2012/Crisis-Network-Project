//! Kisi karti: bir kimligin paylasilabilir, kendinden imzali acik bilgisi.
//!
//! ```text
//! node_id       [32]  Ed25519 acik anahtari (imza dogrulama + adres)
//! exchange_key  [32]  X25519 acik anahtari (sifreleme)
//! signature     [64]  node_id anahtariyla imza: DOMAIN_CARD || node_id || exchange_key
//! ```
//!
//! Kendinden imza, baska birinin `exchange_key`'ini senin `node_id`'ne baglayamamasini
//! saglar (sahip olma kaniti). **Kartin gercekten o kisiye ait oldugunu** kanitlamaz;
//! bunun icin [`ContactCard::safety_number`] yuz yuze (QR/sesli) karsilastirilmalidir.

use ed25519_dalek::{Signature, VerifyingKey};
use proto::NodeId;

use crate::error::{CryptoError, Result};
use crate::kdf::{DOMAIN_CARD, DOMAIN_FINGERPRINT, sha256};

/// Kodlanmis kisi karti uzunlugu.
pub const CONTACT_CARD_LEN: usize = 32 + 32 + 64;

/// Kendinden imzali kimlik karti.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ContactCard {
    node_id: NodeId,
    exchange_key: [u8; 32],
    signature: [u8; 64],
}

impl ContactCard {
    /// Dogrulama yapmadan olusturur (yalnizca `Identity` icin).
    pub(crate) fn from_parts(node_id: NodeId, exchange_key: [u8; 32], signature: [u8; 64]) -> Self {
        Self {
            node_id,
            exchange_key,
            signature,
        }
    }

    /// Imzalanan girdi.
    pub(crate) fn signing_input(node_id: &NodeId, exchange_key: &[u8; 32]) -> Vec<u8> {
        let mut v = Vec::with_capacity(DOMAIN_CARD.len() + 64);
        v.extend_from_slice(DOMAIN_CARD);
        v.extend_from_slice(node_id.as_bytes());
        v.extend_from_slice(exchange_key);
        v
    }

    /// Kartin sahibi (adres).
    pub fn node_id(&self) -> NodeId {
        self.node_id
    }

    /// Sifreleme (X25519) acik anahtari.
    pub fn exchange_key(&self) -> &[u8; 32] {
        &self.exchange_key
    }

    /// Kendinden imzayi denetler.
    pub fn verify(&self) -> Result<()> {
        let vk = VerifyingKey::from_bytes(self.node_id.as_bytes())
            .map_err(|_| CryptoError::InvalidContactCard)?;
        let sig = Signature::from_bytes(&self.signature);
        vk.verify_strict(
            &Self::signing_input(&self.node_id, &self.exchange_key),
            &sig,
        )
        .map_err(|_| CryptoError::InvalidContactCard)
    }

    /// 128 baytlik kanonik bicim.
    pub fn to_bytes(&self) -> [u8; CONTACT_CARD_LEN] {
        let mut out = [0u8; CONTACT_CARD_LEN];
        out[..32].copy_from_slice(self.node_id.as_bytes());
        out[32..64].copy_from_slice(&self.exchange_key);
        out[64..].copy_from_slice(&self.signature);
        out
    }

    /// Siki ayristirma **ve** imza dogrulamasi.
    pub fn from_bytes(bytes: &[u8]) -> Result<Self> {
        if bytes.len() != CONTACT_CARD_LEN {
            return Err(CryptoError::InvalidContactCard);
        }
        let mut node = [0u8; 32];
        let mut exchange_key = [0u8; 32];
        let mut signature = [0u8; 64];
        node.copy_from_slice(&bytes[..32]);
        exchange_key.copy_from_slice(&bytes[32..64]);
        signature.copy_from_slice(&bytes[64..]);
        let card = Self::from_parts(NodeId::new(node), exchange_key, signature);
        card.verify()?;
        Ok(card)
    }

    /// `SHA-256(DOMAIN_FINGERPRINT || node_id || exchange_key)`.
    pub fn fingerprint(&self) -> [u8; 32] {
        sha256(&[
            DOMAIN_FINGERPRINT,
            self.node_id.as_bytes(),
            &self.exchange_key,
        ])
    }

    /// Insanin karsilastirabilecegi kisa gosterim: parmak izinin ilk 16 bayti,
    /// 4'lu hex gruplari (ornek: `A1B2 C3D4 ...`, 8 grup).
    pub fn safety_number(&self) -> String {
        let fp = self.fingerprint();
        fp[..16]
            .chunks(2)
            .map(|c| format!("{:02X}{:02X}", c[0], c[1]))
            .collect::<Vec<_>>()
            .join(" ")
    }
}
