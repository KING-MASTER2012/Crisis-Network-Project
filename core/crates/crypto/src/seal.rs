//! Sealed box: encryption to a single recipient without storing the sender's key.
//!
//! ```text
//! eph_secret  = 32 random bytes (unique per message)
//! eph_pub     = X25519(eph_secret, base point)
//! shared      = X25519(eph_secret, recipient.exchange_key)      (rejected if all zeros)
//! okm[44]     = HKDF-SHA256(salt = eph_pub || recipient.exchange_key,
//!                           ikm  = shared, info = "crisis/v1/seal")
//! key = okm[0..32], nonce = okm[32..44]
//! aad = sender.node_id || recipient.node_id
//! output      = eph_pub || ChaCha20-Poly1305(key, nonce, aad, plaintext)
//! ```
//!
//! The overhead is [`proto::limits::SEAL_OVERHEAD`] = 48 bytes. The sender's
//! identity is authenticated not *inside* the box but by the envelope's Ed25519
//! signature; `aad` merely prevents the box from being repackaged for a different
//! (sender, recipient) pair.

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{ChaCha20Poly1305, Nonce};
use proto::limits::{MAX_PLAINTEXT_LEN, SEAL_OVERHEAD};
use proto::{NodeId, ProtoError};
use zeroize::Zeroizing;

use crate::contact::ContactCard;
use crate::error::{CryptoError, Result};
use crate::identity::Identity;
use crate::{kdf, x25519};

fn aad(sender: &NodeId, recipient: &NodeId) -> [u8; 64] {
    let mut a = [0u8; 64];
    a[..32].copy_from_slice(sender.as_bytes());
    a[32..].copy_from_slice(recipient.as_bytes());
    a
}

/// Encrypts using the operating system's randomness.
pub fn seal(sender: &NodeId, recipient: &ContactCard, plaintext: &[u8]) -> Result<Vec<u8>> {
    let mut eph = Zeroizing::new([0u8; 32]);
    getrandom::fill(&mut eph[..]).map_err(|_| CryptoError::Rng)?;
    seal_with_ephemeral(sender, recipient, plaintext, &eph)
}

/// Takes the ephemeral key from the caller. **For test vectors and the simulator
/// only**; reusing one `eph_secret` for two different messages breaks security.
pub fn seal_with_ephemeral(
    sender: &NodeId,
    recipient: &ContactCard,
    plaintext: &[u8],
    eph_secret: &[u8; 32],
) -> Result<Vec<u8>> {
    recipient.verify()?;
    if plaintext.len() > MAX_PLAINTEXT_LEN {
        return Err(ProtoError::TooLong {
            field: "plaintext",
            len: plaintext.len(),
            max: MAX_PLAINTEXT_LEN,
        }
        .into());
    }
    let eph_pub = x25519::public_from_secret(eph_secret);
    let shared = x25519::diffie_hellman(eph_secret, recipient.exchange_key())?;
    let okm = kdf::derive_seal_material(&shared, &eph_pub, recipient.exchange_key())?;
    let aad = aad(sender, &recipient.node_id());

    let cipher = ChaCha20Poly1305::new_from_slice(&okm[..32]).map_err(|_| CryptoError::Encrypt)?;
    let nonce_bytes: [u8; 12] = okm[32..].try_into().map_err(|_| CryptoError::Encrypt)?;
    let ct = cipher
        .encrypt(
            &Nonce::from(nonce_bytes),
            Payload {
                msg: plaintext,
                aad: &aad,
            },
        )
        .map_err(|_| CryptoError::Encrypt)?;

    let mut out = Vec::with_capacity(32 + ct.len());
    out.extend_from_slice(&eph_pub);
    out.extend_from_slice(&ct);
    Ok(out)
}

/// Opens a sealed box. `sender` is the (signature-verified) sender of the
/// envelope. Every failure is collapsed into one detail-free [`CryptoError::Decrypt`].
pub fn open(recipient: &Identity, sender: &NodeId, sealed: &[u8]) -> Result<Zeroizing<Vec<u8>>> {
    if sealed.len() < SEAL_OVERHEAD {
        return Err(CryptoError::Decrypt);
    }
    let mut eph_pub = [0u8; 32];
    eph_pub.copy_from_slice(&sealed[..32]);
    let shared = x25519::diffie_hellman(recipient.exchange_secret(), &eph_pub)
        .map_err(|_| CryptoError::Decrypt)?;
    let my_exchange_pub = recipient.exchange_public();
    let okm = kdf::derive_seal_material(&shared, &eph_pub, &my_exchange_pub)
        .map_err(|_| CryptoError::Decrypt)?;
    let aad = aad(sender, &recipient.node_id());

    let cipher = ChaCha20Poly1305::new_from_slice(&okm[..32]).map_err(|_| CryptoError::Decrypt)?;
    let nonce_bytes: [u8; 12] = okm[32..].try_into().map_err(|_| CryptoError::Decrypt)?;
    let pt = cipher
        .decrypt(
            &Nonce::from(nonce_bytes),
            Payload {
                msg: &sealed[32..],
                aad: &aad,
            },
        )
        .map_err(|_| CryptoError::Decrypt)?;
    Ok(Zeroizing::new(pt))
}
