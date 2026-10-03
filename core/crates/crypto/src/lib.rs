//! # crypto
//!
//! Kimlik, imza ve sifreleme. Primitifler sabit ve denenmis kutuphanelerden gelir
//! (Ed25519, X25519, HKDF-SHA256, ChaCha20-Poly1305); bu crate yalnizca bunlari
//! protokol icin birlestirir. Kendi primitifimizi icat etmiyoruz.
//!
//! * [`Identity`]: bir dugumun gizli kimligi (32 baytlik tohumdan turetilir).
//! * [`ContactCard`]: paylasilabilir, kendinden imzali kimlik karti (QR icin).
//! * [`seal`]: tek aliciya sealed-box sifreleme.
//! * [`verify_envelope`]: zarf imzasini siki dogrular.
//! * [`KeyStore`]: tohumun platform kasasinda (Keystore/Keychain) tutulmasi
//!   icin arayuz. Cekirdek tohumu kendisi diske yazmaz.
//!
//! Tasarim gerekceleri: `docs/adr/0002-kimlik-ve-kripto.md`. Bilinen sinirlar
//! (ileri gizlilik yok, vb.): `docs/THREAT_MODEL.md`.
#![forbid(unsafe_code)]

mod error;
mod kdf;
mod x25519;

pub mod contact;
pub mod identity;
pub mod keystore;
pub mod seal;

pub use contact::{CONTACT_CARD_LEN, ContactCard};
pub use error::{CryptoError, Result};
pub use identity::{Identity, verify_envelope};
pub use keystore::{KeyStore, MemoryKeyStore, load_or_create};
