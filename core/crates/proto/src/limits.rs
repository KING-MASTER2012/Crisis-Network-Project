//! Protokol sabitleri ve ust sinirlar. Hepsi `PROTOCOL.md` ile birebir aynidir.

/// Bu crate'in uretip kabul ettigi tek protokol surumu.
pub const PROTOCOL_VERSION: u8 = 1;

/// Ed25519 imza uzunlugu (bayt).
pub const SIGNATURE_LEN: usize = 64;
/// [`crate::NodeId`] uzunlugu (Ed25519 acik anahtari).
pub const NODE_ID_LEN: usize = 32;
/// [`crate::MessageId`] uzunlugu (SHA-256).
pub const MESSAGE_ID_LEN: usize = 32;

/// `ext` alaninin azami uzunlugu.
pub const MAX_EXT_LEN: usize = 64;
/// `payload` alaninin azami uzunlugu (Direct icin sifreli govde dahil).
pub const MAX_PAYLOAD_LEN: usize = 1024;
/// Azami mesaj omru (7 gun, saniye).
pub const MAX_LIFETIME_SECS: u32 = 7 * 24 * 60 * 60;
/// Bir cercevenin tasiyabilecegi azami `hops_left`.
pub const MAX_HOPS: u8 = 16;

/// Sealed-box ek yuku: gecici X25519 anahtari (32) + Poly1305 etiketi (16).
pub const SEAL_OVERHEAD: usize = 32 + 16;
/// Direct mesajda sifrelenebilecek azami acik metin.
pub const MAX_PLAINTEXT_LEN: usize = MAX_PAYLOAD_LEN - SEAL_OVERHEAD;
/// Ack mesajinin payload'u tam olarak onaylanan mesajin kimligidir.
pub const ACK_PAYLOAD_LEN: usize = MESSAGE_ID_LEN;

/// Kodlanmis tek bir zarfin azami uzunlugu.
pub const MAX_ENVELOPE_LEN: usize =
    3 + NODE_ID_LEN + NODE_ID_LEN + 8 + 4 + 1 + MAX_EXT_LEN + 2 + MAX_PAYLOAD_LEN + SIGNATURE_LEN;
/// Kodlanmis tek bir cercevenin azami uzunlugu.
pub const MAX_FRAME_LEN: usize = 1 + MAX_ENVELOPE_LEN;

/// Parca basligi: transfer_id (8) + index (1) + total (1).
pub const FRAGMENT_HEADER_LEN: usize = 10;
/// Bir mesajin en fazla kac parcaya bolunebilecegi (`total` bir `u8`).
pub const MAX_FRAGMENTS: usize = 255;
