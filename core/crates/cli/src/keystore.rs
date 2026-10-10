//! A key store that keeps the identity seed in a plain file.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use crypto::KeyStore;
use engine::hex;
use zeroize::Zeroizing;

/// Keeps the 32-byte identity seed as hexadecimal text in a file.
///
/// **For testing only**: the seed is not protected beyond file permissions (owner-only
/// on Unix). Real applications use the platform's secure store (Android Keystore, Apple
/// Keychain, ...) through the same [`KeyStore`] interface.
#[derive(Clone, Debug)]
pub struct FileKeyStore {
    path: PathBuf,
}

impl FileKeyStore {
    /// A store backed by `path`; the file is created on the first save.
    pub fn new(path: PathBuf) -> Self {
        Self { path }
    }

    /// The backing file.
    pub fn path(&self) -> &Path {
        &self.path
    }
}

#[cfg(unix)]
fn restrict_permissions(path: &Path) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o600))
}

#[cfg(not(unix))]
fn restrict_permissions(_path: &Path) -> io::Result<()> {
    Ok(())
}

impl KeyStore for FileKeyStore {
    fn load_seed(&self) -> Result<Option<Zeroizing<[u8; 32]>>, String> {
        let text = match fs::read_to_string(&self.path) {
            Ok(text) => Zeroizing::new(text),
            Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(e) => return Err(format!("cannot read {}: {e}", self.path.display())),
        };
        let bytes = hex::decode(&text).ok_or_else(|| {
            format!("{} does not contain a hexadecimal seed", self.path.display())
        })?;
        let seed: [u8; 32] = bytes
            .try_into()
            .map_err(|_| format!("{} must hold exactly 32 bytes", self.path.display()))?;
        Ok(Some(Zeroizing::new(seed)))
    }

    fn store_seed(&self, seed: &[u8; 32]) -> Result<(), String> {
        let parent = self.path.parent().filter(|p| !p.as_os_str().is_empty());
        if let Some(dir) = parent {
            fs::create_dir_all(dir).map_err(|e| format!("cannot create {}: {e}", dir.display()))?;
        }
        let text = Zeroizing::new(format!("{}\n", hex::encode(seed)));
        fs::write(&self.path, text.as_bytes())
            .map_err(|e| format!("cannot write {}: {e}", self.path.display()))?;
        restrict_permissions(&self.path).map_err(|e| format!("cannot restrict permissions: {e}"))
    }

    fn delete_seed(&self) -> Result<(), String> {
        match fs::remove_file(&self.path) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(format!("cannot delete {}: {e}", self.path.display())),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn creates_reuses_and_deletes_the_key_file() {
        let dir = tempfile::tempdir().unwrap();
        let store = FileKeyStore::new(dir.path().join("nested").join("id.key"));
        assert!(store.load_seed().unwrap().is_none());

        let first = crypto::load_or_create(&store).unwrap();
        let second = crypto::load_or_create(&store).unwrap();
        assert_eq!(first.node_id(), second.node_id());
        assert!(store.path().exists());

        store.delete_seed().unwrap();
        store.delete_seed().unwrap(); // deleting twice is fine
        assert!(store.load_seed().unwrap().is_none());
    }

    #[test]
    fn a_damaged_key_file_is_reported_not_replaced() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("id.key");
        let store = FileKeyStore::new(path.clone());
        fs::write(&path, "not hex at all").unwrap();
        assert!(store.load_seed().is_err());
        fs::write(&path, "abcd").unwrap();
        assert!(store.load_seed().is_err());
        assert!(crypto::load_or_create(&store).is_err());
        assert_eq!(fs::read_to_string(&path).unwrap(), "abcd");
    }
}
