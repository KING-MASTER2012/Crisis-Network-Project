//! Alan ayirma (domain separation) etiketleri.
//!
//! Her imza/hash/KDF ciktisi kendi etiketiyle uretilir; boylece bir baglamda
//! uretilen deger baska bir baglamda gecerli sayilamaz. Etiketler sabittir ve
//! protokol surumuyle (`v1`) birlikte degisir.

/// Zarf imzasinin girdisi: `DOMAIN_ENVELOPE_SIG || imzali_govde`.
pub const DOMAIN_ENVELOPE_SIG: &[u8] = b"kriz/v1/envelope\0";

/// Mesaj kimligi: `SHA-256(DOMAIN_MSG_ID || imzali_govde)`.
pub const DOMAIN_MSG_ID: &[u8] = b"kriz/v1/msgid\0";
