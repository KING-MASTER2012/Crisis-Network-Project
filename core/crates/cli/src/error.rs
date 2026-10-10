//! Error type.

use crypto::CryptoError;
use engine::EngineError;
use sim::SimError;

/// Errors of the command-line tools.
#[derive(Debug, thiserror::Error)]
pub enum CliError {
    /// The command line is wrong.
    #[error("{0}")]
    Usage(String),
    /// Reading, writing or networking failed.
    #[error("i/o error: {0}")]
    Io(#[from] std::io::Error),
    /// The engine failed.
    #[error(transparent)]
    Engine(#[from] EngineError),
    /// The simulator failed.
    #[error(transparent)]
    Sim(#[from] SimError),
    /// A cryptographic operation failed.
    #[error(transparent)]
    Crypto(#[from] CryptoError),
}
