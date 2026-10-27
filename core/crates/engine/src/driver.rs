//! The [`Engine`] itself.

use std::path::Path;

use crypto::{ContactCard, Identity};
use mesh::{Event, Mesh, MeshStats, Notification, PeerId};
use proto::{Envelope, Kind, MessageId, NodeId};
use storage::{Conversation, DbKey, Storage, StoredMessage, TrustState, UnreadCounts};

use crate::config::EngineConfig;
use crate::error::{EngineError, Result};
use crate::history::{
    Contact, ConversationPage, ConversationSummary, Direction, HistoryEntry, HistoryPage,
};
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
    ///
    /// A delivery confirmation (`Notification::Delivered`) is also written to the store, so
    /// that the history can still show the message as delivered after a restart.
    pub fn handle(&mut self, now_ms: u64, event: Event) -> Result<Output> {
        let actions = self.mesh.handle(now_ms, event)?;
        let output = Output::from_actions(actions);
        for notification in &output.notifications {
            if let Notification::Delivered { id } = notification {
                self.mesh.storage_mut().mark_delivered(id, now_ms)?;
            }
        }
        Ok(output)
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
    ///
    /// The envelope is sealed to the recipient, so the sender could never read it back.
    /// The engine therefore keeps the text next to the stored message (for as long as the
    /// message lives) and returns it in the history. This local copy lives in the same
    /// database as the message and is protected by the same at-rest encryption.
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
        let (id, output) = self.publish_envelope(now_ms, envelope)?;
        self.remember_text(&id, plaintext)?;
        Ok((id, output))
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
        let (id, output) = self.publish_envelope(now_ms, envelope)?;
        self.remember_text(&id, plaintext)?;
        Ok((id, output))
    }

    /// Keeps the text of a direct message that was just published. Does nothing when the
    /// mesh refused the message (it is not stored then).
    fn remember_text(&mut self, id: &MessageId, plaintext: &[u8]) -> Result<()> {
        self.mesh.storage_mut().set_plaintext(id, plaintext)?;
        Ok(())
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
    /// sent (a direct message this node sent shows the text the user wrote, see
    /// [`Engine::publish_direct_to_card`]). Messages that only pass through this node are
    /// skipped.
    ///
    /// This is the whole log in order, forwards. For a chat screen use
    /// [`Engine::conversation`], which pages one conversation backwards.
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
                if let Some(entry) = self.history_entry(&me, &stored)? {
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

    fn history_entry(
        &self,
        me: &NodeId,
        stored: &StoredMessage,
    ) -> Result<Option<HistoryEntry>> {
        let body = &stored.envelope.body;
        let mut delivered_at_ms = None;
        let (direction, counterpart, text) = match body.kind {
            Kind::Ack => return Ok(None),
            Kind::Broadcast if body.sender == *me => {
                (Direction::Outgoing, None, Some(body.payload.clone()))
            }
            Kind::Broadcast => (
                Direction::Incoming,
                Some(body.sender),
                Some(body.payload.clone()),
            ),
            Kind::Direct if body.sender == *me => {
                // Sealed to the recipient: the text comes from the local copy.
                let note = self.mesh.storage().note(&stored.id)?;
                delivered_at_ms = note.delivered_at_ms;
                (Direction::Outgoing, body.recipient, note.plaintext)
            }
            Kind::Direct if body.recipient == Some(*me) => {
                let plain = self
                    .mesh
                    .identity()
                    .open_direct(&stored.envelope)
                    .ok()
                    .map(|p| p.to_vec());
                (Direction::Incoming, Some(body.sender), plain)
            }
            Kind::Direct => return Ok(None),
        };
        Ok(Some(HistoryEntry {
            seq: stored.seq,
            id: stored.id,
            direction,
            kind: body.kind,
            counterpart,
            body: text,
            created_at_ms: body.created_at_ms,
            received_at_ms: stored.received_at_ms,
            delivered_at_ms,
        }))
    }

    // ----- conversations ----------------------------------------------------------------

    /// The conversations that hold at least one live message, the one with the newest
    /// message first, at most `limit` of them: the public announcements (one conversation)
    /// and one per peer with direct messages in either direction.
    pub fn conversations(&self, limit: usize, now_ms: u64) -> Result<Vec<ConversationSummary>> {
        let rows = self.mesh.storage().conversations(limit, now_ms)?;
        let mut summaries = Vec::with_capacity(rows.len());
        for row in rows {
            let last = self
                .conversation(&row.conversation, None, 1, now_ms)?
                .entries
                .pop();
            summaries.push(ConversationSummary {
                conversation: row.conversation,
                last,
                message_count: row.message_count,
                unread: row.unread,
            });
        }
        Ok(summaries)
    }

    /// One page of a conversation, read **backwards** from its newest message.
    ///
    /// `before_seq` is `None` for the newest page; to go further back pass the
    /// [`ConversationPage::older_before_seq`] of the previous page. The entries of a page
    /// are ordered oldest first, ready to be shown. Unlike [`Engine::history`], a page is
    /// restricted to one conversation, so a busy announcement feed cannot push a chat out
    /// of reach.
    pub fn conversation(
        &self,
        conversation: &Conversation,
        before_seq: Option<u64>,
        limit: usize,
        now_ms: u64,
    ) -> Result<ConversationPage> {
        let storage = self.mesh.storage();
        let read_up_to_seq = storage.read_mark(conversation)?;
        if limit == 0 {
            return Ok(ConversationPage {
                entries: Vec::new(),
                older_before_seq: before_seq,
                read_up_to_seq,
            });
        }
        let me = self.node_id();
        let page = storage.conversation_messages(conversation, before_seq, limit, now_ms)?;
        let mut entries = Vec::with_capacity(page.messages.len());
        for stored in &page.messages {
            if let Some(entry) = self.history_entry(&me, stored)? {
                entries.push(entry);
            }
        }
        let older_before_seq = if page.more_before {
            page.messages.first().map(|stored| stored.seq)
        } else {
            None
        };
        Ok(ConversationPage {
            entries,
            older_before_seq,
            read_up_to_seq,
        })
    }

    /// Marks a conversation as read up to log position `up_to_seq`, or up to the newest
    /// message stored now when it is `None`. The marker never moves backwards. Returns the
    /// marker as it stands afterwards.
    pub fn mark_read(
        &mut self,
        conversation: &Conversation,
        up_to_seq: Option<u64>,
    ) -> Result<u64> {
        let seq = match up_to_seq {
            Some(seq) => seq,
            None => self.mesh.storage().head_seq()?,
        };
        self.mesh.storage_mut().mark_read(conversation, seq)?;
        Ok(self.mesh.storage().read_mark(conversation)?)
    }

    /// Unread incoming messages over all conversations, for a badge.
    pub fn unread_counts(&self, now_ms: u64) -> Result<UnreadCounts> {
        Ok(self.mesh.storage().unread_counts(now_ms)?)
    }
}
