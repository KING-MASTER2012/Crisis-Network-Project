//! # proto
//!
//! The Crisis Network **wire format**. This crate is sans-IO: it touches no
//! network, disk, clock or randomness, and it neither creates nor verifies
//! signatures (that is the `crypto` crate's job). Input is bytes, output is typed
//! data (and the reverse).
//!
//! Layers, innermost first:
//!
//! 1. [`envelope::Envelope`]: the signed, persistent message. [`id::MessageId`]
//!    is its hash.
//! 2. [`frame::Frame`]: `hops_left` (outside the signature, changes on every hop)
//!    plus the envelope.
//! 3. [`fragment::Fragment`]: a frame cut into pieces that fit a BLE MTU.
//! 4. [`link::Packet`]: what two neighbouring nodes actually exchange
//!    (`Inv`, `Want` and `Fragment` packets).
//!
//! Specification: the "Protocol v1" chapter of the book (`docs/src/protocol/v1.md`).
//! This crate is its reference implementation; if the two disagree, the
//! specification wins and the code is considered buggy.
#![forbid(unsafe_code)]

mod codec;
pub mod domain;
pub mod envelope;
pub mod error;
pub mod fragment;
pub mod frame;
pub mod id;
pub mod limits;
pub mod link;

pub use envelope::{Envelope, EnvelopeBody, Kind};
pub use error::{ProtoError, Result};
pub use fragment::{Fragment, reassemble, split};
pub use frame::Frame;
pub use id::{MessageId, NodeId};
pub use link::{MAX_IDS_PER_PACKET, Packet};
