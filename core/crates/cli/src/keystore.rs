//! A key store that keeps the identity seed in a plain file.

use std::fs;
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};

use crypto::KeyStore;
use engine::hex;
use zeroize::Zeroizing;

/// A key file holds 64 hexadecimal digits and a newline; anything much larger is not a key
/// file, and reading it unbounded could exhaust memory (for example a link to a device).
const MAX_KEY_FILE_BYTES: u64 = 256;

/// Keeps the 32-byte identity seed as hexadecimal text in a file.
///
/// **For testing only**: the seed is not protected beyond file permissions (owner-only
/// on Unix, set when the file is created, so it is never readable by others, not even for
/// an instant). Real applications use the platform's secure store (Android Keystore, Apple
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

/// Creates a new file that only its owner can read, with those permissions in force from
/// the moment it exists.
#[cfg(unix)]
fn create_private(path: &Path) -> io::Result<fs::File> {
    use std::os::unix::fs::OpenOptionsExt;
    fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path)
}

#[cfg(not(unix))]
fn create_private(path: &Path) -> io::Result<fs::File> {
    fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
}

/// `path` with `.tmp` appended.
fn temp_path(path: &Path) -> PathBuf {
    let mut name = path.as_os_str().to_os_string();
    name.push(".tmp");
    PathBuf::from(name)
}

impl KeyStore for FileKeyStore {
    fn load_seed(&self) -> Result<Option<Zeroizing<[u8; 32]>>, String> {
        let file = match fs::File::open(&self.path) {
            Ok(file) => file,
            Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(e) => return Err(format!("cannot read {}: {e}", self.path.display())),
        };
        let mut text = Zeroizing::new(String::new());
        file.take(MAX_KEY_FILE_BYTES + 1)
            .read_to_string(&mut text)
            .map_err(|e| format!("cannot read {}: {e}", self.path.display()))?;
        if text.len() as u64 > MAX_KEY_FILE_BYTES {
            return Err(format!(
                "{} is too large to be a key file",
                self.path.display()
            ));
        }
        let bytes = hex::decode(&text).ok_or_else(|| {
            format!(
                "{} does not contain a hexadecimal seed",
                self.path.display()
            )
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

        // Write a private temporary file, then move it into place: the key file is never
        // visible half-written, and never with looser permissions than 0600.
        let temp = temp_path(&self.path);
        let _ = fs::remove_file(&temp); // a leftover from an interrupted run
        let written = create_private(&temp).and_then(|mut file| {
            file.write_all(text.as_bytes())?;
            file.sync_all()
        });
        let moved = written.and_then(|()| fs::rename(&temp, &self.path));
        if let Err(e) = moved {
            let _ = fs::remove_file(&temp);
            return Err(format!("cannot write {}: {e}", self.path.display()));
        }
        Ok(())
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

    #[test]
    fn a_huge_file_is_refused_without_being_read_whole() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("id.key");
        fs::write(&path, "ab".repeat(10_000)).unwrap();
        let err = FileKeyStore::new(path).load_seed().unwrap_err();
        assert!(err.contains("too large"), "{err}");
    }

    #[test]
    fn saving_replaces_the_file_and_leaves_no_temporary_behind() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("id.key");
        let store = FileKeyStore::new(path.clone());
        store.store_seed(&[1; 32]).unwrap();
        store.store_seed(&[2; 32]).unwrap();
        assert_eq!(*store.load_seed().unwrap().unwrap(), [2; 32]);
        assert!(!temp_path(&path).exists());
    }

    #[cfg(unix)]
    #[test]
    fn the_key_file_is_owner_only_even_when_replacing_a_permissive_one() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("id.key");
        fs::write(&path, "old").unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(0o644)).unwrap();

        let store = FileKeyStore::new(path.clone());
        store.store_seed(&[9; 32]).unwrap();
        let mode = fs::metadata(&path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);
    }
}
