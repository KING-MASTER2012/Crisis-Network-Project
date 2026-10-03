//! Protocol constants and upper bounds. All of them mirror the specification exactly.

/// The only protocol version this crate produces and accepts.
pub const PROTOCOL_VERSION: u8 = 1;

/// Length of an Ed25519 signature in bytes.
pub const SIGNATURE_LEN: usize = 64;
/// Length of a [`crate::NodeId`] (an Ed25519 public key).
pub const NODE_ID_LEN: usize = 32;
/// Length of a [`crate::MessageId`] (a SHA-256 digest).
pub const MESSAGE_ID_LEN: usize = 32;

/// Maximum length of the `ext` field.
pub const MAX_EXT_LEN: usize = 64;
/// Maximum length of the `payload` field (including the sealed box for Direct messages).
pub const MAX_PAYLOAD_LEN: usize = 1024;
/// Maximum message lifetime: 7 days, in seconds.
pub const MAX_LIFETIME_SECS: u32 = 7 * 24 * 60 * 60;
/// Maximum `hops_left` a frame may carry.
pub const MAX_HOPS: u8 = 16;

/// Sealed-box overhead: ephemeral X25519 key (32) + Poly1305 tag (16).
pub const SEAL_OVERHEAD: usize = 32 + 16;
/// Maximum plaintext of a Direct message.
pub const MAX_PLAINTEXT_LEN: usize = MAX_PAYLOAD_LEN - SEAL_OVERHEAD;
/// The payload of an Ack is exactly the id of the acknowledged message.
pub const ACK_PAYLOAD_LEN: usize = MESSAGE_ID_LEN;

/// Maximum length of one encoded envelope.
pub const MAX_ENVELOPE_LEN: usize =
    3 + NODE_ID_LEN + NODE_ID_LEN + 8 + 4 + 1 + MAX_EXT_LEN + 2 + MAX_PAYLOAD_LEN + SIGNATURE_LEN;
/// Maximum length of one encoded frame.
pub const MAX_FRAME_LEN: usize = 1 + MAX_ENVELOPE_LEN;

/// Fragment header: transfer_id (8) + index (1) + total (1).
pub const FRAGMENT_HEADER_LEN: usize = 10;
/// Maximum number of fragments one message can be split into (`total` is a `u8`).
pub const MAX_FRAGMENTS: usize = 255;
