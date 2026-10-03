//! Internal: a thin wrapper around X25519. All `x25519-dalek` usage is gathered
//! here, so if the library API changes only this file needs updating.

use x25519_dalek::{X25519_BASEPOINT_BYTES, x25519};
use zeroize::Zeroizing;

use crate::error::{CryptoError, Result};

/// `secret * G` (the public key).
pub(crate) fn public_from_secret(secret: &[u8; 32]) -> [u8; 32] {
    x25519(*secret, X25519_BASEPOINT_BYTES)
}

/// Diffie-Hellman. Rejects an all-zero result (low-order point).
pub(crate) fn diffie_hellman(secret: &[u8; 32], public: &[u8; 32]) -> Result<Zeroizing<[u8; 32]>> {
    let out = Zeroizing::new(x25519(*secret, *public));
    if out.iter().fold(0u8, |acc, b| acc | b) == 0 {
        return Err(CryptoError::LowOrderPoint);
    }
    Ok(out)
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

    // RFC 7748, section 6.1
    const A_SK: &str = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a";
    const A_PK: &str = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a";
    const B_SK: &str = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb";
    const B_PK: &str = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f";
    const SHARED: &str = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742";

    #[test]
    fn rfc7748_vectors() {
        assert_eq!(public_from_secret(&h32(A_SK)), h32(A_PK));
        assert_eq!(public_from_secret(&h32(B_SK)), h32(B_PK));
        assert_eq!(
            *diffie_hellman(&h32(A_SK), &h32(B_PK)).unwrap(),
            h32(SHARED)
        );
        assert_eq!(
            *diffie_hellman(&h32(B_SK), &h32(A_PK)).unwrap(),
            h32(SHARED)
        );
    }

    #[test]
    fn low_order_point_is_rejected() {
        // The u = 0 point yields an all-zero result.
        assert_eq!(
            diffie_hellman(&h32(A_SK), &[0u8; 32]).unwrap_err(),
            CryptoError::LowOrderPoint
        );
    }
}
