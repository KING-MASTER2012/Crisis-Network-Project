//! Versioning of the interface and of the core behind it.
//!
//! Three numbers matter to an application:
//!
//! * the **interface version** ([`FFI_API_VERSION`]): it changes whenever the exported
//!   functions, types or their meaning change incompatibly. An application checks it once
//!   at start-up with [`require_api_version`], so that a stale native library or stale
//!   generated bindings fail loudly instead of misbehaving;
//! * the **protocol version**: the wire format spoken with other nodes;
//! * the **storage schema version**: the database layout, migrated automatically on
//!   open, never downgraded.
//!
//! UniFFI additionally checks at load time that the generated bindings and the native
//! library were produced from the same interface definition.

use crate::error::CoreError;

/// Version of the exported interface. Increase it on every incompatible change.
///
/// * 2: conversations (phase C7): `HistoryItem` gained `delivered_at_ms` and carries the text
///   of sent direct messages; new `conversations`, `conversation_history`,
///   `mark_conversation_read` and `unread_counts`.
pub const FFI_API_VERSION: u32 = 2;

/// Which core this library contains.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CoreInfo {
    /// The crate version (semantic version of this build).
    pub crate_version: String,
    /// The interface version, see [`FFI_API_VERSION`].
    pub api_version: u32,
    /// The wire protocol version.
    pub protocol_version: u8,
    /// The database schema version written by this build.
    pub storage_schema_version: u32,
    /// The smallest transport packet size (bytes) the core can work with.
    pub min_link_mtu: u32,
    /// The largest message payload (bytes) the protocol allows.
    pub max_payload_bytes: u32,
    /// The longest message lifetime (seconds) the protocol allows.
    pub max_lifetime_secs: u32,
}

/// Describes the core inside this library.
#[uniffi::export]
pub fn core_info() -> CoreInfo {
    // The protocol limits are small constants; saturating keeps the conversion total.
    CoreInfo {
        crate_version: env!("CARGO_PKG_VERSION").to_owned(),
        api_version: FFI_API_VERSION,
        protocol_version: proto::limits::PROTOCOL_VERSION,
        storage_schema_version: storage::schema_version(),
        min_link_mtu: u32::try_from(mesh::MIN_LINK_MTU).unwrap_or(u32::MAX),
        max_payload_bytes: u32::try_from(proto::limits::MAX_PAYLOAD_LEN).unwrap_or(u32::MAX),
        max_lifetime_secs: proto::limits::MAX_LIFETIME_SECS,
    }
}

/// The interface version of this library.
#[uniffi::export]
pub fn ffi_api_version() -> u32 {
    FFI_API_VERSION
}

/// Fails with [`CoreError::VersionMismatch`] unless this library provides exactly the
/// interface version the application was written for. Call it once at start-up.
#[uniffi::export]
pub fn require_api_version(expected: u32) -> Result<(), CoreError> {
    if expected == FFI_API_VERSION {
        Ok(())
    } else {
        Err(CoreError::VersionMismatch {
            expected,
            actual: FFI_API_VERSION,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_version_check_accepts_only_the_exact_version() {
        require_api_version(FFI_API_VERSION).unwrap();
        assert_eq!(
            require_api_version(FFI_API_VERSION + 1),
            Err(CoreError::VersionMismatch {
                expected: FFI_API_VERSION + 1,
                actual: FFI_API_VERSION
            })
        );
    }

    #[test]
    fn core_info_reports_this_build() {
        let info = core_info();
        assert_eq!(info.api_version, ffi_api_version());
        assert_eq!(info.crate_version, env!("CARGO_PKG_VERSION"));
        assert_eq!(info.storage_schema_version, storage::schema_version());
        assert!(info.min_link_mtu >= 64);
        assert!(info.max_payload_bytes > 0);
    }
}
