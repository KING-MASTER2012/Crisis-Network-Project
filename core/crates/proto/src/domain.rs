//! Domain-separation tags.
//!
//! Every signature, hash and KDF output is produced under its own tag, so a value
//! produced in one context can never be mistaken for one from another context.
//! The tags are fixed and change together with the protocol version (`v1`).

/// Input of an envelope signature: `DOMAIN_ENVELOPE_SIG || signed_body`.
pub const DOMAIN_ENVELOPE_SIG: &[u8] = b"crisis/v1/envelope\0";

/// Message identifier: `SHA-256(DOMAIN_MSG_ID || signed_body)`.
pub const DOMAIN_MSG_ID: &[u8] = b"crisis/v1/msgid\0";
