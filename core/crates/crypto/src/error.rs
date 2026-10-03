//! Error type.

use proto::ProtoError;

/// Crypto-layer errors. Decryption failures are intentionally detail-free.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum CryptoError {
    /// The key encoding or point is invalid.
    #[error("invalid key")]
    InvalidKey,
    /// The signature could not be verified.
    #[error("invalid signature")]
    BadSignature,
    /// Decryption failed (wrong key, corrupted data or context mismatch).
    #[error("decryption failed")]
    Decrypt,
    /// Encryption failed.
    #[error("encryption failed")]
    Encrypt,
    /// The X25519 result was all zeros (low-order point).
    #[error("invalid shared secret (low-order point)")]
    LowOrderPoint,
    /// Key derivation failed.
    #[error("key derivation failed")]
    Kdf,
    /// The operating system's randomness source failed.
    #[error("randomness source failed")]
    Rng,
    /// The contact card is invalid (length or signature).
    #[error("invalid contact card")]
    InvalidContactCard,
    /// The envelope does not belong to this identity or has the wrong kind.
    #[error("envelope is not addressed to this identity")]
    NotForMe,
    /// The body's `sender` field is not the signing identity.
    #[error("body belongs to a different sender")]
    SenderMismatch,
    /// Key store failure (platform message).
    #[error("key store: {0}")]
    KeyStore(String),
    /// Protocol (encoding/limit) error.
    #[error(transparent)]
    Proto(#[from] ProtoError),
}

/// Result type used throughout the crate.
pub type Result<T> = core::result::Result<T, CryptoError>;
