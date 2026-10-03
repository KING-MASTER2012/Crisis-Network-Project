//! Store configuration.

use proto::NodeId;
use proto::limits::MAX_ENVELOPE_LEN;

use crate::error::{Result, StorageError};

/// Limits and local identity for a [`crate::Storage`].
#[derive(Clone, Debug)]
pub struct Config {
    /// Byte budget for stored envelopes (the sum of their encoded sizes).
    /// Must be at least [`MAX_ENVELOPE_LEN`] so one maximum-size message fits.
    pub max_total_bytes: u64,
    /// Maximum number of tombstones kept. The oldest ones are trimmed first.
    pub max_tombstones: u64,
    /// Maximum number of peers kept. Only peers in the `Unknown` trust state are
    /// trimmed (least recently seen first); verified and blocked peers are kept.
    pub max_peers: u64,
    /// The local node. Messages sent by it or addressed to it get
    /// [`crate::Priority::Own`] and are never evicted by quota pressure.
    pub local_node: Option<NodeId>,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            max_total_bytes: 32 * 1024 * 1024,
            max_tombstones: 100_000,
            max_peers: 10_000,
            local_node: None,
        }
    }
}

impl Config {
    pub(crate) fn validate(&self) -> Result<()> {
        if self.max_total_bytes < MAX_ENVELOPE_LEN as u64 {
            return Err(StorageError::InvalidConfig(
                "max_total_bytes is smaller than one maximum-size envelope",
            ));
        }
        if self.max_peers == 0 {
            return Err(StorageError::InvalidConfig("max_peers must be positive"));
        }
        Ok(())
    }
}
