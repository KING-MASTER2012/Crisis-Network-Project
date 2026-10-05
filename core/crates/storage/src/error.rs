//! Error type.

use proto::ProtoError;

/// Storage errors.
#[derive(Debug, thiserror::Error)]
pub enum StorageError {
    /// An error reported by SQLite.
    #[error("sqlite: {0}")]
    Sqlite(#[from] rusqlite::Error),
    /// A stored or supplied envelope violates the protocol rules.
    #[error(transparent)]
    Proto(#[from] ProtoError),
    /// The file is not a database, or the encryption key is wrong.
    #[error("not a database, or wrong encryption key")]
    NotADatabaseOrWrongKey,
    /// A key was supplied, but the linked SQLite is not SQLCipher.
    #[error("database encryption is unavailable: the linked SQLite is not SQLCipher")]
    EncryptionUnavailable,
    /// The database was not created by this application.
    #[error("database was not created by this application")]
    NotOurDatabase,
    /// The database was written by a newer version of the application.
    #[error("database schema version {found} is newer than the supported version {supported}")]
    SchemaTooNew {
        /// Version found in the file.
        found: u32,
        /// Highest version this build understands.
        supported: u32,
    },
    /// The configuration is invalid.
    #[error("invalid configuration: {0}")]
    InvalidConfig(&'static str),
    /// A caller-supplied argument is invalid.
    #[error("invalid argument: {0}")]
    InvalidArgument(&'static str),
    /// Stored data is inconsistent (corruption or tampering).
    #[error("stored data is corrupt: {0}")]
    Corrupt(&'static str),
}

/// Result type used throughout the crate.
pub type Result<T> = core::result::Result<T, StorageError>;

/// Maps SQLite's "not a database" code to a dedicated error.
pub(crate) fn map_open_error(e: rusqlite::Error) -> StorageError {
    if let rusqlite::Error::SqliteFailure(f, _) = &e
        && f.code == rusqlite::ErrorCode::NotADatabase
    {
        return StorageError::NotADatabaseOrWrongKey;
    }
    StorageError::Sqlite(e)
}
