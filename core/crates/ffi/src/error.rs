//! The error type of the foreign-function interface.

use engine::EngineError;
use mesh::MeshError;
use storage::StorageError;

/// Everything that can go wrong in a call across the boundary.
///
/// The variants are stable parts of the API: applications are expected to react to
/// specific ones (for example to ask for the database key again on
/// `WrongKeyOrNotADatabase`). The `detail` texts are for logs, not for users.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    /// An argument has the wrong size or shape (for example an id that is not 32 bytes).
    #[error("invalid argument: {detail}")]
    InvalidArgument {
        /// What is wrong.
        detail: String,
    },
    /// The settings are inconsistent.
    #[error("invalid configuration: {detail}")]
    InvalidConfig {
        /// What is wrong.
        detail: String,
    },
    /// No contact card is known for the recipient: add the contact first.
    #[error("no contact card is known for this node")]
    UnknownContact,
    /// A contact card is malformed or its signature does not verify.
    #[error("the contact card is invalid")]
    InvalidContactCard,
    /// The database file is not a database, or the key is wrong.
    #[error("not a database, or wrong encryption key")]
    WrongKeyOrNotADatabase,
    /// A key was given but this build of the core has no database encryption.
    #[error("database encryption is unavailable in this build")]
    EncryptionUnavailable,
    /// The database was written by a newer version of the core.
    #[error("database schema {found} is newer than the supported schema {supported}")]
    SchemaTooNew {
        /// The version found in the file.
        found: u32,
        /// The newest version this build understands.
        supported: u32,
    },
    /// Another storage failure.
    #[error("storage failure: {detail}")]
    Storage {
        /// What happened.
        detail: String,
    },
    /// A cryptographic operation failed.
    #[error("cryptographic failure: {detail}")]
    Crypto {
        /// What happened.
        detail: String,
    },
    /// A message violates the protocol rules (for example it is too large).
    #[error("protocol error: {detail}")]
    Protocol {
        /// What happened.
        detail: String,
    },
    /// The mesh failed.
    #[error("mesh failure: {detail}")]
    Mesh {
        /// What happened.
        detail: String,
    },
    /// The application was built against another version of the interface.
    #[error("interface version mismatch: expected {expected}, the library provides {actual}")]
    VersionMismatch {
        /// The version the application expects.
        expected: u32,
        /// The version this library provides.
        actual: u32,
    },
    /// A bug or an earlier panic left the node unusable; recreate it.
    #[error("internal error: {detail}")]
    Internal {
        /// What happened.
        detail: String,
    },
}

impl From<StorageError> for CoreError {
    fn from(e: StorageError) -> Self {
        match e {
            StorageError::NotADatabaseOrWrongKey => Self::WrongKeyOrNotADatabase,
            StorageError::EncryptionUnavailable => Self::EncryptionUnavailable,
            StorageError::SchemaTooNew { found, supported } => {
                Self::SchemaTooNew { found, supported }
            }
            StorageError::InvalidConfig(detail) => Self::InvalidConfig {
                detail: detail.to_owned(),
            },
            StorageError::InvalidArgument(detail) => Self::InvalidArgument {
                detail: detail.to_owned(),
            },
            StorageError::Proto(e) => Self::Protocol {
                detail: e.to_string(),
            },
            other => Self::Storage {
                detail: other.to_string(),
            },
        }
    }
}

impl From<MeshError> for CoreError {
    fn from(e: MeshError) -> Self {
        match e {
            MeshError::Storage(e) => e.into(),
            MeshError::Crypto(e) => e.into(),
            MeshError::Proto(e) => Self::Protocol {
                detail: e.to_string(),
            },
            MeshError::InvalidConfig(detail) => Self::InvalidConfig {
                detail: detail.to_owned(),
            },
            other => Self::Mesh {
                detail: other.to_string(),
            },
        }
    }
}

impl From<EngineError> for CoreError {
    fn from(e: EngineError) -> Self {
        match e {
            EngineError::Mesh(e) => e.into(),
            EngineError::Storage(e) => e.into(),
            EngineError::Crypto(e) => e.into(),
            EngineError::Proto(e) => Self::Protocol {
                detail: e.to_string(),
            },
            EngineError::InvalidConfig(detail) => Self::InvalidConfig {
                detail: detail.to_owned(),
            },
            EngineError::UnknownContact => Self::UnknownContact,
            EngineError::InvalidContactCard => Self::InvalidContactCard,
        }
    }
}

impl From<crypto::CryptoError> for CoreError {
    fn from(e: crypto::CryptoError) -> Self {
        match e {
            crypto::CryptoError::InvalidContactCard => Self::InvalidContactCard,
            other => Self::Crypto {
                detail: other.to_string(),
            },
        }
    }
}

/// Builds an [`CoreError::InvalidArgument`].
pub(crate) fn invalid_argument(detail: impl Into<String>) -> CoreError {
    CoreError::InvalidArgument {
        detail: detail.into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn engine_errors_keep_their_meaning() {
        assert_eq!(
            CoreError::from(EngineError::UnknownContact),
            CoreError::UnknownContact
        );
        assert_eq!(
            CoreError::from(EngineError::InvalidContactCard),
            CoreError::InvalidContactCard
        );
        assert_eq!(
            CoreError::from(EngineError::Storage(StorageError::NotADatabaseOrWrongKey)),
            CoreError::WrongKeyOrNotADatabase
        );
        assert_eq!(
            CoreError::from(EngineError::Storage(StorageError::SchemaTooNew {
                found: 9,
                supported: 2
            })),
            CoreError::SchemaTooNew {
                found: 9,
                supported: 2
            }
        );
        assert!(matches!(
            CoreError::from(EngineError::InvalidConfig("bad")),
            CoreError::InvalidConfig { .. }
        ));
    }

    #[test]
    fn crypto_card_errors_map_to_the_card_variant() {
        assert_eq!(
            CoreError::from(crypto::CryptoError::InvalidContactCard),
            CoreError::InvalidContactCard
        );
        assert!(matches!(
            CoreError::from(crypto::CryptoError::Decrypt),
            CoreError::Crypto { .. }
        ));
    }
}
