//! Error type.

use crypto::CryptoError;
use mesh::MeshError;
use proto::ProtoError;
use storage::StorageError;

/// Errors returned by the [`crate::Engine`].
#[derive(Debug, thiserror::Error)]
pub enum EngineError {
    /// The mesh failed.
    #[error(transparent)]
    Mesh(#[from] MeshError),
    /// The message store failed.
    #[error(transparent)]
    Storage(#[from] StorageError),
    /// A cryptographic operation failed.
    #[error(transparent)]
    Crypto(#[from] CryptoError),
    /// A message could not be encoded.
    #[error(transparent)]
    Proto(#[from] ProtoError),
    /// The engine configuration is invalid.
    #[error("invalid configuration: {0}")]
    InvalidConfig(&'static str),
    /// No contact card is known for the requested node.
    #[error("no contact card is known for this node")]
    UnknownContact,
    /// A contact card is malformed, has a bad signature or belongs to another node.
    #[error("the contact card is invalid")]
    InvalidContactCard,
}

/// Result type used throughout the crate.
pub type Result<T> = core::result::Result<T, EngineError>;
