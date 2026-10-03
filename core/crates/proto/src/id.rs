//! Node and message identifiers.

use core::fmt;

use crate::limits::{MESSAGE_ID_LEN, NODE_ID_LEN};

fn write_hex(bytes: &[u8], f: &mut fmt::Formatter<'_>) -> fmt::Result {
    for b in bytes {
        write!(f, "{b:02x}")?;
    }
    Ok(())
}

/// A node's identity: its Ed25519 public (signing) key.
#[derive(Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct NodeId([u8; NODE_ID_LEN]);

impl NodeId {
    /// Wraps raw bytes. Whether they form a valid Ed25519 point is not checked
    /// here; signature verification does that.
    pub const fn new(bytes: [u8; NODE_ID_LEN]) -> Self {
        Self(bytes)
    }

    /// The raw bytes.
    pub const fn as_bytes(&self) -> &[u8; NODE_ID_LEN] {
        &self.0
    }
}

impl From<[u8; NODE_ID_LEN]> for NodeId {
    fn from(b: [u8; NODE_ID_LEN]) -> Self {
        Self(b)
    }
}

impl fmt::Display for NodeId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write_hex(&self.0, f)
    }
}

impl fmt::Debug for NodeId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "NodeId(")?;
        write_hex(&self.0[..4], f)?;
        write!(f, "..)")
    }
}

/// Message identifier: `SHA-256(DOMAIN_MSG_ID || signed_body)`.
///
/// The same message has the same identifier no matter how many paths it arrives
/// by; deduplication relies on this value.
#[derive(Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct MessageId([u8; MESSAGE_ID_LEN]);

impl MessageId {
    /// Wraps raw bytes.
    pub const fn new(bytes: [u8; MESSAGE_ID_LEN]) -> Self {
        Self(bytes)
    }

    /// The raw bytes.
    pub const fn as_bytes(&self) -> &[u8; MESSAGE_ID_LEN] {
        &self.0
    }

    /// Short 8-byte tag used for fragmentation (the first 8 bytes of the id).
    pub fn transfer_tag(&self) -> [u8; 8] {
        let mut t = [0u8; 8];
        t.copy_from_slice(&self.0[..8]);
        t
    }
}

impl From<[u8; MESSAGE_ID_LEN]> for MessageId {
    fn from(b: [u8; MESSAGE_ID_LEN]) -> Self {
        Self(b)
    }
}

impl fmt::Display for MessageId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write_hex(&self.0, f)
    }
}

impl fmt::Debug for MessageId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "MessageId(")?;
        write_hex(&self.0[..4], f)?;
        write!(f, "..)")
    }
}
