//! The vocabulary between the platform and the mesh: events in, actions out.

use proto::{Envelope, Kind, MessageId, NodeId};

/// An opaque handle for a directly connected neighbour.
///
/// The platform assigns it (for example, one number per BLE connection). The mesh
/// never interprets it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct PeerId(pub u64);

/// Something that happened, reported by the platform.
#[derive(Clone, Debug)]
pub enum Event {
    /// A neighbour connected (or its link parameters changed).
    PeerSeen {
        /// The neighbour.
        peer: PeerId,
        /// The usable packet size of the link in bytes. Must be at least
        /// [`crate::MIN_LINK_MTU`].
        link_mtu: usize,
    },
    /// A neighbour disconnected.
    PeerLost {
        /// The neighbour.
        peer: PeerId,
    },
    /// The transport delivered one packet from a neighbour.
    PacketReceived {
        /// The neighbour.
        peer: PeerId,
        /// The packet bytes, exactly as received.
        bytes: Vec<u8>,
    },
    /// Time passed. The platform calls this at or after every [`Action::SetTimer`].
    Tick,
    /// The local user published a message that was signed with this node's identity.
    Publish {
        /// The signed envelope.
        envelope: Box<Envelope>,
    },
}

/// Why the mesh asks the platform to drop a connection.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DisconnectReason {
    /// The peer is (or was just) banned for misbehaviour.
    Banned,
    /// The link's packet size is below [`crate::MIN_LINK_MTU`].
    MtuTooSmall,
    /// The mesh already serves [`crate::MeshConfig::max_peers`] neighbours. The platform
    /// may retry later; this is not a ban.
    TooManyPeers,
}

/// Why a locally published message was not accepted.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PublishError {
    /// Not signed by this node, bad signature or malformed.
    Invalid,
    /// The message is already stored.
    Duplicate,
    /// The message was deleted or expired earlier.
    Tombstoned,
    /// There is no room in the store.
    QuotaExceeded,
}

/// Something the application should know about.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Notification {
    /// A message arrived. For `Direct` messages `body` is the decrypted plaintext;
    /// for `Broadcast` it is the payload.
    MessageReceived {
        /// Message id.
        id: MessageId,
        /// Who sent it.
        sender: NodeId,
        /// `Broadcast` or `Direct`.
        kind: Kind,
        /// The readable content.
        body: Vec<u8>,
    },
    /// A message this node sent was acknowledged by its recipient.
    Delivered {
        /// Id of the acknowledged message.
        id: MessageId,
    },
    /// A local [`Event::Publish`] was accepted and will be propagated.
    Published {
        /// Message id.
        id: MessageId,
    },
    /// A local [`Event::Publish`] was refused.
    PublishRejected {
        /// Message id, if it could be computed.
        id: Option<MessageId>,
        /// Why.
        reason: PublishError,
    },
    /// A neighbour was accepted.
    PeerConnected {
        /// The neighbour.
        peer: PeerId,
    },
    /// A neighbour went away.
    PeerDisconnected {
        /// The neighbour.
        peer: PeerId,
    },
    /// A neighbour was banned.
    PeerBanned {
        /// The neighbour.
        peer: PeerId,
    },
}

/// Something the platform must do.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Action {
    /// Send these bytes to this neighbour as one transport packet.
    SendPacket {
        /// The neighbour.
        peer: PeerId,
        /// The packet bytes.
        bytes: Vec<u8>,
    },
    /// Close the connection to this neighbour.
    Disconnect {
        /// The neighbour.
        peer: PeerId,
        /// Why.
        reason: DisconnectReason,
    },
    /// Deliver an [`Event::Tick`] at or after this time (milliseconds, caller's clock).
    SetTimer {
        /// The deadline.
        at_ms: u64,
    },
    /// Tell the application something.
    Notify(Notification),
}

/// Counters describing what the mesh has done. Useful for tests and diagnostics.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MeshStats {
    /// Packets received from known peers.
    pub packets_received: u64,
    /// Packets handed to the platform for sending.
    pub packets_sent: u64,
    /// Packets from peers that are not connected.
    pub unknown_peer_packets: u64,
    /// Packets dropped by the per-peer rate limiter.
    pub rate_limited: u64,
    /// Packets that failed to parse.
    pub malformed_packets: u64,
    /// Fragments refused (conflicts, invalid, or memory limits).
    pub fragments_rejected: u64,
    /// Incomplete transfers dropped after the reassembly timeout.
    pub expired_transfers: u64,
    /// Frames whose signature did not verify.
    pub invalid_signatures: u64,
    /// Frames from a locally blocked sender.
    pub blocked_dropped: u64,
    /// Frames dropped by the per-sender rate limiter.
    pub sender_rate_limited: u64,
    /// Frames for messages we already had.
    pub duplicates: u64,
    /// Frames for deleted or expired messages.
    pub tombstoned: u64,
    /// Frames refused because the store was full.
    pub quota_rejected: u64,
    /// New messages stored from the network.
    pub frames_accepted: u64,
    /// Direct messages for us that could not be decrypted.
    pub undecryptable: u64,
    /// Acks we created.
    pub acks_sent: u64,
    /// Requests abandoned after too many attempts.
    pub want_gave_up: u64,
    /// Announcements ignored because too many requests were in flight.
    pub inflight_full: u64,
    /// Outgoing packets dropped because a send queue was full.
    pub queue_full: u64,
    /// Peers banned.
    pub peers_banned: u64,
    /// Unexpected local encoding failures.
    pub internal_errors: u64,
}
