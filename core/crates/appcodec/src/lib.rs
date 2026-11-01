//! # appcodec
//!
//! The **conventions of the apps**: small rules that every client (Android, Apple, Desktop)
//! must apply in exactly the same way, but that are not part of the wire protocol. They live
//! here, in one place, so that the clients cannot drift apart.
//!
//! | Module content | What it decides |
//! |---|---|
//! | [`sanitize::clean`] | Which characters of a text from another phone are dropped before it is shown. |
//! | [`announcement`] | How the urgency of an announcement travels inside its payload. |
//! | [`card`] | The text form of a contact card (QR codes, "share as text"). |
//! | [`budget`] | How many bytes a text may use in a direct message or an announcement. |
//! | [`lifetime`] | The lifetime presets and unit arithmetic of the compose screen. |
//! | [`nickname`] | How a nickname typed by the user is normalised. |
//! | [`status`] | The delivery status a sent message shows. |
//! | [`names`] | The short form of a node id. |
//!
//! Everything is a pure function: no I/O, no clock, no randomness, no state. The crate does
//! not depend on the mesh, the engine or the storage; it only reads the protocol's limits
//! from `proto` so that the byte budgets cannot disagree with the wire format.
//!
//! The shared test vectors in `core/testdata/appcodec.json` pin the behaviour. This crate's
//! tests check it, and the clients that still carry their own implementation can run the same
//! file against theirs.
#![forbid(unsafe_code)]

mod base64;

pub mod announcement;
pub mod budget;
pub mod card;
pub mod lifetime;
pub mod names;
pub mod nickname;
pub mod sanitize;
pub mod status;

pub use announcement::{
    DecodedAnnouncement, Severity, announcement_overhead, decode_announcement, encode_announcement,
};
pub use budget::{
    ByteCount, DIRECT_SEAL_OVERHEAD, announcement_limit, count_bytes, direct_limit, utf8_length,
};
pub use card::{CARD_LEN, CARD_PREFIX, decode_card, encode_card};
pub use lifetime::{
    DEFAULT_LIFETIME_SECS, LIFETIME_PRESET_SECS, LifetimeUnit, MIN_LIFETIME_SECS, lifetime_presets,
    lifetime_to_seconds, split_lifetime, valid_lifetime,
};
pub use names::{SHORT_ID_LEN, short_id};
pub use nickname::{MAX_NICKNAME_CHARS, normalize_nickname};
pub use sanitize::clean;
pub use status::{DeliveryStatus, message_status};
