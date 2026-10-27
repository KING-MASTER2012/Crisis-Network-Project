//! # ffi (`crisis_core`)
//!
//! The foreign-function interface of the Crisis Network core: a small, versioned surface
//! generated for Kotlin (Android) and Swift (iOS and macOS) by [UniFFI]. The desktop
//! client (Tauri) links the same crate directly from Rust.
//!
//! The interface is deliberately thin. All behaviour lives in the [`engine`] crate; this
//! crate only
//!
//! * wraps an engine in a thread-safe object, [`CrisisNode`];
//! * turns the core's types into plain records and enums (ids and keys are byte arrays,
//!   neighbours are `u64` handles chosen by the transport);
//! * maps every failure to the stable [`CoreError`];
//! * reports its own version ([`FFI_API_VERSION`], [`core_info`], [`require_api_version`]).
//!
//! The core is sans-IO: the platform owns Bluetooth (or any other transport), the clock
//! and the secure key store. It passes events in and carries out the returned
//! [`CoreOutput`].
//!
//! ```text
//! radio -> peer_seen / packet_received / tick -> CrisisNode -> CoreOutput -> radio, UI, timer
//! ```
//!
//! Bindings are generated with `scripts/gen-bindings.sh`.
//!
//! [UniFFI]: https://mozilla.github.io/uniffi-rs/

mod error;
mod node;
mod types;
mod version;

pub use error::CoreError;
pub use node::{
    CrisisNode, default_settings, generate_database_key, generate_seed, inspect_contact_card,
};
pub use types::{
    CardDetails, ContactInfo, ConversationInfo, ConversationPageInfo, ConversationRef,
    CoreNotification, CoreOutput, DisconnectCause, HistoryItem, HistoryPageInfo, MessageDirection,
    MessageKind, NodeSettings, NodeStats, PublishFailure, PublishOutcome, TransportCommand,
    TrustLevel, UnreadInfo,
};
pub use version::{CoreInfo, FFI_API_VERSION, core_info, ffi_api_version, require_api_version};

uniffi::setup_scaffolding!("crisis_core");
