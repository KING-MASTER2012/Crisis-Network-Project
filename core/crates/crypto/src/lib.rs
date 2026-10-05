//! # crypto
//!
//! Identity, signatures and encryption. The primitives come from fixed, proven
//! libraries (Ed25519, X25519, HKDF-SHA256, ChaCha20-Poly1305); this crate only
//! combines them for the protocol. We do not invent our own primitives.
//!
//! * The [`Identity`] is a node's secret identity (derived from a 32-byte seed).
//! * A [`ContactCard`] is a shareable, self-signed identity card (meant for QR codes).
//! * The [`seal`] module does sealed-box encryption to a single recipient.
//! * [`verify_envelope`] checks an envelope signature strictly.
//! * The [`KeyStore`] interface lets the platform keep the seed in its secure store
//!   (Keystore/Keychain); the core never writes the seed to disk itself.
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
