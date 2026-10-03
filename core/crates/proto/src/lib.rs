//! # proto
//!
//! Kriz Agi'nin **wire formati**. Bu crate sans-IO'dur: ag, disk, saat ve
//! rastgelelik kullanmaz; imza uretmez/dogrulamaz (bu `crypto`'nun isi).
//! Girdisi bayt, ciktisi tiplenmis veri (ve tersi).
//!
//! Katmanlar (icten disa):
//!
//! 1. [`envelope::Envelope`]: imzali, kalici mesaj. [`id::MessageId`] bunun hash'idir.
//! 2. [`frame::Frame`]: `hops_left` (imza disinda, her sicramada degisir) + zarf.
//! 3. [`fragment::Fragment`]: BLE MTU'suna sigsin diye bolunmus cerceve parcasi.
//!
//! Spesifikasyon: `docs/protocol/PROTOCOL.md`. Bu crate o dokumanin referans
//! uygulamasidir; ikisi celisirse dokuman gecerlidir ve kod hatali sayilir.
#![forbid(unsafe_code)]

mod codec;
pub mod domain;
pub mod envelope;
pub mod error;
pub mod fragment;
pub mod frame;
pub mod id;
pub mod limits;

pub use envelope::{Envelope, EnvelopeBody, Kind};
pub use error::{ProtoError, Result};
pub use fragment::{Fragment, reassemble, split};
pub use frame::Frame;
pub use id::{MessageId, NodeId};
