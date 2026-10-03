//! Hata tipi.

use proto::ProtoError;

/// Kripto katmani hatalari. Sifre cozme hatalari kasitli olarak ayrintisizdir.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum CryptoError {
    /// Anahtar bicimi veya noktasi gecersiz.
    #[error("gecersiz anahtar")]
    InvalidKey,
    /// Imza dogrulanamadi.
    #[error("imza gecersiz")]
    BadSignature,
    /// Sifre cozulemedi (yanlis anahtar, bozuk veri veya baglam uyusmazligi).
    #[error("sifre cozulemedi")]
    Decrypt,
    /// Sifreleme basarisiz.
    #[error("sifreleme basarisiz")]
    Encrypt,
    /// X25519 sonucu tamamen sifir (dusuk dereceli nokta).
    #[error("gecersiz ortak sir (dusuk dereceli nokta)")]
    LowOrderPoint,
    /// Anahtar turetme hatasi.
    #[error("anahtar turetme hatasi")]
    Kdf,
    /// Isletim sistemi rastgelelik kaynagi basarisiz.
    #[error("rastgelelik kaynagi basarisiz")]
    Rng,
    /// Kisi karti gecersiz (uzunluk veya imza).
    #[error("gecersiz kisi karti")]
    InvalidContactCard,
    /// Zarf bu kimlige ait degil veya turu uygun degil.
    #[error("zarf bu kimlige ait degil")]
    NotForMe,
    /// Govdenin `sender` alani imzalayan kimlikle ayni degil.
    #[error("govde baska bir gonderene ait")]
    SenderMismatch,
    /// Anahtar deposu hatasi (platform mesaji).
    #[error("anahtar deposu: {0}")]
    KeyStore(String),
    /// Protokol (kodlama/sinir) hatasi.
    #[error(transparent)]
    Proto(#[from] ProtoError),
}

/// Crate genelinde kullanilan sonuc tipi.
pub type Result<T> = core::result::Result<T, CryptoError>;
