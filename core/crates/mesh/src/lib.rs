//! # mesh
//!
//! The Crisis Network mesh logic, written **sans-IO**: the [`Mesh`] state machine
//! never touches a radio, a socket or a clock. The platform feeds it [`Event`]s
//! together with the current time and carries out the [`Action`]s it returns.
//!
//! What it does:
//!
//! * **Propagation.** Neighbours announce what they hold (`Inv`), request what they
//!   lack (`Want`) and then receive the message as fragments. Each message carries a
//!   hop budget, and every node deduplicates by [`proto::MessageId`].
//! * **Synchronisation.** When a peer connects, and periodically afterwards
//!   (anti-entropy), the node offers its log in small pages, paced by the caller's
//!   ticks.
//! * **Reassembly.** Fragments are put back together with a timeout and strict
//!   memory limits per peer and overall.
//! * **Abuse protection.** Per-peer rate limits, a penalty score with bans, a
//!   per-sender rate limit, local sender blocking and a hop cap.
//! * **Delivery.** A node acknowledges direct messages addressed to it; the original
//!   sender gets a [`Notification::Delivered`] only for a valid Ack from the recipient.
//!
//! Signatures are always verified before a message is stored or forwarded.
//!
//! # Driving the state machine
//!
//! ```text
//! let actions = mesh.handle(now_ms, Event::PacketReceived { peer, bytes })?;
//! for action in actions { /* SendPacket, Disconnect, SetTimer, Notify */ }
//! ```
//!
//! The platform should call `handle(now_ms, Event::Tick)` at or after every
//! [`Action::SetTimer`] deadline. Given the same events and times, a `Mesh`
//! behaves identically every run: it uses no randomness and iterates its
//! collections in a fixed order.
#![forbid(unsafe_code)]

mod config;
mod error;
mod event;
mod node;
mod reassembly;
mod util;

pub use config::MeshConfig;
pub use error::{MeshError, Result};
pub use event::{Action, DisconnectReason, Event, MeshStats, Notification, PeerId, PublishError};
pub use node::{MIN_LINK_MTU, Mesh};
