//! Contact card: a shareable, self-signed piece of public identity information.
//!
//! ```text
//! node_id       [32]  Ed25519 public key (signature verification + address)
//! exchange_key  [32]  X25519 public key (encryption)
//! signature     [64]  by the node_id key over: DOMAIN_CARD || node_id || exchange_key
//! ```
//!
//! The self-signature proves possession: nobody can bind their own `exchange_key`
//! to your `node_id`. It does **not** prove that the card really belongs to a given
//! person; for that, [`ContactCard::safety_number`] must be compared in person
//! (QR code or reading it aloud).

use ed25519_dalek::{Signature, VerifyingKey};
use proto::NodeId;

use crate::error::{CryptoError, Result};
use crate::kdf::{DOMAIN_CARD, DOMAIN_FINGERPRINT, sha256};

/// Length of an encoded contact card.
pub const CONTACT_CARD_LEN: usize = 32 + 32 + 64;

/// A self-signed identity card.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ContactCard {
    node_id: NodeId,
    exchange_key: [u8; 32],
    signature: [u8; 64],
}

impl ContactCard {
    /// Builds a card without verifying it (only for `Identity`).
    pub(crate) fn from_parts(node_id: NodeId, exchange_key: [u8; 32], signature: [u8; 64]) -> Self {
        Self {
            node_id,
            exchange_key,
            signature,
        }
    }

    /// The signed input.
    pub(crate) fn signing_input(node_id: &NodeId, exchange_key: &[u8; 32]) -> Vec<u8> {
        let mut v = Vec::with_capacity(DOMAIN_CARD.len() + 64);
        v.extend_from_slice(DOMAIN_CARD);
        v.extend_from_slice(node_id.as_bytes());
        v.extend_from_slice(exchange_key);
        v
    }

    /// The card's owner (its address).
    pub fn node_id(&self) -> NodeId {
        self.node_id
    }

    /// The X25519 public key used for encryption.
    pub fn exchange_key(&self) -> &[u8; 32] {
        &self.exchange_key
    }

    /// Verifies the self-signature.
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

    /// The canonical 128-byte encoding.
    pub fn to_bytes(&self) -> [u8; CONTACT_CARD_LEN] {
        let mut out = [0u8; CONTACT_CARD_LEN];
        out[..32].copy_from_slice(self.node_id.as_bytes());
        out[32..64].copy_from_slice(&self.exchange_key);
        out[64..].copy_from_slice(&self.signature);
        out
    }

    /// Strict parsing **and** signature verification.
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

    /// A short form people can compare: the first 16 bytes of the fingerprint as
    /// 4-character hex groups (for example `A1B2 C3D4 ...`, 8 groups).
    pub fn safety_number(&self) -> String {
        let fp = self.fingerprint();
        fp[..16]
            .chunks(2)
            .map(|c| format!("{:02X}{:02X}", c[0], c[1]))
            .collect::<Vec<_>>()
            .join(" ")
    }
}
