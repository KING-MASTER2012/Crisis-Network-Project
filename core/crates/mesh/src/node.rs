//! The [`Mesh`] state machine.

use std::collections::{BTreeMap, VecDeque};

use crypto::{Identity, verify_envelope};
use proto::link::{MAX_IDS_PER_PACKET, Packet};
use proto::{Envelope, Fragment, Frame, Kind, MessageId, NodeId, split};
use storage::{InsertOutcome, Storage, TrustState};

use crate::config::MeshConfig;
use crate::error::{MeshError, Result};
use crate::event::{
    Action, DisconnectReason, Event, MeshStats, Notification, PeerId, PublishError,
};
use crate::reassembly::{Accepted, Reassembler, Rejection};
use crate::util::{BoundedIdSet, HopTable, Penalty, TokenBucket};

/// The smallest link packet size the mesh accepts, in bytes. It guarantees room for
/// one id in an `Inv`/`Want` packet and a useful amount of data in a fragment.
pub const MIN_LINK_MTU: usize = 64;

/// Other peers remembered as alternative sources for a requested message.
const MAX_ALTERNATES: usize = 3;

/// Per-neighbour state.
struct PeerState {
    mtu: usize,
    /// Everything in our log up to this sequence number has been offered to the peer.
    sync_cursor: u64,
    next_resync_ms: u64,
    ctrl: VecDeque<Vec<u8>>,
    data: VecDeque<Vec<u8>>,
    knows: BoundedIdSet,
    packets: TokenBucket,
    bytes: TokenBucket,
    penalty: Penalty,
}

impl PeerState {
    fn new(config: &MeshConfig, mtu: usize, now_ms: u64) -> Self {
        Self {
            mtu,
            sync_cursor: 0,
            next_resync_ms: now_ms.saturating_add(config.resync_interval_ms),
            ctrl: VecDeque::new(),
            data: VecDeque::new(),
            knows: BoundedIdSet::new(config.peer_known_ids),
            packets: TokenBucket::new(config.packet_burst, config.packet_rate_per_sec, now_ms),
            bytes: TokenBucket::new(config.byte_burst, config.byte_rate_per_sec, now_ms),
            penalty: Penalty::new(now_ms),
        }
    }
}

/// A message we asked for and have not received yet.
struct Inflight {
    /// The peer currently asked.
    peer: PeerId,
    /// Other peers that announced the same message.
    alternates: Vec<PeerId>,
    deadline_ms: u64,
    attempts: u32,
}

/// How many ids fit one `Inv`/`Want` packet on a link with this MTU.
fn ids_per_packet(mtu: usize) -> usize {
    (mtu.saturating_sub(2) / 32).clamp(1, MAX_IDS_PER_PACKET)
}

/// The mesh state machine. See the crate documentation.
pub struct Mesh {
    config: MeshConfig,
    identity: Identity,
    storage: Storage,
    peers: BTreeMap<PeerId, PeerState>,
    banned: BTreeMap<PeerId, u64>,
    reassembler: Reassembler,
    inflight: BTreeMap<MessageId, Inflight>,
    hops: HopTable,
    rejected: BoundedIdSet,
    sender_limits: BTreeMap<NodeId, TokenBucket>,
    head_seq: u64,
    next_purge_ms: u64,
    last_timer_ms: Option<u64>,
    stats: MeshStats,
}

impl Mesh {
    /// Creates the mesh.
    ///
    /// The store must have been opened with `local_node` set to this identity's
    /// node id, so that the node's own messages are protected from eviction.
    pub fn new(
        config: MeshConfig,
        identity: Identity,
        storage: Storage,
        now_ms: u64,
    ) -> Result<Self> {
        config.validate()?;
        if storage.config().local_node != Some(identity.node_id()) {
            return Err(MeshError::LocalNodeMismatch);
        }
        let head_seq = storage.head_seq()?;
        Ok(Self {
            reassembler: Reassembler::new(
                config.reassembly_timeout_ms,
                config.max_transfers_per_peer,
                config.max_reassembly_bytes_per_peer,
                config.max_reassembly_bytes_total,
            ),
            hops: HopTable::new(config.max_tracked_hops, config.fallback_hops),
            rejected: BoundedIdSet::new(config.max_rejected_ids),
            next_purge_ms: now_ms.saturating_add(config.purge_interval_ms),
            config,
            identity,
            storage,
            peers: BTreeMap::new(),
            banned: BTreeMap::new(),
            inflight: BTreeMap::new(),
            sender_limits: BTreeMap::new(),
            head_seq,
            last_timer_ms: None,
            stats: MeshStats::default(),
        })
    }

    /// This node's identity.
    pub fn identity(&self) -> &Identity {
        &self.identity
    }

    /// The message store.
    pub fn storage(&self) -> &Storage {
        &self.storage
    }

    /// The message store, mutably (for example to block a sender or edit peers).
    pub fn storage_mut(&mut self) -> &mut Storage {
        &mut self.storage
    }

    /// The active configuration.
    pub fn config(&self) -> &MeshConfig {
        &self.config
    }

    /// Counters describing what the mesh has done so far.
    pub fn stats(&self) -> &MeshStats {
        &self.stats
    }

    /// The neighbours currently connected, in a fixed order.
    pub fn connected_peers(&self) -> Vec<PeerId> {
        self.peers.keys().copied().collect()
    }

    /// Processes one event at time `now_ms` and returns what the platform must do.
    ///
    /// Misbehaving peers never cause an error: their packets are counted,
    /// penalised and dropped. An `Err` means a local failure (storage, crypto).
    pub fn handle(&mut self, now_ms: u64, event: Event) -> Result<Vec<Action>> {
        let mut out = Vec::new();
        let is_tick = matches!(event, Event::Tick);
        match event {
            Event::PeerSeen { peer, link_mtu } => {
                self.on_peer_seen(now_ms, peer, link_mtu, &mut out);
            }
            Event::PeerLost { peer } => self.on_peer_lost(now_ms, peer, &mut out),
            Event::PacketReceived { peer, bytes } => {
                self.on_packet(now_ms, peer, &bytes, &mut out)?;
            }
            Event::Tick => self.on_tick(now_ms)?,
            Event::Publish { envelope } => self.on_publish(now_ms, *envelope, &mut out)?,
        }
        self.announce(now_ms)?;
        self.flush(&mut out);
        self.schedule(now_ms, is_tick, &mut out);
        Ok(out)
    }

    // ----- peers ---------------------------------------------------------------

    fn on_peer_seen(&mut self, now_ms: u64, peer: PeerId, link_mtu: usize, out: &mut Vec<Action>) {
        if self.banned.get(&peer).is_some_and(|until| *until > now_ms) {
            out.push(Action::Disconnect {
                peer,
                reason: DisconnectReason::Banned,
            });
            return;
        }
        if link_mtu < MIN_LINK_MTU {
            out.push(Action::Disconnect {
                peer,
                reason: DisconnectReason::MtuTooSmall,
            });
            return;
        }
        if !self.peers.contains_key(&peer) && self.peers.len() >= self.config.max_peers {
            out.push(Action::Disconnect {
                peer,
                reason: DisconnectReason::TooManyPeers,
            });
            return;
        }
        if let Some(state) = self.peers.get_mut(&peer) {
            if link_mtu < state.mtu {
                // Fragments queued for the old, larger link would not fit any more. The
                // peer's reassembly times out and the message is requested again later.
                state.data.clear();
            }
            state.ctrl.retain(|packet| packet.len() <= link_mtu);
            state.mtu = link_mtu;
        } else {
            let state = PeerState::new(&self.config, link_mtu, now_ms);
            self.peers.insert(peer, state);
            out.push(Action::Notify(Notification::PeerConnected { peer }));
        }
    }

    fn on_peer_lost(&mut self, now_ms: u64, peer: PeerId, out: &mut Vec<Action>) {
        if self.drop_peer_state(now_ms, peer) {
            out.push(Action::Notify(Notification::PeerDisconnected { peer }));
        }
    }

    /// Forgets a peer and re-routes the requests that were waiting on it.
    /// Returns whether the peer was connected.
    fn drop_peer_state(&mut self, now_ms: u64, peer: PeerId) -> bool {
        let existed = self.peers.remove(&peer).is_some();
        self.reassembler.drop_peer(peer);
        for entry in self.inflight.values_mut() {
            entry.alternates.retain(|p| *p != peer);
        }
        let orphaned: Vec<MessageId> = self
            .inflight
            .iter()
            .filter(|(_, entry)| entry.peer == peer)
            .map(|(id, _)| *id)
            .collect();
        for id in orphaned {
            self.retry_inflight(now_ms, id);
        }
        existed
    }

    fn penalize(&mut self, now_ms: u64, peer: PeerId, points: u32, out: &mut Vec<Action>) {
        let decay = self.config.penalty_decay_per_sec;
        let threshold = self.config.ban_threshold;
        let Some(state) = self.peers.get_mut(&peer) else {
            return;
        };
        let total = state.penalty.add(now_ms, points, decay);
        if total >= threshold {
            self.ban(now_ms, peer, out);
        }
    }

    fn ban(&mut self, now_ms: u64, peer: PeerId, out: &mut Vec<Action>) {
        let until = now_ms.saturating_add(self.config.ban_duration_ms);
        if !self.banned.contains_key(&peer) && self.banned.len() >= self.config.max_banned_peers {
            // Forget the ban that would end first; the table stays bounded.
            let soonest = self
                .banned
                .iter()
                .min_by_key(|(_, ends_ms)| **ends_ms)
                .map(|(banned_peer, _)| *banned_peer);
            if let Some(forgotten) = soonest {
                self.banned.remove(&forgotten);
            }
        }
        self.banned.insert(peer, until);
        self.stats.peers_banned += 1;
        self.drop_peer_state(now_ms, peer);
        out.push(Action::Disconnect {
            peer,
            reason: DisconnectReason::Banned,
        });
        out.push(Action::Notify(Notification::PeerBanned { peer }));
    }

    // ----- inbound packets -------------------------------------------------------

    fn on_packet(
        &mut self,
        now_ms: u64,
        peer: PeerId,
        bytes: &[u8],
        out: &mut Vec<Action>,
    ) -> Result<()> {
        let Some(state) = self.peers.get_mut(&peer) else {
            self.stats.unknown_peer_packets += 1;
            return Ok(());
        };
        self.stats.packets_received += 1;
        let within_limits =
            state.packets.try_take(now_ms, 1) && state.bytes.try_take(now_ms, bytes.len() as u64);
        if !within_limits {
            self.stats.rate_limited += 1;
            let points = self.config.penalty_rate_limited;
            self.penalize(now_ms, peer, points, out);
            return Ok(());
        }
        match Packet::decode(bytes) {
            Err(_) => {
                self.stats.malformed_packets += 1;
                let points = self.config.penalty_malformed;
                self.penalize(now_ms, peer, points, out);
            }
            Ok(Packet::Inv(ids)) => self.on_inv(now_ms, peer, &ids)?,
            Ok(Packet::Want(ids)) => self.on_want(now_ms, peer, &ids)?,
            Ok(Packet::Fragment(fragment)) => self.on_fragment(now_ms, peer, fragment, out)?,
        }
        Ok(())
    }

    /// The peer announces messages it holds: request the ones we lack.
    fn on_inv(&mut self, now_ms: u64, peer: PeerId, ids: &[MessageId]) -> Result<()> {
        if let Some(state) = self.peers.get_mut(&peer) {
            for id in ids {
                state.knows.insert(*id);
            }
        }
        let missing = self.storage.missing(ids, now_ms)?;
        let mut wanted: Vec<MessageId> = Vec::new();
        let mut asked_of_peer = self
            .inflight
            .values()
            .filter(|entry| entry.peer == peer)
            .count();
        for id in missing {
            if self.rejected.contains(&id) {
                continue;
            }
            if let Some(entry) = self.inflight.get_mut(&id) {
                // Already requested elsewhere: remember this peer as a fallback source.
                if entry.peer != peer
                    && !entry.alternates.contains(&peer)
                    && entry.alternates.len() < MAX_ALTERNATES
                {
                    entry.alternates.push(peer);
                }
                continue;
            }
            // A peer may announce ids it will never deliver; its share of the request
            // table is limited so that it cannot starve everybody else.
            if self.inflight.len() >= self.config.max_inflight
                || asked_of_peer >= self.config.max_inflight_per_peer
            {
                self.stats.inflight_full += 1;
                continue;
            }
            asked_of_peer += 1;
            self.inflight.insert(
                id,
                Inflight {
                    peer,
                    alternates: Vec::new(),
                    deadline_ms: now_ms.saturating_add(self.config.want_timeout_ms),
                    attempts: 0,
                },
            );
            wanted.push(id);
        }
        // A request must fit the link like any other packet, however many ids the peer
        // squeezed into its announcement.
        let per_packet = self
            .peers
            .get(&peer)
            .map_or(1, |state| ids_per_packet(state.mtu));
        for chunk in wanted.chunks(per_packet) {
            self.send_ctrl(peer, &Packet::Want(chunk.to_vec()));
        }
        Ok(())
    }

    /// The peer asks for messages: queue them as fragments.
    fn on_want(&mut self, now_ms: u64, peer: PeerId, ids: &[MessageId]) -> Result<()> {
        for id in ids {
            let Some(stored) = self.storage.get(id, now_ms)? else {
                continue;
            };
            let frame = Frame {
                hops_left: self.hops.arrived(id),
                envelope: stored.envelope,
            };
            // `forwarded` is `None` once the hop budget is spent.
            if let Some(outgoing) = frame.forwarded() {
                self.send_frame(peer, &outgoing);
            }
        }
        Ok(())
    }

    fn on_fragment(
        &mut self,
        now_ms: u64,
        peer: PeerId,
        fragment: Fragment,
        out: &mut Vec<Action>,
    ) -> Result<()> {
        match self.reassembler.accept(peer, fragment, now_ms) {
            Ok(Accepted::Pending) => Ok(()),
            Ok(Accepted::Complete(frame)) => self.on_frame(now_ms, peer, frame, out),
            Err(Rejection::Memory) => {
                self.stats.fragments_rejected += 1;
                Ok(())
            }
            Err(Rejection::Conflict | Rejection::Invalid) => {
                self.stats.fragments_rejected += 1;
                let points = self.config.penalty_bad_fragment;
                self.penalize(now_ms, peer, points, out);
                Ok(())
            }
        }
    }

    /// A complete frame arrived from `from`.
    ///
    /// Order matters: cheap checks first, the signature next, and only then
    /// anything that costs the sender's rate budget or touches the store.
    fn on_frame(
        &mut self,
        now_ms: u64,
        from: PeerId,
        frame: Frame,
        out: &mut Vec<Action>,
    ) -> Result<()> {
        let hops_left = frame.hops_left;
        let envelope = frame.envelope;
        let id = envelope.message_id()?;

        if let Some(state) = self.peers.get_mut(&from) {
            state.knows.insert(id);
        }

        if self.storage.missing(&[id], now_ms)?.is_empty() {
            self.inflight.remove(&id);
            self.stats.duplicates += 1;
            return Ok(());
        }

        let sender = envelope.body.sender;
        let blocked = self
            .storage
            .get_peer(&sender)?
            .is_some_and(|p| p.trust == TrustState::Blocked);
        if blocked {
            self.inflight.remove(&id);
            self.rejected.insert(id);
            self.stats.blocked_dropped += 1;
            return Ok(());
        }

        // The message id does not cover the signature, so anybody can send a frame with
        // the id of a message we are waiting for and a forged signature. The pending
        // request must survive such a frame: it is cleared only after verification.
        if verify_envelope(&envelope).is_err() {
            self.stats.invalid_signatures += 1;
            let points = self.config.penalty_invalid_signature;
            self.penalize(now_ms, from, points, out);
            return Ok(());
        }
        self.inflight.remove(&id);

        if !self.sender_allowed(now_ms, &sender) {
            self.stats.sender_rate_limited += 1;
            return Ok(());
        }

        match self.storage.insert(&envelope, now_ms)? {
            InsertOutcome::Inserted { seq, .. } => {
                self.head_seq = self.head_seq.max(seq);
                let arrived = hops_left.min(self.config.max_hops);
                self.hops.set(id, arrived);
                self.stats.frames_accepted += 1;
                self.after_insert(now_ms, &envelope, id, out)?;
            }
            InsertOutcome::Duplicate => self.stats.duplicates += 1,
            InsertOutcome::Tombstoned => self.stats.tombstoned += 1,
            InsertOutcome::QuotaExceeded => self.stats.quota_rejected += 1,
        }
        Ok(())
    }

    /// A new message was stored: tell the application and acknowledge direct messages.
    fn after_insert(
        &mut self,
        now_ms: u64,
        envelope: &Envelope,
        id: MessageId,
        out: &mut Vec<Action>,
    ) -> Result<()> {
        let me = self.identity.node_id();
        let body = &envelope.body;
        match body.kind {
            Kind::Broadcast => out.push(Action::Notify(Notification::MessageReceived {
                id,
                sender: body.sender,
                kind: Kind::Broadcast,
                body: body.payload.clone(),
            })),
            Kind::Direct if body.recipient == Some(me) => {
                match self.identity.open_direct(envelope) {
                    Ok(plain) => {
                        out.push(Action::Notify(Notification::MessageReceived {
                            id,
                            sender: body.sender,
                            kind: Kind::Direct,
                            body: plain.to_vec(),
                        }));
                        self.send_ack(now_ms, &body.sender, &id)?;
                    }
                    Err(_) => self.stats.undecryptable += 1,
                }
            }
            Kind::Ack if body.recipient == Some(me) => self.on_ack(now_ms, envelope, out)?,
            Kind::Direct | Kind::Ack => {}
        }
        Ok(())
    }

    /// An Ack addressed to us. It only counts if it comes from the recipient of a
    /// message that we sent.
    fn on_ack(&mut self, now_ms: u64, ack: &Envelope, out: &mut Vec<Action>) -> Result<()> {
        let Ok(raw) = <[u8; 32]>::try_from(ack.body.payload.as_slice()) else {
            return Ok(());
        };
        let acked = MessageId::new(raw);
        let Some(original) = self.storage.get(&acked, now_ms)? else {
            return Ok(());
        };
        let me = self.identity.node_id();
        let body = &original.envelope.body;
        if body.sender == me && body.recipient == Some(ack.body.sender) {
            out.push(Action::Notify(Notification::Delivered { id: acked }));
        }
        Ok(())
    }

    fn send_ack(&mut self, now_ms: u64, to: &NodeId, acked: &MessageId) -> Result<()> {
        let ack = self
            .identity
            .ack(to, acked, now_ms, self.config.ack_lifetime_secs)?;
        let id = ack.message_id()?;
        if matches!(
            self.publish_local(now_ms, &ack, id)?,
            InsertOutcome::Inserted { .. }
        ) {
            self.stats.acks_sent += 1;
        }
        Ok(())
    }

    /// Whether `sender` is still within its message budget.
    fn sender_allowed(&mut self, now_ms: u64, sender: &NodeId) -> bool {
        let tracked = self.sender_limits.contains_key(sender);
        if !tracked {
            if self.sender_limits.len() >= self.config.max_tracked_senders {
                let idlest = self
                    .sender_limits
                    .iter()
                    .min_by_key(|(_, bucket)| bucket.last_ms)
                    .map(|(node, _)| *node);
                if let Some(node) = idlest {
                    self.sender_limits.remove(&node);
                }
            }
            self.sender_limits.insert(
                *sender,
                TokenBucket::new(
                    self.config.sender_burst,
                    self.config.sender_rate_per_sec,
                    now_ms,
                ),
            );
        }
        self.sender_limits
            .get_mut(sender)
            .is_some_and(|bucket| bucket.try_take(now_ms, 1))
    }

    // ----- local publishing -----------------------------------------------------

    fn on_publish(&mut self, now_ms: u64, envelope: Envelope, out: &mut Vec<Action>) -> Result<()> {
        let id = envelope.message_id().ok();
        let ours = envelope.body.sender == self.identity.node_id();
        let valid = ours && verify_envelope(&envelope).is_ok();
        let (Some(id), true) = (id, valid) else {
            out.push(Action::Notify(Notification::PublishRejected {
                id,
                reason: PublishError::Invalid,
            }));
            return Ok(());
        };
        let reason = match self.publish_local(now_ms, &envelope, id)? {
            InsertOutcome::Inserted { .. } => {
                out.push(Action::Notify(Notification::Published { id }));
                return Ok(());
            }
            InsertOutcome::Duplicate => PublishError::Duplicate,
            InsertOutcome::Tombstoned => PublishError::Tombstoned,
            InsertOutcome::QuotaExceeded => PublishError::QuotaExceeded,
        };
        out.push(Action::Notify(Notification::PublishRejected {
            id: Some(id),
            reason,
        }));
        Ok(())
    }

    /// Stores a message created on this node. Propagation happens through the
    /// normal announcement path.
    fn publish_local(
        &mut self,
        now_ms: u64,
        envelope: &Envelope,
        id: MessageId,
    ) -> Result<InsertOutcome> {
        let outcome = self.storage.insert(envelope, now_ms)?;
        if let InsertOutcome::Inserted { seq, .. } = outcome {
            self.head_seq = self.head_seq.max(seq);
            // A local message "arrives" with one hop more than it is sent with.
            let arrived = self.config.default_hops.saturating_add(1);
            self.hops.set(id, arrived);
        }
        Ok(outcome)
    }

    // ----- requests in flight -------------------------------------------------

    /// A request timed out or its peer vanished: ask the next candidate, or give up.
    fn retry_inflight(&mut self, now_ms: u64, id: MessageId) {
        let Some(mut entry) = self.inflight.remove(&id) else {
            return;
        };
        entry.attempts += 1;
        if entry.attempts >= self.config.max_want_attempts {
            self.stats.want_gave_up += 1;
            return;
        }
        let mut candidates = std::mem::take(&mut entry.alternates);
        candidates.push(entry.peer);
        candidates.retain(|p| self.peers.contains_key(p));
        if candidates.is_empty() {
            return;
        }
        let next = candidates.remove(0);
        entry.peer = next;
        entry.alternates = candidates;
        entry.deadline_ms = now_ms.saturating_add(self.config.want_timeout_ms);
        self.inflight.insert(id, entry);
        self.send_ctrl(next, &Packet::Want(vec![id]));
    }

    // ----- timers ----------------------------------------------------------------

    fn on_tick(&mut self, now_ms: u64) -> Result<()> {
        self.stats.expired_transfers += self.reassembler.expire(now_ms);
        if now_ms >= self.next_purge_ms {
            self.storage.purge_expired(now_ms)?;
            self.next_purge_ms = now_ms.saturating_add(self.config.purge_interval_ms);
        }
        let due: Vec<MessageId> = self
            .inflight
            .iter()
            .filter(|(_, entry)| entry.deadline_ms <= now_ms)
            .map(|(id, _)| *id)
            .collect();
        for id in due {
            self.retry_inflight(now_ms, id);
        }
        self.banned.retain(|_, until| *until > now_ms);
        Ok(())
    }

    /// Asks the platform for the next tick, and only repeats the request when it changed.
    fn schedule(&mut self, now_ms: u64, force: bool, out: &mut Vec<Action>) {
        let mut next = self.next_purge_ms;
        if let Some(t) = self.reassembler.next_expiry_ms() {
            next = next.min(t);
        }
        if let Some(t) = self.inflight.values().map(|entry| entry.deadline_ms).min() {
            next = next.min(t);
        }
        let resync = self.config.resync_interval_ms;
        let mut busy = false;
        for state in self.peers.values() {
            if resync > 0 {
                next = next.min(state.next_resync_ms);
            }
            if !state.ctrl.is_empty() || !state.data.is_empty() || state.sync_cursor < self.head_seq
            {
                busy = true;
            }
        }
        if busy {
            next = next.min(now_ms.saturating_add(self.config.flush_interval_ms));
        }
        let at_ms = next.max(now_ms);
        if force || self.last_timer_ms != Some(at_ms) {
            self.last_timer_ms = Some(at_ms);
            out.push(Action::SetTimer { at_ms });
        }
    }

    // ----- outbound ----------------------------------------------------------------

    /// Offers our log to every peer, one page at a time, and starts periodic re-offers.
    fn announce(&mut self, now_ms: u64) -> Result<()> {
        let head = self.head_seq;
        let pages = self.config.sync_pages_per_flush;
        let max_ctrl = self.config.max_ctrl_queue;
        let resync = self.config.resync_interval_ms;
        for state in self.peers.values_mut() {
            if resync > 0 && now_ms >= state.next_resync_ms {
                state.sync_cursor = 0;
                state.next_resync_ms = now_ms.saturating_add(resync);
            }
            let mut done = 0;
            while state.sync_cursor < head && done < pages && state.ctrl.len() < max_ctrl {
                done += 1;
                let page =
                    self.storage
                        .ids_after(state.sync_cursor, ids_per_packet(state.mtu), now_ms)?;
                let Some(&(last_seq, _)) = page.last() else {
                    state.sync_cursor = head;
                    break;
                };
                state.sync_cursor = last_seq;
                let ids: Vec<MessageId> = page
                    .iter()
                    .map(|(_, id)| *id)
                    .filter(|id| self.hops.arrived(id) > 0 && !state.knows.contains(id))
                    .collect();
                if ids.is_empty() {
                    continue;
                }
                match Packet::Inv(ids).encode() {
                    Ok(bytes) => state.ctrl.push_back(bytes),
                    Err(_) => self.stats.internal_errors += 1,
                }
            }
        }
        Ok(())
    }

    /// Moves queued packets into actions, a bounded number per peer.
    fn flush(&mut self, out: &mut Vec<Action>) {
        let budget = self.config.flush_packets_per_peer;
        for (peer, state) in &mut self.peers {
            for _ in 0..budget {
                let Some(bytes) = state.ctrl.pop_front().or_else(|| state.data.pop_front()) else {
                    break;
                };
                self.stats.packets_sent += 1;
                out.push(Action::SendPacket { peer: *peer, bytes });
            }
        }
    }

    fn send_ctrl(&mut self, peer: PeerId, packet: &Packet) {
        let Ok(bytes) = packet.encode() else {
            self.stats.internal_errors += 1;
            return;
        };
        let max = self.config.max_ctrl_queue;
        if let Some(state) = self.peers.get_mut(&peer) {
            if state.ctrl.len() < max {
                state.ctrl.push_back(bytes);
            } else {
                self.stats.queue_full += 1;
            }
        }
    }

    /// Queues all fragments of a frame for a peer, or none of them if there is no room.
    fn send_frame(&mut self, peer: PeerId, frame: &Frame) {
        let max = self.config.max_data_queue;
        let Some(state) = self.peers.get_mut(&peer) else {
            return;
        };
        let Ok(fragments) = split(frame, state.mtu - 1) else {
            self.stats.internal_errors += 1;
            return;
        };
        let mut packets = Vec::with_capacity(fragments.len());
        for fragment in fragments {
            match Packet::Fragment(fragment).encode() {
                Ok(bytes) => packets.push(bytes),
                Err(_) => {
                    self.stats.internal_errors += 1;
                    return;
                }
            }
        }
        if state.data.len() + packets.len() > max {
            self.stats.queue_full += 1;
            return;
        }
        state.data.extend(packets);
    }
}
