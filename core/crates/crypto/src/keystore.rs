//! Tohum depolama arayuzu.
//!
//! Cekirdek 32 baytlik kimlik tohumunu **kendisi diske yazmaz** ve SQLite'a
//! koymaz. Platform (Android Keystore, Apple Keychain, masaustu isletim sistemi
//! kasasi) bu arayuzu uygular; hata mesajlari duz `String`'tir ki FFI'den gecebilsin.

use std::sync::Mutex;

use zeroize::Zeroizing;

use crate::error::{CryptoError, Result};
use crate::identity::Identity;

/// Platformun guvenli tohum deposu.
pub trait KeyStore: Send + Sync {
    /// Kayitli tohumu dondurur; yoksa `Ok(None)`.
    fn load_seed(&self) -> core::result::Result<Option<Zeroizing<[u8; 32]>>, String>;
    /// Tohumu kaydeder (varsa uzerine yazar).
    fn store_seed(&self, seed: &[u8; 32]) -> core::result::Result<(), String>;
    /// Tohumu siler.
    fn delete_seed(&self) -> core::result::Result<(), String>;
}

/// Depodaki kimligi yukler; yoksa yenisini uretip kaydeder.
pub fn load_or_create(store: &dyn KeyStore) -> Result<Identity> {
    match store.load_seed().map_err(CryptoError::KeyStore)? {
        Some(seed) => Identity::from_seed(&seed),
        None => {
            let id = Identity::generate()?;
            store.store_seed(id.seed()).map_err(CryptoError::KeyStore)?;
            Ok(id)
        }
    }
}

/// Bellekte tutan depo. **Yalnizca test ve CLI icin**: surec bitince tohum kaybolur.
#[derive(Default)]
pub struct MemoryKeyStore {
    inner: Mutex<Option<Zeroizing<[u8; 32]>>>,
}

impl KeyStore for MemoryKeyStore {
    fn load_seed(&self) -> core::result::Result<Option<Zeroizing<[u8; 32]>>, String> {
        let g = self.inner.lock().map_err(|_| "kilit zehirli".to_string())?;
        Ok(g.clone())
    }

    fn store_seed(&self, seed: &[u8; 32]) -> core::result::Result<(), String> {
        let mut g = self.inner.lock().map_err(|_| "kilit zehirli".to_string())?;
        *g = Some(Zeroizing::new(*seed));
        Ok(())
    }

    fn delete_seed(&self) -> core::result::Result<(), String> {
        let mut g = self.inner.lock().map_err(|_| "kilit zehirli".to_string())?;
        *g = None;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn creates_once_then_reuses() {
        let store = MemoryKeyStore::default();
        let a = load_or_create(&store).unwrap();
        let b = load_or_create(&store).unwrap();
        assert_eq!(a.node_id(), b.node_id());
        store.delete_seed().unwrap();
        let c = load_or_create(&store).unwrap();
        assert_ne!(a.node_id(), c.node_id());
    }
}
