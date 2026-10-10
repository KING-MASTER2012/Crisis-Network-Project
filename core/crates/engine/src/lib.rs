//! # engine
//!
//! The high-level API of the Crisis Network core. An [`Engine`] owns a node's
//! identity, its message store and the [`mesh::Mesh`] state machine, and exposes what
//! an application needs:
//!
//! * **Events in, outputs out.** [`Engine::handle`] takes a transport or timer event
//!   and returns an [`Output`]: packets to send, connections to close, notifications
//!   for the user interface and the next timer deadline.
//! * **Publishing.** [`Engine::publish_broadcast`] and [`Engine::publish_direct`] sign
//!   (and, for direct messages, encrypt) a message and hand it to the mesh.
//! * **Contacts.** Contact cards are verified before they are stored; senders can be
//!   blocked or marked verified.
//! * **History.** [`Engine::history`] reads the message log back as readable entries,
//!   decrypting direct messages addressed to this node.
//!
//! Like the mesh below it, the engine is sans-IO and deterministic: it never reads the
//! clock, so every call that depends on time takes `now_ms`.
#![forbid(unsafe_code)]

mod config;
mod driver;
mod error;
pub mod hex;
mod history;
mod output;

pub use config::EngineConfig;
pub use driver::Engine;
pub use error::{EngineError, Result};
pub use history::{Contact, Direction, HistoryEntry, HistoryPage};
pub use output::{Output, TransportAction};
