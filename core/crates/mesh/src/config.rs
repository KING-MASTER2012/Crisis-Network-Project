//! Mesh configuration.

use proto::limits::{MAX_FRAME_LEN, MAX_HOPS};

use crate::error::{MeshError, Result};

/// Tunable behaviour of a [`crate::Mesh`]. The defaults suit a phone on BLE; the
/// values are starting points until real-device measurements exist.
#[derive(Clone, Debug)]
pub struct MeshConfig {
    /// Hop budget given to messages created on this node.
    pub default_hops: u8,
    /// Upper bound applied to the `hops_left` of incoming frames (a relay could
    /// otherwise inflate it, because it is not signed).
    pub max_hops: u8,
    /// Hop budget assumed for stored messages whose real budget is unknown (for
    /// example after a restart).
    pub fallback_hops: u8,
    /// Lifetime of the acknowledgements this node creates, in seconds.
    pub ack_lifetime_secs: u32,

    /// An incomplete transfer is dropped this long after its first fragment (ms).
    pub reassembly_timeout_ms: u64,
    /// Concurrent incomplete transfers allowed per peer; the oldest is evicted for a new one.
    pub max_transfers_per_peer: usize,
    /// Bytes of incomplete transfers allowed per peer.
    pub max_reassembly_bytes_per_peer: usize,
    /// Bytes of incomplete transfers allowed across all peers.
    pub max_reassembly_bytes_total: usize,

    /// Packets sent to one peer per call to [`crate::Mesh::handle`].
    pub flush_packets_per_peer: usize,
    /// How soon the mesh asks to be ticked again while it has queued work (ms).
    pub flush_interval_ms: u64,
    /// Maximum queued control packets (`Inv`/`Want`) per peer.
    pub max_ctrl_queue: usize,
    /// Maximum queued data packets (fragments) per peer.
    pub max_data_queue: usize,
    /// Inventory pages offered to one peer per call to [`crate::Mesh::handle`].
    pub sync_pages_per_flush: usize,
    /// How often a peer is re-offered the whole log, to repair lost announcements
    /// (ms). Zero disables the periodic re-offer.
    pub resync_interval_ms: u64,

    /// How long to wait for a requested message before asking again (ms).
    pub want_timeout_ms: u64,
    /// How many times a message is requested before giving up.
    pub max_want_attempts: u32,
    /// Maximum messages requested but not yet received.
    pub max_inflight: usize,
    /// Maximum requests in flight towards one peer. A peer that announces messages it
    /// never delivers can therefore tie up only this many of the `max_inflight` slots.
    pub max_inflight_per_peer: usize,
    /// Ids remembered per peer as "the peer already has this".
    pub peer_known_ids: usize,
    /// Messages whose hop budget is remembered.
    pub max_tracked_hops: usize,
    /// Ids of messages from blocked senders that are not requested again.
    pub max_rejected_ids: usize,

    /// Neighbours served at the same time. A further neighbour is turned away
    /// ([`crate::DisconnectReason::TooManyPeers`]) until a slot frees up.
    pub max_peers: usize,
    /// Bans remembered. When the table is full, the ban that ends soonest is forgotten.
    pub max_banned_peers: usize,

    /// How often expired messages are purged from the store (ms).
    pub purge_interval_ms: u64,

    /// Sustained packets per second accepted from one peer.
    pub packet_rate_per_sec: u64,
    /// Burst size of the packet limiter.
    pub packet_burst: u64,
    /// Sustained bytes per second accepted from one peer.
    pub byte_rate_per_sec: u64,
    /// Burst size of the byte limiter (at least 1024).
    pub byte_burst: u64,

    /// Sustained new messages per second accepted from one sender identity.
    pub sender_rate_per_sec: u64,
    /// Burst size of the per-sender limiter.
    pub sender_burst: u64,
    /// Sender identities tracked by the limiter.
    pub max_tracked_senders: usize,

    /// Penalty at which a peer is banned.
    pub ban_threshold: u32,
    /// Penalty points forgiven per second.
    pub penalty_decay_per_sec: u32,
    /// How long a ban lasts (ms).
    pub ban_duration_ms: u64,
    /// Penalty for a frame with an invalid signature.
    pub penalty_invalid_signature: u32,
    /// Penalty for a packet that does not parse.
    pub penalty_malformed: u32,
    /// Penalty for a packet dropped by the rate limiter.
    pub penalty_rate_limited: u32,
    /// Penalty for conflicting or invalid fragments.
    pub penalty_bad_fragment: u32,
}

impl Default for MeshConfig {
    fn default() -> Self {
        Self {
            default_hops: 8,
            max_hops: 12,
            fallback_hops: 2,
            ack_lifetime_secs: 3600,

            reassembly_timeout_ms: 30_000,
            max_transfers_per_peer: 4,
            max_reassembly_bytes_per_peer: 8 * 1024,
            max_reassembly_bytes_total: 64 * 1024,

            flush_packets_per_peer: 8,
            flush_interval_ms: 100,
            max_ctrl_queue: 64,
            max_data_queue: 256,
            sync_pages_per_flush: 4,
            resync_interval_ms: 60_000,

            want_timeout_ms: 3_000,
            max_want_attempts: 4,
            max_inflight: 1024,
            max_inflight_per_peer: 256,
            peer_known_ids: 512,
            max_tracked_hops: 4096,
            max_rejected_ids: 1024,

            max_peers: 128,
            max_banned_peers: 1024,

            purge_interval_ms: 10_000,

            packet_rate_per_sec: 100,
            packet_burst: 200,
            byte_rate_per_sec: 20_000,
            byte_burst: 40_000,

            sender_rate_per_sec: 2,
            sender_burst: 20,
            max_tracked_senders: 1024,

            ban_threshold: 100,
            penalty_decay_per_sec: 1,
            ban_duration_ms: 600_000,
            penalty_invalid_signature: 25,
            penalty_malformed: 5,
            penalty_rate_limited: 1,
            penalty_bad_fragment: 10,
        }
    }
}

fn check(ok: bool, message: &'static str) -> Result<()> {
    if ok {
        Ok(())
    } else {
        Err(MeshError::InvalidConfig(message))
    }
}

impl MeshConfig {
    /// Checks that the values are consistent.
    pub fn validate(&self) -> Result<()> {
        check(self.default_hops >= 1, "default_hops must be at least 1")?;
        check(
            self.default_hops <= self.max_hops && self.max_hops <= MAX_HOPS,
            "hop limits must satisfy default_hops <= max_hops <= MAX_HOPS",
        )?;
        check(
            self.fallback_hops <= self.max_hops,
            "fallback_hops must not exceed max_hops",
        )?;
        check(
            self.ack_lifetime_secs > 0,
            "ack_lifetime_secs must be positive",
        )?;
        check(
            self.reassembly_timeout_ms > 0,
            "reassembly_timeout_ms must be positive",
        )?;
        check(
            self.max_transfers_per_peer > 0,
            "max_transfers_per_peer must be positive",
        )?;
        check(
            self.max_reassembly_bytes_per_peer >= MAX_FRAME_LEN,
            "max_reassembly_bytes_per_peer must fit one maximum-size frame",
        )?;
        check(
            self.max_reassembly_bytes_total >= self.max_reassembly_bytes_per_peer,
            "max_reassembly_bytes_total must be at least the per-peer limit",
        )?;
        check(
            self.flush_packets_per_peer > 0,
            "flush_packets_per_peer must be positive",
        )?;
        check(
            self.flush_interval_ms > 0,
            "flush_interval_ms must be positive",
        )?;
        check(self.max_ctrl_queue > 0, "max_ctrl_queue must be positive")?;
        check(
            self.max_data_queue >= 32,
            "max_data_queue must fit a maximum-size frame at the smallest MTU (32)",
        )?;
        check(
            self.sync_pages_per_flush > 0,
            "sync_pages_per_flush must be positive",
        )?;
        check(self.want_timeout_ms > 0, "want_timeout_ms must be positive")?;
        check(
            self.max_want_attempts > 0,
            "max_want_attempts must be positive",
        )?;
        check(self.max_inflight > 0, "max_inflight must be positive")?;
        check(
            self.max_inflight_per_peer > 0,
            "max_inflight_per_peer must be positive",
        )?;
        check(self.peer_known_ids > 0, "peer_known_ids must be positive")?;
        check(
            self.max_tracked_hops > 0,
            "max_tracked_hops must be positive",
        )?;
        check(
            self.max_rejected_ids > 0,
            "max_rejected_ids must be positive",
        )?;
        check(self.max_peers > 0, "max_peers must be positive")?;
        check(
            self.max_banned_peers > 0,
            "max_banned_peers must be positive",
        )?;
        check(
            self.purge_interval_ms > 0,
            "purge_interval_ms must be positive",
        )?;
        check(
            self.packet_rate_per_sec > 0 && self.packet_burst > 0,
            "packet limiter values must be positive",
        )?;
        check(
            self.byte_rate_per_sec > 0 && self.byte_burst >= 1024,
            "byte limiter needs a positive rate and a burst of at least 1024",
        )?;
        check(
            self.sender_rate_per_sec > 0 && self.sender_burst > 0,
            "sender limiter values must be positive",
        )?;
        check(
            self.max_tracked_senders > 0,
            "max_tracked_senders must be positive",
        )?;
        check(self.ban_threshold > 0, "ban_threshold must be positive")?;
        check(
            self.penalty_decay_per_sec > 0,
            "penalty_decay_per_sec must be positive",
        )?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_is_valid() {
        MeshConfig::default().validate().unwrap();
    }

    #[test]
    fn inconsistent_values_are_rejected() {
        let bad = |f: fn(&mut MeshConfig)| {
            let mut c = MeshConfig::default();
            f(&mut c);
            c.validate().is_err()
        };
        assert!(bad(|c| c.default_hops = 0));
        assert!(bad(|c| c.default_hops = c.max_hops + 1));
        assert!(bad(|c| c.max_hops = MAX_HOPS + 1));
        assert!(bad(|c| c.max_reassembly_bytes_per_peer = 100));
        assert!(bad(|c| c.max_data_queue = 4));
        assert!(bad(|c| c.byte_burst = 10));
        assert!(bad(|c| c.penalty_decay_per_sec = 0));
        assert!(bad(|c| c.max_inflight_per_peer = 0));
        assert!(bad(|c| c.max_peers = 0));
        assert!(bad(|c| c.max_banned_peers = 0));
    }
}
