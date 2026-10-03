//! Known peers: last-seen time, trust state and contact card.

use proto::NodeId;
use proto::limits::NODE_ID_LEN;
use rusqlite::{OptionalExtension, Row, params};

use crate::error::{Result, StorageError};
use crate::store::{Storage, from_i64, to_i64};
use crate::types::{Peer, TrustState};

/// Length of an encoded contact card (mirrors `crypto::CONTACT_CARD_LEN`).
const CONTACT_CARD_LEN: usize = 128;

const PEER_COLS: &str = "node_id, first_seen_ms, last_seen_ms, trust, contact_card";

/// A peer row as read from SQLite: (node_id, first_seen, last_seen, trust, contact_card).
type RawPeer = (Vec<u8>, i64, i64, i64, Option<Vec<u8>>);

fn peer_from_row(r: &Row<'_>) -> rusqlite::Result<RawPeer> {
    Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?))
}

fn into_peer(raw: RawPeer) -> Result<Peer> {
    let (id, first, last, trust, card) = raw;
    let node: [u8; NODE_ID_LEN] = id
        .as_slice()
        .try_into()
        .map_err(|_| StorageError::Corrupt("peer id has the wrong length"))?;
    Ok(Peer {
        node_id: NodeId::new(node),
        first_seen_ms: from_i64(first),
        last_seen_ms: from_i64(last),
        trust: TrustState::from_i64(trust).ok_or(StorageError::Corrupt("unknown trust state"))?,
        contact_card: card,
    })
}

impl Storage {
    /// Records that `node` was seen at `now_ms` (creating the peer if needed).
    /// `last_seen_ms` never moves backwards. Trims least-recently-seen `Unknown`
    /// peers when `Config::max_peers` is exceeded.
    pub fn touch_peer(&mut self, node: &NodeId, now_ms: u64) -> Result<()> {
        self.conn.execute(
            "INSERT INTO peers (node_id, first_seen_ms, last_seen_ms) VALUES (?1, ?2, ?2)
             ON CONFLICT (node_id) DO UPDATE
                SET last_seen_ms = MAX(last_seen_ms, excluded.last_seen_ms)",
            params![&node.as_bytes()[..], to_i64(now_ms)],
        )?;
        self.trim_peers()
    }

    fn trim_peers(&mut self) -> Result<()> {
        let count: i64 = self
            .conn
            .query_row("SELECT COUNT(*) FROM peers", [], |r| r.get(0))?;
        let excess = from_i64(count).saturating_sub(self.config.max_peers);
        if excess > 0 {
            self.conn.execute(
                "DELETE FROM peers WHERE node_id IN (
                     SELECT node_id FROM peers WHERE trust = 0
                      ORDER BY last_seen_ms ASC, node_id ASC LIMIT ?1)",
                params![to_i64(excess)],
            )?;
        }
        Ok(())
    }

    /// Reads one peer.
    pub fn get_peer(&self, node: &NodeId) -> Result<Option<Peer>> {
        let raw = self
            .conn
            .query_row(
                &format!("SELECT {PEER_COLS} FROM peers WHERE node_id = ?1"),
                params![&node.as_bytes()[..]],
                peer_from_row,
            )
            .optional()?;
        raw.map(into_peer).transpose()
    }

    /// Peers, most recently seen first, at most `limit`.
    pub fn list_peers(&self, limit: usize) -> Result<Vec<Peer>> {
        let mut stmt = self.conn.prepare(&format!(
            "SELECT {PEER_COLS} FROM peers ORDER BY last_seen_ms DESC, node_id ASC LIMIT ?1"
        ))?;
        let raws = stmt
            .query_map(params![to_i64(limit as u64)], peer_from_row)?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        raws.into_iter().map(into_peer).collect()
    }

    /// Sets the trust state, creating the peer if it is not known yet.
    pub fn set_trust(&mut self, node: &NodeId, trust: TrustState, now_ms: u64) -> Result<()> {
        self.conn.execute(
            "INSERT INTO peers (node_id, first_seen_ms, last_seen_ms, trust) VALUES (?1, ?2, ?2, ?3)
             ON CONFLICT (node_id) DO UPDATE SET trust = excluded.trust",
            params![&node.as_bytes()[..], to_i64(now_ms), trust as i64],
        )?;
        self.trim_peers()
    }

    /// Stores the peer's encoded contact card (opaque here; exactly 128 bytes),
    /// creating the peer if needed. The card must already have been verified by
    /// `crypto::ContactCard::from_bytes`.
    pub fn set_contact_card(&mut self, node: &NodeId, card: &[u8], now_ms: u64) -> Result<()> {
        if card.len() != CONTACT_CARD_LEN {
            return Err(StorageError::InvalidArgument(
                "contact card must be exactly 128 bytes",
            ));
        }
        self.conn.execute(
            "INSERT INTO peers (node_id, first_seen_ms, last_seen_ms, contact_card)
             VALUES (?1, ?2, ?2, ?3)
             ON CONFLICT (node_id) DO UPDATE SET contact_card = excluded.contact_card",
            params![&node.as_bytes()[..], to_i64(now_ms), card],
        )?;
        self.trim_peers()
    }

    /// Removes a peer. Returns whether it existed.
    pub fn forget_peer(&mut self, node: &NodeId) -> Result<bool> {
        let n = self.conn.execute(
            "DELETE FROM peers WHERE node_id = ?1",
            params![&node.as_bytes()[..]],
        )?;
        Ok(n > 0)
    }
}
