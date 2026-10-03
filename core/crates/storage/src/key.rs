//! Database encryption key.

use core::fmt;

use zeroize::Zeroizing;

/// A raw 256-bit SQLCipher key. It is wiped from memory on drop and never shown
/// by `Debug`. Where it is stored (platform key store) is the caller's concern.
pub struct DbKey(Zeroizing<[u8; 32]>);

impl DbKey {
    /// Wraps a raw 32-byte key.
    pub fn new(key: [u8; 32]) -> Self {
        Self(Zeroizing::new(key))
    }

    #[cfg_attr(not(feature = "sqlcipher"), allow(dead_code))]
    pub(crate) fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }
}

impl fmt::Debug for DbKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("DbKey(<redacted>)")
    }
}
