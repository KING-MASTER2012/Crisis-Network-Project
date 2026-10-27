//! Conversations: per-peer views of the log, read markers and per-message notes.
//!
//! The log itself ([`crate::Storage::list_after`]) is one stream for everything. A chat
//! screen needs the other view: "the messages between me and this peer, newest first, a
//! page at a time", "which conversations exist and how many messages are unread", and the
//! text of the messages the user sent (their envelopes are sealed to the recipient).
//!
//! Everything here needs the local node (`Config::local_node`) to tell the two directions
//! of a conversation apart.

use proto::limits::MAX_PLAINTEXT_LEN;
use proto::{Kind, MessageId, NodeId};
use rusqlite::{OptionalExtension, params};

use crate::error::{Result, StorageError};
use crate::messages::{MSG_COLS, RawRow, raw_from_row};
use crate::store::{Storage, from_i64, to_i64};
use crate::types::{
    Conversation, ConversationMessages, ConversationRow, MessageNote, UnreadCounts,
};

/// Scope of the announcements in `read_marks`. A node id is 32 bytes long, so the two kinds
/// of scope cannot clash.
const BROADCAST_SCOPE: [u8; 1] = [0];

/// The key of a conversation in `read_marks`.
fn scope_of(conversation: &Conversation) -> Vec<u8> {
    match conversation {
        Conversation::Broadcasts => BROADCAST_SCOPE.to_vec(),
        Conversation::Direct(peer) => peer.as_bytes().to_vec(),
    }
}

fn node_from_blob(blob: &[u8]) -> Result<NodeId> {
    let bytes: [u8; 32] = blob
        .try_into()
        .map_err(|_| StorageError::Corrupt("peer id has the wrong length"))?;
    Ok(NodeId::new(bytes))
}

impl Storage {
    fn local_node(&self) -> Result<NodeId> {
        self.config.local_node.ok_or(StorageError::InvalidArgument(
            "conversations need the store to know its local node",
        ))
    }

    // ----- paging ------------------------------------------------------------------

    /// One page of a conversation, reading **backwards** from the newest message.
    ///
    /// * `before_seq`: only messages with a smaller log position are returned; `None`
    ///   starts at the newest message. To continue, pass the `seq` of the first message of
    ///   the page you got.
    /// * The messages of the page come back **oldest first**, ready to be shown in order.
    /// * [`ConversationMessages::more_before`] tells whether an older page exists.
    ///
    /// A [`Conversation::Direct`] holds the direct messages in both directions between the
    /// local node and the peer; [`Conversation::Broadcasts`] holds every live broadcast.
    /// Acknowledgements and messages that merely pass through this node are never part of a
    /// conversation. Expired messages (`expires_at_ms <= now_ms`) are skipped.
    pub fn conversation_messages(
        &self,
        conversation: &Conversation,
        before_seq: Option<u64>,
        limit: usize,
        now_ms: u64,
    ) -> Result<ConversationMessages> {
        let me = self.local_node()?;
        let before = before_seq.map_or(i64::MAX, to_i64);
        let now = to_i64(now_ms);
        // One more than asked for, to learn whether an older page exists.
        let fetch = to_i64((limit as u64).saturating_add(1));

        let raws = match conversation {
            Conversation::Broadcasts => {
                let mut stmt = self.conn.prepare(&format!(
                    "SELECT {MSG_COLS} FROM messages
                      WHERE kind = ?1 AND seq < ?2 AND expires_at_ms > ?3
                      ORDER BY seq DESC LIMIT ?4"
                ))?;
                stmt.query_map(
                    params![Kind::Broadcast as i64, before, now, fetch],
                    raw_from_row,
                )?
                .collect::<rusqlite::Result<Vec<_>>>()?
            }
            Conversation::Direct(peer) => {
                let mut stmt = self.conn.prepare(&format!(
                    "SELECT {MSG_COLS} FROM messages
                      WHERE kind = ?1 AND seq < ?2 AND expires_at_ms > ?3
                        AND ((sender = ?4 AND recipient = ?5) OR (sender = ?5 AND recipient = ?4))
                      ORDER BY seq DESC LIMIT ?6"
                ))?;
                stmt.query_map(
                    params![
                        Kind::Direct as i64,
                        before,
                        now,
                        &me.as_bytes()[..],
                        &peer.as_bytes()[..],
                        fetch
                    ],
                    raw_from_row,
                )?
                .collect::<rusqlite::Result<Vec<_>>>()?
            }
        };

        let more_before = raws.len() > limit;
        let mut messages = raws
            .into_iter()
            .take(limit)
            .map(RawRow::into_stored)
            .collect::<Result<Vec<_>>>()?;
        messages.reverse();
        Ok(ConversationMessages {
            messages,
            more_before,
        })
    }

    /// The conversations that hold at least one live message, the one with the newest
    /// message first, at most `limit` of them.
    ///
    /// The announcements count as one conversation ([`Conversation::Broadcasts`]); every
    /// peer with direct messages in either direction is another. `unread` counts incoming
    /// messages after the conversation's read marker (see [`Storage::mark_read`]).
    pub fn conversations(&self, limit: usize, now_ms: u64) -> Result<Vec<ConversationRow>> {
        let me = self.local_node()?;
        let now = to_i64(now_ms);
        let mut rows: Vec<ConversationRow> = Vec::new();

        let mut stmt = self.conn.prepare(
            "WITH d AS (
                 SELECT seq,
                        CASE WHEN sender = ?1 THEN recipient ELSE sender END AS peer,
                        sender <> ?1 AS incoming
                   FROM messages
                  WHERE kind = ?2 AND expires_at_ms > ?3
                    AND recipient IS NOT NULL AND (sender = ?1 OR recipient = ?1)
             )
             SELECT d.peer, MAX(d.seq), COUNT(*),
                    COALESCE(SUM(d.incoming AND d.seq > COALESCE(r.read_seq, 0)), 0)
               FROM d LEFT JOIN read_marks r ON r.scope = d.peer
              GROUP BY d.peer
              ORDER BY MAX(d.seq) DESC
              LIMIT ?4",
        )?;
        let direct = stmt
            .query_map(
                params![
                    &me.as_bytes()[..],
                    Kind::Direct as i64,
                    now,
                    to_i64(limit as u64)
                ],
                |r| {
                    Ok((
                        r.get::<_, Vec<u8>>(0)?,
                        r.get::<_, i64>(1)?,
                        r.get::<_, i64>(2)?,
                        r.get::<_, i64>(3)?,
                    ))
                },
            )?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        for (peer, last, count, unread) in direct {
            rows.push(ConversationRow {
                conversation: Conversation::Direct(node_from_blob(&peer)?),
                last_seq: from_i64(last),
                message_count: from_i64(count),
                unread: from_i64(unread),
            });
        }

        let (last, count, unread): (Option<i64>, i64, i64) = self.conn.query_row(
            "SELECT MAX(seq), COUNT(*),
                    COALESCE(SUM(sender <> ?1 AND seq > COALESCE(
                        (SELECT read_seq FROM read_marks WHERE scope = ?4), 0)), 0)
               FROM messages
              WHERE kind = ?2 AND expires_at_ms > ?3",
            params![
                &me.as_bytes()[..],
                Kind::Broadcast as i64,
                now,
                &BROADCAST_SCOPE[..]
            ],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )?;
        if count > 0 {
            rows.push(ConversationRow {
                conversation: Conversation::Broadcasts,
                last_seq: from_i64(last.unwrap_or(0)),
                message_count: from_i64(count),
                unread: from_i64(unread),
            });
        }

        rows.sort_by_key(|row| std::cmp::Reverse(row.last_seq));
        rows.truncate(limit);
        Ok(rows)
    }

    // ----- read markers --------------------------------------------------------------

    /// How far the user has read `conversation`: incoming messages with a log position at
    /// or below this value count as read. `0` when nothing was ever marked.
    pub fn read_mark(&self, conversation: &Conversation) -> Result<u64> {
        let mark: Option<i64> = self
            .conn
            .query_row(
                "SELECT read_seq FROM read_marks WHERE scope = ?1",
                params![scope_of(conversation)],
                |r| r.get(0),
            )
            .optional()?;
        Ok(mark.map_or(0, from_i64))
    }

    /// Marks everything in `conversation` up to log position `up_to_seq` as read.
    ///
    /// The marker never moves backwards: marking an older position than the current one
    /// changes nothing, so a late or repeated call cannot make read messages unread again.
    pub fn mark_read(&mut self, conversation: &Conversation, up_to_seq: u64) -> Result<()> {
        self.conn.execute(
            "INSERT INTO read_marks (scope, read_seq) VALUES (?1, ?2)
             ON CONFLICT (scope) DO UPDATE SET read_seq = MAX(read_seq, excluded.read_seq)",
            params![scope_of(conversation), to_i64(up_to_seq)],
        )?;
        Ok(())
    }

    /// Unread incoming messages over all conversations, for a badge.
    pub fn unread_counts(&self, now_ms: u64) -> Result<UnreadCounts> {
        let me = self.local_node()?;
        let now = to_i64(now_ms);
        let direct: i64 = self.conn.query_row(
            "SELECT COALESCE(SUM(m.seq > COALESCE(r.read_seq, 0)), 0)
               FROM messages m LEFT JOIN read_marks r ON r.scope = m.sender
              WHERE m.kind = ?1 AND m.recipient = ?2 AND m.sender <> ?2
                AND m.expires_at_ms > ?3",
            params![Kind::Direct as i64, &me.as_bytes()[..], now],
            |r| r.get(0),
        )?;
        let broadcasts: i64 = self.conn.query_row(
            "SELECT COALESCE(SUM(seq > COALESCE(
                        (SELECT read_seq FROM read_marks WHERE scope = ?4), 0)), 0)
               FROM messages
              WHERE kind = ?1 AND sender <> ?2 AND expires_at_ms > ?3",
            params![
                Kind::Broadcast as i64,
                &me.as_bytes()[..],
                now,
                &BROADCAST_SCOPE[..]
            ],
            |r| r.get(0),
        )?;
        Ok(UnreadCounts {
            direct: from_i64(direct),
            broadcasts: from_i64(broadcasts),
        })
    }

    // ----- notes -----------------------------------------------------------------------

    /// Keeps the text of a direct message this node sent, next to the (sealed) message.
    ///
    /// Returns `false` (and stores nothing) when no message with this id is stored, for
    /// example because the message was refused. The note disappears together with its
    /// message. The text is at most [`MAX_PLAINTEXT_LEN`] bytes.
    pub fn set_plaintext(&mut self, id: &MessageId, plaintext: &[u8]) -> Result<bool> {
        if plaintext.len() > MAX_PLAINTEXT_LEN {
            return Err(StorageError::InvalidArgument(
                "the plaintext is longer than a direct message can carry",
            ));
        }
        let changed = self.conn.execute(
            "INSERT INTO message_notes (id, plaintext)
             SELECT ?1, ?2 WHERE EXISTS (SELECT 1 FROM messages WHERE id = ?1)
             ON CONFLICT (id) DO UPDATE SET plaintext = excluded.plaintext",
            params![&id.as_bytes()[..], plaintext],
        )?;
        Ok(changed > 0)
    }

    /// Records that the recipient acknowledged the message at `now_ms`. The first
    /// acknowledgement counts; later ones keep the original time.
    ///
    /// Returns `false` when no message with this id is stored.
    pub fn mark_delivered(&mut self, id: &MessageId, now_ms: u64) -> Result<bool> {
        let changed = self.conn.execute(
            "INSERT INTO message_notes (id, delivered_at_ms)
             SELECT ?1, ?2 WHERE EXISTS (SELECT 1 FROM messages WHERE id = ?1)
             ON CONFLICT (id) DO UPDATE
                SET delivered_at_ms = COALESCE(delivered_at_ms, excluded.delivered_at_ms)",
            params![&id.as_bytes()[..], to_i64(now_ms)],
        )?;
        Ok(changed > 0)
    }

    /// What is remembered about a message besides its envelope (nothing, by default).
    pub fn note(&self, id: &MessageId) -> Result<MessageNote> {
        let found = self
            .conn
            .query_row(
                "SELECT plaintext, delivered_at_ms FROM message_notes WHERE id = ?1",
                params![&id.as_bytes()[..]],
                |r| Ok((r.get::<_, Option<Vec<u8>>>(0)?, r.get::<_, Option<i64>>(1)?)),
            )
            .optional()?;
        Ok(
            found.map_or_else(MessageNote::default, |(plaintext, delivered)| MessageNote {
                plaintext,
                delivered_at_ms: delivered.map(from_i64),
            }),
        )
    }
}
