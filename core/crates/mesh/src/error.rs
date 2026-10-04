//! Error type.

use crypto::CryptoError;
use proto::ProtoError;
use storage::StorageError;

/// Errors that stop an event from being processed.
///
/// Misbehaving peers never produce an error: bad packets are counted, penalised and
/// dropped. These errors describe local failures (storage, crypto) or invalid setup.
#[derive(Debug, thiserror::Error)]
pub enum MeshError {
    /// The local store failed.
    #[error(transparent)]
    Storage(#[from] StorageError),
    /// A cryptographic operation failed.
    #[error(transparent)]
    Crypto(#[from] CryptoError),
    /// A protocol-level encoding error occurred on our own data.
    #[error(transparent)]
    Proto(#[from] ProtoError),
    /// The configuration is invalid.
    #[error("invalid configuration: {0}")]
    InvalidConfig(&'static str),
    /// The store's `local_node` is not the identity given to the mesh.
    #[error("the store's local node does not match the mesh identity")]
    LocalNodeMismatch,
}

/// Result type used throughout the crate.
pub type Result<T> = core::result::Result<T, MeshError>;
