//! The [`Storage`] handle: opening, encryption key, pragmas and integrity checks.

use std::path::Path;

use rusqlite::Connection;

use crate::config::Config;
use crate::error::{Result, StorageError, map_open_error};
use crate::key::DbKey;
use crate::migrations::{MIGRATIONS, migrate};
use crate::types::Stats;

/// The on-device store. See the crate documentation for the model.
pub struct Storage {
    pub(crate) conn: Connection,
    pub(crate) config: Config,
}

#[cfg(feature = "sqlcipher")]
fn apply_key(conn: &Connection, key: &DbKey) -> Result<()> {
    use zeroize::Zeroizing;

    let mut hex = Zeroizing::new(String::with_capacity(64));
    for b in key.as_bytes() {
        hex.push_str(&format!("{b:02x}"));
    }
    let sql = Zeroizing::new(format!("PRAGMA key = \"x'{}'\";", hex.as_str()));
    conn.execute_batch(sql.as_str()).map_err(map_open_error)
}

#[cfg(not(feature = "sqlcipher"))]
fn apply_key(_conn: &Connection, _key: &DbKey) -> Result<()> {
    Err(StorageError::EncryptionUnavailable)
}

impl Storage {
    /// Opens (creating if needed) the database at `path`.
    ///
    /// `key` enables at-rest encryption and requires the `sqlcipher` feature;
    /// without it, passing a key returns [`StorageError::EncryptionUnavailable`]
    /// rather than silently storing data unencrypted.
    pub fn open(path: &Path, config: Config, key: Option<&DbKey>) -> Result<Self> {
        let conn = Connection::open(path).map_err(map_open_error)?;
        Self::init(conn, config, key, true)
    }

    /// Opens a private in-memory database (for tests and the simulator).
    pub fn open_in_memory(config: Config) -> Result<Self> {
        let conn = Connection::open_in_memory().map_err(map_open_error)?;
        Self::init(conn, config, None, false)
    }

    fn init(
        mut conn: Connection,
        config: Config,
        key: Option<&DbKey>,
        on_disk: bool,
    ) -> Result<Self> {
        config.validate()?;
        if let Some(k) = key {
            apply_key(&conn, k)?;
        }
        // Forces SQLite to read the file: fails here on a wrong key or a foreign file.
        conn.query_row("SELECT COUNT(*) FROM sqlite_master", [], |r| {
            r.get::<_, i64>(0)
        })
        .map_err(map_open_error)?;

        conn.execute_batch("PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;")?;
        if on_disk {
            conn.execute_batch("PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL;")?;
        }
        migrate(&mut conn, MIGRATIONS)?;
        Ok(Self { conn, config })
    }

    /// The active configuration.
    pub fn config(&self) -> &Config {
        &self.config
    }

    /// Current counters.
    pub fn stats(&self) -> Result<Stats> {
        let (message_count, total_bytes) = self.counters()?;
        let tombstone_count: i64 =
            self.conn
                .query_row("SELECT COUNT(*) FROM tombstones", [], |r| r.get(0))?;
        let peer_count: i64 = self
            .conn
            .query_row("SELECT COUNT(*) FROM peers", [], |r| r.get(0))?;
        Ok(Stats {
            message_count,
            total_bytes,
            tombstone_count: from_i64(tombstone_count),
            peer_count: from_i64(peer_count),
        })
    }

    pub(crate) fn counters(&self) -> Result<(u64, u64)> {
        counters(&self.conn)
    }

    /// Checks the internal invariants: the trigger-maintained counters match the
    /// table, and SQLite's own quick check passes. Intended for diagnostics and tests.
    pub fn check_integrity(&self) -> Result<()> {
        let (count, bytes) = self.counters()?;
        let (real_count, real_bytes): (i64, i64) = self.conn.query_row(
            "SELECT COUNT(*), COALESCE(SUM(size), 0) FROM messages",
            [],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )?;
        if count != from_i64(real_count) || bytes != from_i64(real_bytes) {
            return Err(StorageError::Corrupt(
                "counters do not match the messages table",
            ));
        }
        let quick: String = self
            .conn
            .query_row("PRAGMA quick_check", [], |r| r.get(0))?;
        if quick != "ok" {
            return Err(StorageError::Corrupt("sqlite quick_check failed"));
        }
        Ok(())
    }
}

pub(crate) fn counters(conn: &Connection) -> Result<(u64, u64)> {
    let (c, b): (i64, i64) = conn.query_row(
        "SELECT message_count, total_bytes FROM counters WHERE id = 1",
        [],
        |r| Ok((r.get(0)?, r.get(1)?)),
    )?;
    Ok((from_i64(c), from_i64(b)))
}

/// Saturating `u64` -> `i64` (SQLite integers are signed 64-bit).
pub(crate) fn to_i64(v: u64) -> i64 {
    i64::try_from(v).unwrap_or(i64::MAX)
}

/// `i64` -> `u64`; negative values (which we never write) map to 0.
pub(crate) fn from_i64(v: i64) -> u64 {
    u64::try_from(v).unwrap_or(0)
}
