//! Sealed box: tek aliciya, gonderen anahtari saklanmadan sifreleme.
//!
//! ```text
//! eph_secret  = rastgele 32 bayt (mesaj basina tekil)
//! eph_pub     = X25519(eph_secret, taban noktasi)
//! shared      = X25519(eph_secret, alici.exchange_key)       (sifirsa reddedilir)
//! okm[44]     = HKDF-SHA256(salt = eph_pub || alici.exchange_key,
//!                           ikm  = shared, info = "kriz/v1/seal")
//! key = okm[0..32], nonce = okm[32..44]
//! aad = gonderen.node_id || alici.node_id
//! cikti       = eph_pub || ChaCha20-Poly1305(key, nonce, aad, acik_metin)
//! ```
//!
//! Ek yuk [`proto::limits::SEAL_OVERHEAD`] = 48 bayttir. Gonderenin kimligi
//! sifreli kutunun icinde **kriptografik olarak** degil, zarfin Ed25519 imzasiyla
//! dogrulanir; `aad` yalnizca kutunun baska bir (gonderen, alici) cifti icin
//! yeniden paketlenmesini engeller.

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};
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

/// Isletim sistemi rastgeleligiyle sifreler.
pub fn seal(sender: &NodeId, recipient: &ContactCard, plaintext: &[u8]) -> Result<Vec<u8>> {
    let mut eph = Zeroizing::new([0u8; 32]);
    getrandom::fill(&mut eph[..]).map_err(|_| CryptoError::Rng)?;
    seal_with_ephemeral(sender, recipient, plaintext, &eph)
}

/// Gecici anahtari disaridan alir. **Yalnizca test vektorleri ve simulator icin**;
/// ayni `eph_secret`'i iki farkli mesajda kullanmak guvenligi bozar.
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

    let cipher = ChaCha20Poly1305::new(Key::from_slice(&okm[..32]));
    let ct = cipher
        .encrypt(
            Nonce::from_slice(&okm[32..]),
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

/// Sealed kutuyu acar. `sender`, zarfin (imzasi dogrulanmis) gonderenidir.
/// Her hata tek bir ayrintisiz [`CryptoError::Decrypt`]'e indirgenir.
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

    let cipher = ChaCha20Poly1305::new(Key::from_slice(&okm[..32]));
    let pt = cipher
        .decrypt(
            Nonce::from_slice(&okm[32..]),
            Payload {
                msg: &sealed[32..],
                aad: &aad,
            },
        )
        .map_err(|_| CryptoError::Decrypt)?;
    Ok(Zeroizing::new(pt))
}
