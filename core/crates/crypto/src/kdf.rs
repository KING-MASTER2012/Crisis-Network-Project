//! Dahili: etiketler ve anahtar turetme.

use hkdf::Hkdf;
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

use crate::error::{CryptoError, Result};

pub(crate) const IDENTITY_SALT: &[u8] = b"kriz/v1/identity";
pub(crate) const INFO_EXCHANGE_KEY: &[u8] = b"kriz/v1/exchange-key";
pub(crate) const INFO_SEAL: &[u8] = b"kriz/v1/seal";
pub(crate) const DOMAIN_CARD: &[u8] = b"kriz/v1/contact-card\0";
pub(crate) const DOMAIN_FINGERPRINT: &[u8] = b"kriz/v1/fingerprint\0";

/// Kimlik tohumundan X25519 gizli anahtarini turetir (imza anahtariyla ayni
/// tohumu paylasir ama ayri bir etiketle ayrilir).
pub(crate) fn derive_exchange_secret(seed: &[u8; 32]) -> Result<Zeroizing<[u8; 32]>> {
    let hk = Hkdf::<Sha256>::new(Some(IDENTITY_SALT), seed);
    let mut out = Zeroizing::new([0u8; 32]);
    hk.expand(INFO_EXCHANGE_KEY, &mut out[..])
        .map_err(|_| CryptoError::Kdf)?;
    Ok(out)
}

/// Sealed-box: 32 bayt anahtar + 12 bayt nonce (toplam 44) turetir.
/// Gecici anahtar her mesajda tekil oldugundan nonce'un turetilmesi guvenlidir.
pub(crate) fn derive_seal_material(
    shared: &[u8; 32],
    eph_pub: &[u8; 32],
    recipient_exchange_pub: &[u8; 32],
) -> Result<Zeroizing<[u8; 44]>> {
    let mut salt = [0u8; 64];
    salt[..32].copy_from_slice(eph_pub);
    salt[32..].copy_from_slice(recipient_exchange_pub);
    let hk = Hkdf::<Sha256>::new(Some(&salt), shared);
    let mut okm = Zeroizing::new([0u8; 44]);
    hk.expand(INFO_SEAL, &mut okm[..])
        .map_err(|_| CryptoError::Kdf)?;
    Ok(okm)
}

/// `SHA-256(parca1 || parca2 || ...)`.
pub(crate) fn sha256(parts: &[&[u8]]) -> [u8; 32] {
    let mut h = Sha256::new();
    for p in parts {
        h.update(p);
    }
    h.finalize().into()
}
