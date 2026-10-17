//! The [`Engine`] itself.

use std::path::Path;

use crypto::{ContactCard, Identity};
use mesh::{Event, Mesh, MeshStats, PeerId};
use proto::{Envelope, Kind, MessageId, NodeId};
use storage::{DbKey, Storage, StoredMessage, TrustState};

use crate::config::EngineConfig;
use crate::error::{EngineError, Result};
use crate::history::{Contact, Direction, HistoryEntry, HistoryPage};
use crate::output::Output;

/// How many log entries are examined per storage query while building a history page.
const SCAN_PAGE: usize = 64;

/// A node of the Crisis Network. See the crate documentation.
pub struct Engine {
    mesh: Mesh,
    default_lifetime_secs: u32,
}

impl Engine {
    /// Builds an engine on top of an existing store.
    ///
    /// The store must have been opened with `local_node` set to this identity's node
    /// id; use [`Engine::in_memory`] or [`Engine::open`] to have that done for you.
    pub fn with_storage(
        config: EngineConfig,
        identity: Identity,
        storage: Storage,
        now_ms: u64,
    ) -> Result<Self> {
        config.validate()?;
        let default_lifetime_secs = config.default_lifetime_secs;
        let mesh = Mesh::new(config.mesh, identity, storage, now_ms)?;
        Ok(Self {
            mesh,
            default_lifetime_secs,
        })
    }

    /// Builds an engine with a private in-memory store (tests and simulation).
    pub fn in_memory(config: EngineConfig, identity: Identity, now_ms: u64) -> Result<Self> {
        let storage = Storage::open_in_memory(Self::storage_config(&config, &identity))?;
        Self::with_storage(config, identity, storage, now_ms)
    }

    /// Builds an engine on a database file, optionally encrypted with `key`
    /// (see [`storage::Storage::open`] for what encryption requires).
    pub fn open(
        config: EngineConfig,
        identity: Identity,
        path: &Path,
        key: Option<&DbKey>,
        now_ms: u64,
    ) -> Result<Self> {
        let storage = Storage::open(path, Self::storage_config(&config, &identity), key)?;
        Self::with_storage(config, identity, storage, now_ms)
    }

    fn storage_config(config: &EngineConfig, identity: &Identity) -> storage::Config {
        storage::Config {
            local_node: Some(identity.node_id()),
            ..config.storage.clone()
        }
    }

    // ----- identity ------------------------------------------------------------

    /// This node's address.
    pub fn node_id(&self) -> NodeId {
        self.mesh.identity().node_id()
    }

    /// This node's shareable, self-signed contact card.
    pub fn contact_card(&self) -> ContactCard {
        self.mesh.identity().contact_card()
    }

    /// This node's identity.
    pub fn identity(&self) -> &Identity {
        self.mesh.identity()
    }

    // ----- event loop ------------------------------------------------------------

    /// Processes one event at time `now_ms`.
    pub fn handle(&mut self, now_ms: u64, event: Event) -> Result<Output> {
        let actions = self.mesh.handle(now_ms, event)?;
        Ok(Output::from_actions(actions))
    }

    /// Shorthand for handling [`Event::Tick`].
    pub fn tick(&mut self, now_ms: u64) -> Result<Output> {
        self.handle(now_ms, Event::Tick)
    }

    /// Shorthand for handling [`Event::PeerSeen`].
    pub fn peer_seen(&mut self, now_ms: u64, peer: PeerId, link_mtu: usize) -> Result<Output> {
        self.handle(now_ms, Event::PeerSeen { peer, link_mtu })
    }

    /// Shorthand for handling [`Event::PeerLost`].
    pub fn peer_lost(&mut self, now_ms: u64, peer: PeerId) -> Result<Output> {
        self.handle(now_ms, Event::PeerLost { peer })
    }

    /// Shorthand for handling [`Event::PacketReceived`].
    pub fn packet_received(&mut self, now_ms: u64, peer: PeerId, bytes: Vec<u8>) -> Result<Output> {
        self.handle(now_ms, Event::PacketReceived { peer, bytes })
    }

    /// The neighbours currently connected.
    pub fn connected_peers(&self) -> Vec<PeerId> {
        self.mesh.connected_peers()
    }

    /// Counters describing what the mesh has done so far.
    pub fn stats(&self) -> &MeshStats {
        self.mesh.stats()
    }

    /// The message store (read-only).
    pub fn storage(&self) -> &Storage {
        self.mesh.storage()
    }

    // ----- publishing ------------------------------------------------------------

    /// Publishes a signed, unencrypted announcement with the default lifetime.
    ///
    /// Returns the message id and the resulting output; if the mesh refused the
    /// message, the output contains a `PublishRejected` notification.
    pub fn publish_broadcast(
        &mut self,
        now_ms: u64,
        payload: &[u8],
    ) -> Result<(MessageId, Output)> {
        let lifetime = self.default_lifetime_secs;
        self.publish_broadcast_with(now_ms, payload, lifetime)
    }

    /// Like [`Engine::publish_broadcast`] with an explicit lifetime in seconds.
    pub fn publish_broadcast_with(
        &mut self,
        now_ms: u64,
        payload: &[u8],
        lifetime_secs: u32,
    ) -> Result<(MessageId, Output)> {
        let envelope = self
            .mesh
            .identity()
            .broadcast(payload, now_ms, lifetime_secs)?;
        self.publish_envelope(now_ms, envelope)
    }

    /// Publishes a message encrypted for `to`, whose contact card must be known
    /// (see [`Engine::add_contact`]).
    pub fn publish_direct(
        &mut self,
        now_ms: u64,
        to: &NodeId,
        plaintext: &[u8],
    ) -> Result<(MessageId, Output)> {
        let card = self
            .contact_card_of(to)?
            .ok_or(EngineError::UnknownContact)?;
        self.publish_direct_to_card(now_ms, &card, plaintext)
    }

    /// Publishes a message encrypted for the owner of `card`.
    pub fn publish_direct_to_card(
        &mut self,
        now_ms: u64,
        card: &ContactCard,
        plaintext: &[u8],
    ) -> Result<(MessageId, Output)> {
        let lifetime = self.default_lifetime_secs;
        let envelope = self
            .mesh
            .identity()
            .direct(card, plaintext, now_ms, lifetime)?;
        self.publish_envelope(now_ms, envelope)
    }

    /// Like [`Engine::publish_direct_to_card`], but with a caller-supplied ephemeral key
    /// so that the message is reproducible. **For simulation and tests only**: never
    /// reuse one `ephemeral` for two different messages.
    pub fn publish_direct_to_card_with_ephemeral(
        &mut self,
        now_ms: u64,
        card: &ContactCard,
        plaintext: &[u8],
        ephemeral: &[u8; 32],
    ) -> Result<(MessageId, Output)> {
        let lifetime = self.default_lifetime_secs;
        let envelope = self
            .mesh
            .identity()
            .direct_with_ephemeral(card, plaintext, now_ms, lifetime, ephemeral)?;
        self.publish_envelope(now_ms, envelope)
    }

    fn publish_envelope(&mut self, now_ms: u64, envelope: Envelope) -> Result<(MessageId, Output)> {
        let id = envelope.message_id()?;
        let output = self.handle(
            now_ms,
            Event::Publish {
                envelope: Box::new(envelope),
            },
        )?;
        Ok((id, output))
    }

    // ----- contacts ---------------------------------------------------------------

    /// Verifies a contact card received out of band (for example scanned from a QR
    /// code) and remembers it. Returns the verified card.
    pub fn add_contact(&mut self, now_ms: u64, card_bytes: &[u8]) -> Result<ContactCard> {
        let card =
            ContactCard::from_bytes(card_bytes).map_err(|_| EngineError::InvalidContactCard)?;
        self.mesh
            .storage_mut()
            .set_contact_card(&card.node_id(), card_bytes, now_ms)?;
        Ok(card)
    }

    fn contact_card_of(&self, node: &NodeId) -> Result<Option<ContactCard>> {
        let Some(peer) = self.mesh.storage().get_peer(node)? else {
            return Ok(None);
        };
        let Some(bytes) = peer.contact_card else {
            return Ok(None);
        };
        let card = ContactCard::from_bytes(&bytes).map_err(|_| EngineError::InvalidContactCard)?;
        if card.node_id() != *node {
            return Err(EngineError::InvalidContactCard);
        }
        Ok(Some(card))
    }

    /// Known peers, most recently recorded first.
    pub fn contacts(&self, limit: usize) -> Result<Vec<Contact>> {
        let peers = self.mesh.storage().list_peers(limit)?;
        Ok(peers
            .into_iter()
            .map(|peer| Contact {
                card: peer
                    .contact_card
                    .as_deref()
                    .and_then(|bytes| ContactCard::from_bytes(bytes).ok()),
                node_id: peer.node_id,
                trust: peer.trust,
                first_seen_ms: peer.first_seen_ms,
                last_seen_ms: peer.last_seen_ms,
            })
            .collect())
    }

    /// Marks a node as verified, unknown or blocked. Messages from a blocked node are
    /// dropped on arrival and never relayed.
    pub fn set_trust(&mut self, now_ms: u64, node: &NodeId, trust: TrustState) -> Result<()> {
        self.mesh.storage_mut().set_trust(node, trust, now_ms)?;
        Ok(())
    }

    // ----- history ------------------------------------------------------------------

    /// Reads the conversation history after log position `after_seq`: broadcasts,
    /// direct messages addressed to this node (decrypted) and everything this node
    /// sent. Messages that only pass through this node are skipped.
    ///
    /// At most `limit` entries are returned. Continue with
    /// [`HistoryPage::next_after_seq`].
    pub fn history(&self, after_seq: u64, limit: usize, now_ms: u64) -> Result<HistoryPage> {
        if limit == 0 {
            return Ok(HistoryPage {
                entries: Vec::new(),
                next_after_seq: after_seq,
            });
        }
        let me = self.node_id();
        let mut entries = Vec::new();
        let mut cursor = after_seq;
        'scan: loop {
            let page = self.mesh.storage().list_after(cursor, SCAN_PAGE, now_ms)?;
            if page.is_empty() {
                break;
            }
            for stored in page {
                cursor = stored.seq;
                if let Some(entry) = self.history_entry(&me, &stored) {
                    entries.push(entry);
                    if entries.len() >= limit {
                        break 'scan;
                    }
                }
            }
        }
        Ok(HistoryPage {
            entries,
            next_after_seq: cursor,
        })
    }

    fn history_entry(&self, me: &NodeId, stored: &StoredMessage) -> Option<HistoryEntry> {
        let body = &stored.envelope.body;
        let (direction, counterpart, text) = match body.kind {
            Kind::Ack => return None,
            Kind::Broadcast if body.sender == *me => {
                (Direction::Outgoing, None, Some(body.payload.clone()))
            }
            Kind::Broadcast => (
                Direction::Incoming,
                Some(body.sender),
                Some(body.payload.clone()),
            ),
            Kind::Direct if body.sender == *me => (Direction::Outgoing, body.recipient, None),
            Kind::Direct if body.recipient == Some(*me) => {
                let plain = self
                    .mesh
                    .identity()
                    .open_direct(&stored.envelope)
                    .ok()
                    .map(|p| p.to_vec());
                (Direction::Incoming, Some(body.sender), plain)
            }
            Kind::Direct => return None,
        };
        Some(HistoryEntry {
            seq: stored.seq,
            id: stored.id,
            direction,
            kind: body.kind,
            counterpart,
            body: text,
            created_at_ms: body.created_at_ms,
            received_at_ms: stored.received_at_ms,
        })
    }
}
