//! On-disk behaviour: reopening, corruption detection, encryption key handling.

mod common;

use common::*;
use rusqlite::Connection;
use storage::{Config, DbKey, InsertOutcome, Storage, StorageError};

const T0: u64 = 1_000;

#[test]
fn data_survives_reopening() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("crisis.db");
    let env = broadcast(1);
    let id = env.message_id().unwrap();

    {
        let mut s = Storage::open(&path, Config::default(), None).unwrap();
        s.insert(&env, T0).unwrap();
        s.touch_peer(&node(5), T0).unwrap();
        let gone = broadcast(2);
        s.insert(&gone, T0).unwrap();
        s.delete(&gone.message_id().unwrap(), T0).unwrap();
    }

    let mut s = Storage::open(&path, Config::default(), None).unwrap();
    assert_eq!(s.get(&id, T0).unwrap().unwrap().envelope, env);
    assert!(s.get_peer(&node(5)).unwrap().is_some());
    // Tombstones persist too, and the log keeps counting from where it stopped.
    assert_eq!(
        s.insert(&broadcast(2), T0).unwrap(),
        InsertOutcome::Tombstoned
    );
    assert!(matches!(
        s.insert(&broadcast(3), T0).unwrap(),
        InsertOutcome::Inserted { seq: 3, .. }
    ));
    s.check_integrity().unwrap();
}

#[test]
fn a_tampered_envelope_is_detected_on_read() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("crisis.db");
    let env = broadcast(1);
    let id = env.message_id().unwrap();
    {
        let mut s = Storage::open(&path, Config::default(), None).unwrap();
        s.insert(&env, T0).unwrap();
    }
    // Flip a payload byte behind the store's back.
    {
        let conn = Connection::open(&path).unwrap();
        let mut blob: Vec<u8> = conn
            .query_row("SELECT envelope FROM messages", [], |r| r.get(0))
            .unwrap();
        let i = blob.len() - 70; // inside the payload, before the signature
        blob[i] ^= 0xff;
        conn.execute("UPDATE messages SET envelope = ?1", [blob])
            .unwrap();
    }
    let s = Storage::open(&path, Config::default(), None).unwrap();
    assert!(matches!(s.get(&id, T0), Err(StorageError::Corrupt(_))));
}

#[test]
fn a_foreign_file_is_rejected() {
    let dir = tempfile::tempdir().unwrap();

    // Not a database at all.
    let junk = dir.path().join("junk.db");
    std::fs::write(&junk, vec![0x5a; 8192]).unwrap();
    assert!(matches!(
        Storage::open(&junk, Config::default(), None),
        Err(StorageError::NotADatabaseOrWrongKey)
    ));

    // A valid SQLite database that belongs to something else.
    let other = dir.path().join("other.db");
    {
        let conn = Connection::open(&other).unwrap();
        conn.execute_batch("PRAGMA user_version = 3; CREATE TABLE notes (x);")
            .unwrap();
    }
    assert!(matches!(
        Storage::open(&other, Config::default(), None),
        Err(StorageError::NotOurDatabase)
    ));
}

#[test]
fn a_newer_database_is_rejected() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("crisis.db");
    drop(Storage::open(&path, Config::default(), None).unwrap());
    {
        let conn = Connection::open(&path).unwrap();
        conn.execute_batch("PRAGMA user_version = 99;").unwrap();
    }
    assert!(matches!(
        Storage::open(&path, Config::default(), None),
        Err(StorageError::SchemaTooNew { found: 99, .. })
    ));
}

/// With plain SQLite a key must be refused; with SQLCipher it must really encrypt.
/// Either way the data is never silently stored unencrypted.
#[test]
fn a_key_is_either_honoured_or_refused() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("crisis.db");
    let key = DbKey::new([7; 32]);
    let wrong = DbKey::new([8; 32]);
    assert_eq!(format!("{key:?}"), "DbKey(<redacted>)");

    match Storage::open(&path, Config::default(), Some(&key)) {
        Err(StorageError::EncryptionUnavailable) => {} // plain SQLite build
        Err(other) => panic!("unexpected error: {other}"),
        Ok(mut s) => {
            // SQLCipher build.
            let env = broadcast(1);
            s.insert(&env, T0).unwrap();
            drop(s);
            assert!(matches!(
                Storage::open(&path, Config::default(), Some(&wrong)),
                Err(StorageError::NotADatabaseOrWrongKey)
            ));
            assert!(matches!(
                Storage::open(&path, Config::default(), None),
                Err(StorageError::NotADatabaseOrWrongKey)
            ));
            let s = Storage::open(&path, Config::default(), Some(&key)).unwrap();
            assert!(s.contains(&env.message_id().unwrap(), T0).unwrap());
        }
    }
}
