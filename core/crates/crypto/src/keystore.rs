//! Seed storage interface.
//!
//! The core **never writes the 32-byte identity seed to disk itself** and never
//! puts it in SQLite. The platform (Android Keystore, Apple Keychain, the desktop
//! OS vault) implements this interface; error messages are plain `String`s so they
//! can cross the FFI boundary.

use std::sync::Mutex;

use zeroize::Zeroizing;

use crate::error::{CryptoError, Result};
use crate::identity::Identity;

/// The platform's secure seed store.
pub trait KeyStore: Send + Sync {
    /// Returns the stored seed, or `Ok(None)` if there is none.
    fn load_seed(&self) -> core::result::Result<Option<Zeroizing<[u8; 32]>>, String>;
    /// Stores the seed (overwriting any existing one).
    fn store_seed(&self, seed: &[u8; 32]) -> core::result::Result<(), String>;
    /// Deletes the seed.
    fn delete_seed(&self) -> core::result::Result<(), String>;
}

/// Loads the identity from the store, or generates and saves a new one if none exists.
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

/// An in-memory store. **For tests and the CLI only**: the seed is lost when the
/// process exits.
#[derive(Default)]
pub struct MemoryKeyStore {
    inner: Mutex<Option<Zeroizing<[u8; 32]>>>,
}

impl KeyStore for MemoryKeyStore {
    fn load_seed(&self) -> core::result::Result<Option<Zeroizing<[u8; 32]>>, String> {
        let g = self.inner.lock().map_err(|_| "lock poisoned".to_string())?;
        Ok(g.clone())
    }

    fn store_seed(&self, seed: &[u8; 32]) -> core::result::Result<(), String> {
        let mut g = self.inner.lock().map_err(|_| "lock poisoned".to_string())?;
        *g = Some(Zeroizing::new(*seed));
        Ok(())
    }

    fn delete_seed(&self) -> core::result::Result<(), String> {
        let mut g = self.inner.lock().map_err(|_| "lock poisoned".to_string())?;
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
