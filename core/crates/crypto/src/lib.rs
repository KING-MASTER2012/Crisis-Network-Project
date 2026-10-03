//! # crypto
//!
//! Identity, signatures and encryption. The primitives come from fixed, proven
//! libraries (Ed25519, X25519, HKDF-SHA256, ChaCha20-Poly1305); this crate only
//! combines them for the protocol. We do not invent our own primitives.
//!
//! * [`Identity`]: a node's secret identity (derived from a 32-byte seed).
//! * [`ContactCard`]: a shareable, self-signed identity card (meant for QR codes).
//! * [`seal`]: sealed-box encryption to a single recipient.
//! * [`verify_envelope`]: strict envelope signature verification.
//! * [`KeyStore`]: the interface through which the platform keeps the seed in its
//!   secure store (Keystore/Keychain). The core never writes the seed to disk itself.
//!
//! Design rationale: the "Identity and cryptography" ADR in the book. Known
//! limitations (no forward secrecy, ...): the "Threat model" chapter.
#![forbid(unsafe_code)]

mod error;
mod kdf;
mod x25519;

pub mod contact;
pub mod identity;
pub mod keystore;
pub mod seal;

pub use contact::{CONTACT_CARD_LEN, ContactCard};
pub use error::{CryptoError, Result};
pub use identity::{Identity, verify_envelope};
pub use keystore::{KeyStore, MemoryKeyStore, load_or_create};
