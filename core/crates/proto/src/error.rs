//! Hata tipleri.

/// Ayristirma/dogrulama hatalari. Hicbir hata `panic` degildir; havadan gelen
/// bayt dizisi ne olursa olsun fonksiyonlar `Err` doner.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ProtoError {
    /// Girdi beklenenden kisa.
    #[error("girdi eksik (kesilmis)")]
    Truncated,
    /// Mesaj bittikten sonra fazladan bayt var.
    #[error("mesajdan sonra fazladan bayt var")]
    TrailingBytes,
    /// Desteklenmeyen protokol surumu.
    #[error("desteklenmeyen protokol surumu: {0}")]
    UnsupportedVersion(u8),
    /// Taninmayan mesaj turu.
    #[error("bilinmeyen mesaj turu: {0}")]
    UnknownKind(u8),
    /// Ayrilmis `flags` alani sifir olmali (v1).
    #[error("ayrilmis flags sifir olmali: {0:#04x}")]
    NonZeroFlags(u8),
    /// Bir alan izin verilen boyutu asiyor.
    #[error("{field} cok uzun: {len} > {max}")]
    TooLong {
        /// Alan adi.
        field: &'static str,
        /// Gorulen uzunluk.
        len: usize,
        /// Izin verilen azami uzunluk.
        max: usize,
    },
    /// `lifetime_secs` 0 veya azami degerin ustunde.
    #[error("gecersiz lifetime_secs")]
    InvalidLifetime,
    /// `hops_left` azami degerin ustunde.
    #[error("gecersiz hops_left: {0}")]
    InvalidHops(u8),
    /// Alan, mesaj turuyla uyumsuz.
    #[error("gecersiz alan: {0}")]
    InvalidField(&'static str),
    /// Parcalama / birlestirme hatasi.
    #[error("parca hatasi: {0}")]
    Fragment(&'static str),
}

/// Crate genelinde kullanilan sonuc tipi.
pub type Result<T> = core::result::Result<T, ProtoError>;
