//! Schema versioning.
//!
//! The schema version lives in SQLite's `PRAGMA user_version`; the file is tagged
//! with `PRAGMA application_id` so that foreign databases are rejected instead of
//! being "migrated". Each migration runs in its own transaction together with the
//! version bump, so a crash never leaves a half-applied step.

use rusqlite::Connection;

use crate::error::{Result, StorageError};

/// `"CRIS"` in ASCII.
pub(crate) const APPLICATION_ID: i32 = 0x4352_4953;

/// One schema step.
pub(crate) struct Migration {
    pub(crate) version: u32,
    pub(crate) sql: &'static str,
}

/// Version 1: the initial schema.
const V1: &str = "
CREATE TABLE messages (
    seq            INTEGER PRIMARY KEY AUTOINCREMENT,
    id             BLOB    NOT NULL UNIQUE CHECK (length(id) = 32),
    kind           INTEGER NOT NULL,
    sender         BLOB    NOT NULL CHECK (length(sender) = 32),
    recipient      BLOB             CHECK (recipient IS NULL OR length(recipient) = 32),
    created_at_ms  INTEGER NOT NULL,
    received_at_ms INTEGER NOT NULL,
    expires_at_ms  INTEGER NOT NULL,
    size           INTEGER NOT NULL CHECK (size > 0),
    priority       INTEGER NOT NULL CHECK (priority BETWEEN 0 AND 3),
    uploaded_at_ms INTEGER,
    envelope       BLOB    NOT NULL
);
CREATE INDEX messages_expires ON messages (expires_at_ms);
CREATE INDEX messages_evict ON messages (priority, received_at_ms, seq);
CREATE INDEX messages_recipient ON messages (recipient, seq) WHERE recipient IS NOT NULL;
CREATE INDEX messages_pending_upload ON messages (seq) WHERE uploaded_at_ms IS NULL;

CREATE TABLE tombstones (
    id            BLOB    PRIMARY KEY CHECK (length(id) = 32),
    deleted_at_ms INTEGER NOT NULL,
    expires_at_ms INTEGER NOT NULL
) WITHOUT ROWID;
CREATE INDEX tombstones_expires ON tombstones (expires_at_ms);

CREATE TABLE peers (
    node_id       BLOB    PRIMARY KEY CHECK (length(node_id) = 32),
    first_seen_ms INTEGER NOT NULL,
    last_seen_ms  INTEGER NOT NULL,
    trust         INTEGER NOT NULL DEFAULT 0 CHECK (trust IN (0, 1, 2)),
    contact_card  BLOB    CHECK (contact_card IS NULL OR length(contact_card) = 128)
) WITHOUT ROWID;

-- Running totals, maintained by triggers so they cannot drift from the table.
CREATE TABLE counters (
    id            INTEGER PRIMARY KEY CHECK (id = 1),
    message_count INTEGER NOT NULL,
    total_bytes   INTEGER NOT NULL
);
INSERT INTO counters (id, message_count, total_bytes) VALUES (1, 0, 0);

CREATE TRIGGER messages_after_insert AFTER INSERT ON messages
BEGIN
    UPDATE counters
       SET message_count = message_count + 1,
           total_bytes   = total_bytes + NEW.size
     WHERE id = 1;
END;

CREATE TRIGGER messages_after_delete AFTER DELETE ON messages
BEGIN
    UPDATE counters
       SET message_count = message_count - 1,
           total_bytes   = total_bytes - OLD.size
     WHERE id = 1;
END;
";

/// Version 2: a covering index for the "pinned by other nodes" byte count, so that the
/// quota check in `Storage::insert` reads the index only (see `Config::max_inbound_own_bytes`).
const V2: &str = "
CREATE INDEX messages_pinned ON messages (priority, kind, sender, size);
";

/// All migrations, in order. Versions must be contiguous, starting at 1.
pub(crate) const MIGRATIONS: &[Migration] = &[
    Migration {
        version: 1,
        sql: V1,
    },
    Migration {
        version: 2,
        sql: V2,
    },
];

/// Brings the database to the newest version in `migrations`.
pub(crate) fn migrate(conn: &mut Connection, migrations: &[Migration]) -> Result<()> {
    let latest = migrations.last().map_or(0, |m| m.version);
    let app_id: i32 = conn.query_row("PRAGMA application_id", [], |r| r.get(0))?;
    let version: u32 = conn.query_row("PRAGMA user_version", [], |r| r.get(0))?;

    if app_id != 0 && app_id != APPLICATION_ID {
        return Err(StorageError::NotOurDatabase);
    }
    if app_id == 0 && version != 0 {
        return Err(StorageError::NotOurDatabase);
    }
    if version > latest {
        return Err(StorageError::SchemaTooNew {
            found: version,
            supported: latest,
        });
    }

    for m in migrations.iter().filter(|m| m.version > version) {
        let tx = conn.transaction()?;
        tx.execute_batch(m.sql)?;
        // The tag and the version change in the same transaction as the schema.
        tx.execute_batch(&format!(
            "PRAGMA application_id = {APPLICATION_ID}; PRAGMA user_version = {};",
            m.version
        ))?;
        tx.commit()?;
    }
    Ok(())
}

#[cfg(test)]
pub(crate) fn current_version(conn: &Connection) -> Result<u32> {
    Ok(conn.query_row("PRAGMA user_version", [], |r| r.get(0))?)
}

#[cfg(test)]
mod tests {
    use super::*;

    const V3_TEST: &str = "ALTER TABLE peers ADD COLUMN note TEXT;";

    fn mem() -> Connection {
        Connection::open_in_memory().unwrap()
    }

    fn latest() -> u32 {
        MIGRATIONS.last().map_or(0, |m| m.version)
    }

    #[test]
    fn fresh_database_gets_latest_schema() {
        let mut c = mem();
        migrate(&mut c, MIGRATIONS).unwrap();
        assert_eq!(current_version(&c).unwrap(), latest());
        let app_id: i32 = c
            .query_row("PRAGMA application_id", [], |r| r.get(0))
            .unwrap();
        assert_eq!(app_id, APPLICATION_ID);
        // Running again is a no-op.
        migrate(&mut c, MIGRATIONS).unwrap();
        assert_eq!(current_version(&c).unwrap(), latest());
    }

    #[test]
    fn version_one_databases_are_upgraded_with_the_pinned_index() {
        let mut c = mem();
        migrate(&mut c, &MIGRATIONS[..1]).unwrap();
        assert_eq!(current_version(&c).unwrap(), 1);
        c.execute(
            "INSERT INTO messages (id, kind, sender, created_at_ms, received_at_ms, expires_at_ms,
                                    size, priority, envelope)
             VALUES (zeroblob(32), 1, zeroblob(32), 0, 0, 10, 100, 1, x'00')",
            [],
        )
        .unwrap();
        migrate(&mut c, MIGRATIONS).unwrap();
        assert_eq!(current_version(&c).unwrap(), latest());
        let rows: i64 = c
            .query_row("SELECT COUNT(*) FROM messages", [], |r| r.get(0))
            .unwrap();
        assert_eq!(rows, 1);
        let indexes: i64 = c
            .query_row(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'messages_pinned'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(indexes, 1);
    }

    #[test]
    fn upgrade_preserves_existing_data() {
        let mut c = mem();
        migrate(&mut c, MIGRATIONS).unwrap();
        c.execute(
            "INSERT INTO peers (node_id, first_seen_ms, last_seen_ms) VALUES (?1, 1, 2)",
            [vec![7u8; 32]],
        )
        .unwrap();

        let extended = [
            Migration {
                version: 1,
                sql: V1,
            },
            Migration {
                version: 2,
                sql: V2,
            },
            Migration {
                version: 3,
                sql: V3_TEST,
            },
        ];
        migrate(&mut c, &extended).unwrap();
        assert_eq!(current_version(&c).unwrap(), 3);
        let (n, note): (i64, Option<String>) = c
            .query_row("SELECT COUNT(*), MAX(note) FROM peers", [], |r| {
                Ok((r.get(0)?, r.get(1)?))
            })
            .unwrap();
        assert_eq!((n, note), (1, None));
    }

    #[test]
    fn newer_database_is_rejected() {
        let mut c = mem();
        let extended = [
            Migration {
                version: 1,
                sql: V1,
            },
            Migration {
                version: 2,
                sql: V2,
            },
            Migration {
                version: 3,
                sql: V3_TEST,
            },
        ];
        migrate(&mut c, &extended).unwrap();
        let err = migrate(&mut c, MIGRATIONS).unwrap_err();
        assert!(matches!(
            err,
            StorageError::SchemaTooNew { found: 3, supported } if supported == latest()
        ));
    }

    #[test]
    fn foreign_database_is_rejected() {
        let mut c = mem();
        c.execute_batch("PRAGMA user_version = 5; CREATE TABLE t (x);")
            .unwrap();
        assert!(matches!(
            migrate(&mut c, MIGRATIONS),
            Err(StorageError::NotOurDatabase)
        ));

        let mut c = mem();
        c.execute_batch("PRAGMA application_id = 1234;").unwrap();
        assert!(matches!(
            migrate(&mut c, MIGRATIONS),
            Err(StorageError::NotOurDatabase)
        ));
    }

    #[test]
    fn failed_migration_leaves_no_partial_state() {
        let mut c = mem();
        migrate(&mut c, MIGRATIONS).unwrap();
        let broken = [
            Migration {
                version: 1,
                sql: V1,
            },
            Migration {
                version: 2,
                sql: V2,
            },
            Migration {
                version: 3,
                sql: "ALTER TABLE peers ADD COLUMN ok_col TEXT; THIS IS NOT SQL;",
            },
        ];
        assert!(migrate(&mut c, &broken).is_err());
        assert_eq!(current_version(&c).unwrap(), latest());
        let has_col: i64 = c
            .query_row(
                "SELECT COUNT(*) FROM pragma_table_info('peers') WHERE name = 'ok_col'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(has_col, 0);
    }
}
