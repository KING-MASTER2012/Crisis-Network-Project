//! The message log: insertion, retention, deletion and queries.

use std::collections::HashSet;

use proto::limits::MAX_LIFETIME_SECS;
use proto::{Envelope, EnvelopeBody, Kind, MessageId, NodeId};
use rusqlite::{OptionalExtension, Row, TransactionBehavior, params};

use crate::error::{Result, StorageError};
use crate::store::{Storage, counters, from_i64, to_i64};
use crate::types::{InsertOutcome, Priority, PurgeReport, StoredMessage};

pub(crate) const MSG_COLS: &str = "seq, id, envelope, received_at_ms, expires_at_ms, uploaded_at_ms";
const MAX_LIFETIME_MS: u64 = MAX_LIFETIME_SECS as u64 * 1000;

/// A row as read from SQLite, before the envelope is decoded.
pub(crate) struct RawRow {
    seq: i64,
    id: Vec<u8>,
    envelope: Vec<u8>,
    received_at_ms: i64,
    expires_at_ms: i64,
    uploaded_at_ms: Option<i64>,
}

pub(crate) fn raw_from_row(r: &Row<'_>) -> rusqlite::Result<RawRow> {
    Ok(RawRow {
        seq: r.get(0)?,
        id: r.get(1)?,
        envelope: r.get(2)?,
        received_at_ms: r.get(3)?,
        expires_at_ms: r.get(4)?,
        uploaded_at_ms: r.get(5)?,
    })
}

fn message_id_from_blob(blob: &[u8]) -> Result<MessageId> {
    let arr: [u8; 32] = blob
        .try_into()
        .map_err(|_| StorageError::Corrupt("message id has the wrong length"))?;
    Ok(MessageId::new(arr))
}

impl RawRow {
    /// Decodes the envelope and checks that it still hashes to the recorded id.
    pub(crate) fn into_stored(self) -> Result<StoredMessage> {
        let id = message_id_from_blob(&self.id)?;
        let envelope = Envelope::decode(&self.envelope)?;
        if envelope.message_id()? != id {
            return Err(StorageError::Corrupt(
                "stored envelope does not match its id",
            ));
        }
        Ok(StoredMessage {
            seq: from_i64(self.seq),
            id,
            envelope,
            received_at_ms: from_i64(self.received_at_ms),
            expires_at_ms: from_i64(self.expires_at_ms),
            uploaded_at_ms: self.uploaded_at_ms.map(from_i64),
        })
    }
}

impl Storage {
    /// The *nominal* retention priority of a message on this node.
    ///
    /// [`Storage::insert`] may store a message with a lower priority than this when
    /// the budget for data that other nodes can pin is used up
    /// (see [`crate::Config::max_inbound_own_bytes`]).
    pub fn priority_of(&self, body: &EnvelopeBody) -> Priority {
        if let Some(local) = &self.config.local_node
            && (body.sender == *local || body.recipient == Some(*local))
        {
            return Priority::Own;
        }
        match body.kind {
            Kind::Direct => Priority::Direct,
            Kind::Broadcast => Priority::Broadcast,
            Kind::Ack => Priority::Ack,
        }
    }

    /// Whether other nodes can make us hold this message with `Own` priority: it is
    /// addressed to us by someone else, or it is an acknowledgement we created in reply.
    fn is_pinned_by_others(&self, body: &EnvelopeBody) -> bool {
        let Some(local) = &self.config.local_node else {
            return false;
        };
        let inbound = body.sender != *local && body.recipient == Some(*local);
        let own_ack = body.sender == *local && body.kind == Kind::Ack;
        inbound || own_ack
    }

    /// Appends an envelope to the log.
    ///
    /// The envelope's signature must already have been verified by the caller.
    ///
    /// Insertion is idempotent: a known id yields [`InsertOutcome::Duplicate`] and
    /// a live tombstone yields [`InsertOutcome::Tombstoned`]; neither changes the
    /// store. Expiry is counted from `now_ms` (our clock), never from the sender's
    /// `created_at_ms`, which cannot be trusted.
    ///
    /// `Own` priority is granted to messages the local user wrote without limit, but to
    /// messages that other nodes address to us (and the acks we create for them) only
    /// while they stay within [`crate::Config::max_inbound_own_bytes`]; beyond that they
    /// are stored with their transit priority. This keeps an attacker from filling the
    /// store with data that can never be evicted.
    ///
    /// When the byte budget would be exceeded, messages of priority lower than or
    /// equal to the new one (and never [`Priority::Own`]) are evicted, lowest
    /// priority first and oldest first within a priority. If that cannot free
    /// enough room, nothing is evicted and [`InsertOutcome::QuotaExceeded`] is
    /// returned. Everything happens in one transaction.
    pub fn insert(&mut self, envelope: &Envelope, now_ms: u64) -> Result<InsertOutcome> {
        let blob = envelope.encode()?;
        let id = envelope.message_id()?;
        let body = &envelope.body;
        let size = blob.len() as u64;
        let nominal = self.priority_of(body);
        let pinned_by_others = nominal == Priority::Own && self.is_pinned_by_others(body);
        let transit = match body.kind {
            Kind::Direct => Priority::Direct,
            Kind::Broadcast => Priority::Broadcast,
            Kind::Ack => Priority::Ack,
        };
        let local_bytes = self
            .config
            .local_node
            .map_or([0u8; 32], |node| *node.as_bytes());
        let max_inbound_own = self.config.max_inbound_own_bytes;
        let max_total = self.config.max_total_bytes;
        let expires_at_ms = now_ms.saturating_add(u64::from(body.lifetime_secs) * 1000);

        let tx = self
            .conn
            .transaction_with_behavior(TransactionBehavior::Immediate)?;

        let tombstoned: bool = tx.query_row(
            "SELECT EXISTS (SELECT 1 FROM tombstones WHERE id = ?1 AND expires_at_ms > ?2)",
            params![&id.as_bytes()[..], to_i64(now_ms)],
            |r| r.get(0),
        )?;
        if tombstoned {
            return Ok(InsertOutcome::Tombstoned);
        }
        let known: bool = tx.query_row(
            "SELECT EXISTS (SELECT 1 FROM messages WHERE id = ?1)",
            params![&id.as_bytes()[..]],
            |r| r.get(0),
        )?;
        if known {
            return Ok(InsertOutcome::Duplicate);
        }

        let priority = if pinned_by_others {
            let pinned: i64 = tx.query_row(
                "SELECT COALESCE(SUM(size), 0) FROM messages
                  WHERE priority = ?1 AND (kind = ?2 OR sender <> ?3)",
                params![Priority::Own as i64, Kind::Ack as i64, &local_bytes[..]],
                |r| r.get(0),
            )?;
            if from_i64(pinned).saturating_add(size) > max_inbound_own {
                transit
            } else {
                Priority::Own
            }
        } else {
            nominal
        };

        let (_, total) = counters(&tx)?;
        let need = total.saturating_add(size).saturating_sub(max_total);
        let mut evicted = 0usize;
        if need > 0 {
            if size > max_total {
                return Ok(InsertOutcome::QuotaExceeded);
            }
            let mut victims: Vec<i64> = Vec::new();
            let mut freed = 0u64;
            {
                let mut stmt = tx.prepare(
                    "SELECT seq, size FROM messages
                      WHERE priority <= ?1 AND priority < ?2
                      ORDER BY priority ASC, received_at_ms ASC, seq ASC",
                )?;
                let mut rows = stmt.query(params![priority as i64, Priority::Own as i64])?;
                while let Some(row) = rows.next()? {
                    victims.push(row.get::<_, i64>(0)?);
                    freed += from_i64(row.get::<_, i64>(1)?);
                    if freed >= need {
                        break;
                    }
                }
            }
            if freed < need {
                // Dropping `tx` rolls back; nothing was deleted yet anyway.
                return Ok(InsertOutcome::QuotaExceeded);
            }
            for seq in &victims {
                tx.execute("DELETE FROM messages WHERE seq = ?1", params![seq])?;
            }
            evicted = victims.len();
        }

        tx.execute(
            "INSERT INTO messages
                 (id, kind, sender, recipient, created_at_ms, received_at_ms,
                  expires_at_ms, size, priority, envelope)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)",
            params![
                &id.as_bytes()[..],
                body.kind as i64,
                &body.sender.as_bytes()[..],
                body.recipient.as_ref().map(|r| r.as_bytes().to_vec()),
                to_i64(body.created_at_ms),
                to_i64(now_ms),
                to_i64(expires_at_ms),
                to_i64(size),
                priority as i64,
                blob,
            ],
        )?;
        let seq = from_i64(tx.last_insert_rowid());
        tx.commit()?;
        Ok(InsertOutcome::Inserted { seq, evicted })
    }

    /// The highest log sequence number currently stored (0 if the log is empty).
    ///
    /// Sequence numbers handed out later are always larger, so this is a safe
    /// starting point for tracking "everything after position N".
    pub fn head_seq(&self) -> Result<u64> {
        let v: i64 =
            self.conn
                .query_row("SELECT COALESCE(MAX(seq), 0) FROM messages", [], |r| {
                    r.get(0)
                })?;
        Ok(from_i64(v))
    }

    /// Whether a live (not expired) message with this id is stored.
    pub fn contains(&self, id: &MessageId, now_ms: u64) -> Result<bool> {
        Ok(self.conn.query_row(
            "SELECT EXISTS (SELECT 1 FROM messages WHERE id = ?1 AND expires_at_ms > ?2)",
            params![&id.as_bytes()[..], to_i64(now_ms)],
            |r| r.get(0),
        )?)
    }

    /// Whether a live tombstone exists for this id.
    pub fn is_tombstoned(&self, id: &MessageId, now_ms: u64) -> Result<bool> {
        Ok(self.conn.query_row(
            "SELECT EXISTS (SELECT 1 FROM tombstones WHERE id = ?1 AND expires_at_ms > ?2)",
            params![&id.as_bytes()[..], to_i64(now_ms)],
            |r| r.get(0),
        )?)
    }

    /// Reads one live message.
    pub fn get(&self, id: &MessageId, now_ms: u64) -> Result<Option<StoredMessage>> {
        let raw = self
            .conn
            .query_row(
                &format!("SELECT {MSG_COLS} FROM messages WHERE id = ?1 AND expires_at_ms > ?2"),
                params![&id.as_bytes()[..], to_i64(now_ms)],
                raw_from_row,
            )
            .optional()?;
        raw.map(RawRow::into_stored).transpose()
    }

    /// Live messages with `seq > after_seq`, in log order, at most `limit`.
    /// Page through the log by passing the last `seq` you saw.
    pub fn list_after(
        &self,
        after_seq: u64,
        limit: usize,
        now_ms: u64,
    ) -> Result<Vec<StoredMessage>> {
        let mut stmt = self.conn.prepare(&format!(
            "SELECT {MSG_COLS} FROM messages
              WHERE seq > ?1 AND expires_at_ms > ?2
              ORDER BY seq ASC LIMIT ?3"
        ))?;
        let raws = stmt
            .query_map(
                params![to_i64(after_seq), to_i64(now_ms), to_i64(limit as u64)],
                raw_from_row,
            )?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        raws.into_iter().map(RawRow::into_stored).collect()
    }

    /// Like [`Storage::list_after`], restricted to messages addressed to `node`.
    pub fn list_for_recipient(
        &self,
        node: &NodeId,
        after_seq: u64,
        limit: usize,
        now_ms: u64,
    ) -> Result<Vec<StoredMessage>> {
        let mut stmt = self.conn.prepare(&format!(
            "SELECT {MSG_COLS} FROM messages
              WHERE recipient = ?1 AND seq > ?2 AND expires_at_ms > ?3
              ORDER BY seq ASC LIMIT ?4"
        ))?;
        let raws = stmt
            .query_map(
                params![
                    &node.as_bytes()[..],
                    to_i64(after_seq),
                    to_i64(now_ms),
                    to_i64(limit as u64)
                ],
                raw_from_row,
            )?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        raws.into_iter().map(RawRow::into_stored).collect()
    }

    /// `(seq, id)` pairs of live messages with `seq > after_seq`: the cheap summary
    /// a node offers to a peer during synchronisation.
    pub fn ids_after(
        &self,
        after_seq: u64,
        limit: usize,
        now_ms: u64,
    ) -> Result<Vec<(u64, MessageId)>> {
        let mut stmt = self.conn.prepare(
            "SELECT seq, id FROM messages
              WHERE seq > ?1 AND expires_at_ms > ?2
              ORDER BY seq ASC LIMIT ?3",
        )?;
        let rows = stmt
            .query_map(
                params![to_i64(after_seq), to_i64(now_ms), to_i64(limit as u64)],
                |r| Ok((r.get::<_, i64>(0)?, r.get::<_, Vec<u8>>(1)?)),
            )?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        rows.into_iter()
            .map(|(seq, id)| Ok((from_i64(seq), message_id_from_blob(&id)?)))
            .collect()
    }

    /// Given the ids a peer offers, returns those we should request: ids we neither
    /// hold (expired-but-unpurged copies count as held) nor have a live tombstone for.
    /// Input order is kept and duplicates are dropped.
    pub fn missing(&self, offered: &[MessageId], now_ms: u64) -> Result<Vec<MessageId>> {
        let mut stmt = self.conn.prepare(
            "SELECT EXISTS (SELECT 1 FROM messages WHERE id = ?1)
                 OR EXISTS (SELECT 1 FROM tombstones WHERE id = ?1 AND expires_at_ms > ?2)",
        )?;
        let mut seen = HashSet::new();
        let mut out = Vec::new();
        for id in offered {
            if !seen.insert(*id) {
                continue;
            }
            let known: bool =
                stmt.query_row(params![&id.as_bytes()[..], to_i64(now_ms)], |r| r.get(0))?;
            if !known {
                out.push(*id);
            }
        }
        Ok(out)
    }

    /// Deletes a message and leaves a tombstone so it is not accepted again.
    /// Returns `false` (and leaves no tombstone) if the message was not stored.
    pub fn delete(&mut self, id: &MessageId, now_ms: u64) -> Result<bool> {
        let tx = self
            .conn
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        let removed = tx.execute(
            "DELETE FROM messages WHERE id = ?1",
            params![&id.as_bytes()[..]],
        )?;
        if removed == 0 {
            return Ok(false);
        }
        tx.execute(
            "INSERT OR REPLACE INTO tombstones (id, deleted_at_ms, expires_at_ms)
             VALUES (?1, ?2, ?3)",
            params![
                &id.as_bytes()[..],
                to_i64(now_ms),
                to_i64(now_ms.saturating_add(MAX_LIFETIME_MS))
            ],
        )?;
        tx.commit()?;
        Ok(true)
    }

    /// Retention housekeeping; call it periodically (for example on every `Tick`).
    ///
    /// 1. Messages whose expiry has passed are removed and tombstoned, so a peer
    ///    that still holds a copy cannot make them reappear.
    /// 2. Tombstones past their retention (the maximum message lifetime) are removed.
    /// 3. If there are more tombstones than `Config::max_tombstones`, the oldest are trimmed.
    pub fn purge_expired(&mut self, now_ms: u64) -> Result<PurgeReport> {
        let max_tombstones = self.config.max_tombstones;
        let tx = self
            .conn
            .transaction_with_behavior(TransactionBehavior::Immediate)?;

        tx.execute(
            "INSERT OR IGNORE INTO tombstones (id, deleted_at_ms, expires_at_ms)
             SELECT id, ?1, ?2 FROM messages WHERE expires_at_ms <= ?1",
            params![
                to_i64(now_ms),
                to_i64(now_ms.saturating_add(MAX_LIFETIME_MS))
            ],
        )?;
        let messages_expired = tx.execute(
            "DELETE FROM messages WHERE expires_at_ms <= ?1",
            params![to_i64(now_ms)],
        )? as u64;
        let tombstones_expired = tx.execute(
            "DELETE FROM tombstones WHERE expires_at_ms <= ?1",
            params![to_i64(now_ms)],
        )? as u64;

        let count: i64 = tx.query_row("SELECT COUNT(*) FROM tombstones", [], |r| r.get(0))?;
        let excess = from_i64(count).saturating_sub(max_tombstones);
        let mut tombstones_trimmed = 0;
        if excess > 0 {
            tombstones_trimmed = tx.execute(
                "DELETE FROM tombstones WHERE id IN (
                     SELECT id FROM tombstones ORDER BY deleted_at_ms ASC, id ASC LIMIT ?1)",
                params![to_i64(excess)],
            )? as u64;
        }
        tx.commit()?;
        Ok(PurgeReport {
            messages_expired,
            tombstones_expired,
            tombstones_trimmed,
        })
    }

    /// Marks messages as uploaded to a server. Returns how many were newly marked.
    pub fn mark_uploaded(&mut self, ids: &[MessageId], now_ms: u64) -> Result<usize> {
        let tx = self
            .conn
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        let mut marked = 0;
        {
            let mut stmt = tx.prepare(
                "UPDATE messages SET uploaded_at_ms = ?2 WHERE id = ?1 AND uploaded_at_ms IS NULL",
            )?;
            for id in ids {
                marked += stmt.execute(params![&id.as_bytes()[..], to_i64(now_ms)])?;
            }
        }
        tx.commit()?;
        Ok(marked)
    }

    /// Live messages not yet uploaded, oldest first, at most `limit`.
    pub fn pending_upload(&self, limit: usize, now_ms: u64) -> Result<Vec<StoredMessage>> {
        let mut stmt = self.conn.prepare(&format!(
            "SELECT {MSG_COLS} FROM messages
              WHERE uploaded_at_ms IS NULL AND expires_at_ms > ?1
              ORDER BY seq ASC LIMIT ?2"
        ))?;
        let raws = stmt
            .query_map(params![to_i64(now_ms), to_i64(limit as u64)], raw_from_row)?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        raws.into_iter().map(RawRow::into_stored).collect()
    }
}
