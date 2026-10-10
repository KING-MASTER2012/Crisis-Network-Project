//! Engine configuration.

use mesh::MeshConfig;
use proto::limits::MAX_LIFETIME_SECS;
use storage::Config as StorageConfig;

use crate::error::{EngineError, Result};

/// Configuration of an [`crate::Engine`].
#[derive(Clone, Debug)]
pub struct EngineConfig {
    /// Mesh behaviour.
    pub mesh: MeshConfig,
    /// Store limits. The engine overrides `local_node` with its own identity.
    pub storage: StorageConfig,
    /// Lifetime given to messages published without an explicit one (seconds).
    pub default_lifetime_secs: u32,
}

impl Default for EngineConfig {
    fn default() -> Self {
        Self {
            mesh: MeshConfig::default(),
            storage: StorageConfig::default(),
            default_lifetime_secs: 24 * 60 * 60,
        }
    }
}

impl EngineConfig {
    /// Checks that the values are consistent.
    pub fn validate(&self) -> Result<()> {
        if self.default_lifetime_secs == 0 || self.default_lifetime_secs > MAX_LIFETIME_SECS {
            return Err(EngineError::InvalidConfig(
                "default_lifetime_secs must be between 1 and the protocol maximum",
            ));
        }
        self.mesh.validate()?;
        Ok(())
    }
}
