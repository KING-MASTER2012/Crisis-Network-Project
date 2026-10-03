//! Error types.

/// Parsing and validation errors. No error is a panic: whatever bytes arrive over
/// the air, the functions in this crate return `Err`.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ProtoError {
    /// The input is shorter than expected.
    #[error("input is truncated")]
    Truncated,
    /// There are extra bytes after the end of the message.
    #[error("trailing bytes after the message")]
    TrailingBytes,
    /// Unsupported protocol version.
    #[error("unsupported protocol version: {0}")]
    UnsupportedVersion(u8),
    /// Unknown message kind.
    #[error("unknown message kind: {0}")]
    UnknownKind(u8),
    /// The reserved `flags` byte must be zero in v1.
    #[error("reserved flags must be zero: {0:#04x}")]
    NonZeroFlags(u8),
    /// A field exceeds its allowed size.
    #[error("{field} is too long: {len} > {max}")]
    TooLong {
        /// Field name.
        field: &'static str,
        /// Observed length.
        len: usize,
        /// Maximum allowed length.
        max: usize,
    },
    /// `lifetime_secs` is zero or above the maximum.
    #[error("invalid lifetime_secs")]
    InvalidLifetime,
    /// `hops_left` is above the maximum.
    #[error("invalid hops_left: {0}")]
    InvalidHops(u8),
    /// A field is inconsistent with the message kind.
    #[error("invalid field: {0}")]
    InvalidField(&'static str),
    /// Fragmentation or reassembly error.
    #[error("fragment error: {0}")]
    Fragment(&'static str),
}

/// Result type used throughout the crate.
pub type Result<T> = core::result::Result<T, ProtoError>;
