//! Error type.

use engine::EngineError;

/// Errors returned by the simulator.
#[derive(Debug, thiserror::Error)]
pub enum SimError {
    /// A node's engine failed.
    #[error(transparent)]
    Engine(#[from] EngineError),
    /// The simulator configuration is invalid.
    #[error("invalid simulator configuration: {0}")]
    InvalidConfig(&'static str),
    /// The scenario is inconsistent or unknown.
    #[error("invalid scenario: {0}")]
    InvalidScenario(String),
}

/// Result type used throughout the crate.
pub type Result<T> = core::result::Result<T, SimError>;
