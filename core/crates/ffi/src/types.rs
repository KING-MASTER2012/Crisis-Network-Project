//! Plain data types that cross the boundary, and their conversions from the core's types.
//!
//! Ids and keys are byte arrays (`ByteArray` in Kotlin, `Data` in Swift); neighbours are
//! `u64` handles chosen by the platform's transport.

use engine::{
    Contact, Conversation, ConversationPage, ConversationSummary, Direction, HistoryEntry,
    HistoryPage, Output, TransportAction, UnreadCounts,
};
use mesh::{DisconnectReason, MeshStats, Notification, PublishError};
use proto::limits::NODE_ID_LEN;
use proto::{Kind, NodeId};
use storage::TrustState;

use crate::error::{CoreError, invalid_argument};

/// Settings of a node. Start from [`crate::default_settings`] and change what you need.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NodeSettings {
    /// Lifetime of messages published without an explicit one, in seconds.
    pub default_lifetime_secs: u32,
    /// Upper bound for the message store, in bytes.
    pub max_store_bytes: u64,
    /// Bytes of the store that other nodes may pin by addressing messages to this node.
    pub max_inbound_pinned_bytes: u64,
    /// Neighbours served at the same time.
    pub max_peers: u32,
}

/// What kind of message this is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MessageKind {
    /// A public, signed announcement.
    Broadcast,
    /// A message encrypted for one recipient.
    Direct,
    /// A delivery confirmation (normally handled inside the core).
    Ack,
}

impl From<Kind> for MessageKind {
    fn from(kind: Kind) -> Self {
        match kind {
            Kind::Broadcast => Self::Broadcast,
            Kind::Direct => Self::Direct,
            Kind::Ack => Self::Ack,
        }
    }
}

/// How much the user trusts a contact.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TrustLevel {
    /// Seen on the network, never verified.
    Unknown,
    /// The user compared the safety number in person.
    Verified,
    /// Messages from this node are dropped and never relayed.
    Blocked,
}

impl From<TrustState> for TrustLevel {
    fn from(state: TrustState) -> Self {
        match state {
            TrustState::Unknown => Self::Unknown,
            TrustState::Verified => Self::Verified,
            TrustState::Blocked => Self::Blocked,
        }
    }
}

impl From<TrustLevel> for TrustState {
    fn from(level: TrustLevel) -> Self {
        match level {
            TrustLevel::Unknown => Self::Unknown,
            TrustLevel::Verified => Self::Verified,
            TrustLevel::Blocked => Self::Blocked,
        }
    }
}

/// Whether the user wrote a message or received it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MessageDirection {
    /// Written by this node.
    Outgoing,
    /// Written by someone else.
    Incoming,
}

impl From<Direction> for MessageDirection {
    fn from(direction: Direction) -> Self {
        match direction {
            Direction::Outgoing => Self::Outgoing,
            Direction::Incoming => Self::Incoming,
        }
    }
}

/// Why the core asks the transport to close a connection.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum DisconnectCause {
    /// The neighbour misbehaved and is banned for a while.
    Banned,
    /// The link's packet size is too small to be used.
    MtuTooSmall,
    /// The node already serves as many neighbours as it may; try again later.
    TooManyPeers,
}

impl From<DisconnectReason> for DisconnectCause {
    fn from(reason: DisconnectReason) -> Self {
        match reason {
            DisconnectReason::Banned => Self::Banned,
            DisconnectReason::MtuTooSmall => Self::MtuTooSmall,
            DisconnectReason::TooManyPeers => Self::TooManyPeers,
        }
    }
}

/// Why a message the user wrote was not accepted.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PublishFailure {
    /// Not signed by this node, or malformed.
    Invalid,
    /// The message is already stored.
    Duplicate,
    /// The message was deleted or expired earlier.
    Tombstoned,
    /// There is no room in the store.
    QuotaExceeded,
}

impl From<PublishError> for PublishFailure {
    fn from(error: PublishError) -> Self {
        match error {
            PublishError::Invalid => Self::Invalid,
            PublishError::Duplicate => Self::Duplicate,
            PublishError::Tombstoned => Self::Tombstoned,
            PublishError::QuotaExceeded => Self::QuotaExceeded,
        }
    }
}

/// Something the transport must do, in order.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum TransportCommand {
    /// Send these bytes to this neighbour as one transport packet.
    SendPacket {
        /// The neighbour's handle.
        peer: u64,
        /// The packet.
        bytes: Vec<u8>,
    },
    /// Close the connection to this neighbour.
    Disconnect {
        /// The neighbour's handle.
        peer: u64,
        /// Why.
        cause: DisconnectCause,
    },
}

/// Something the user interface should show or react to.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum CoreNotification {
    /// A message arrived; `body` is readable (direct messages are already decrypted).
    MessageReceived {
        /// Message id (32 bytes).
        id: Vec<u8>,
        /// Sender's node id (32 bytes).
        sender: Vec<u8>,
        /// Broadcast or direct.
        kind: MessageKind,
        /// The content.
        body: Vec<u8>,
    },
    /// A message this node sent was acknowledged by its recipient.
    Delivered {
        /// Message id (32 bytes).
        id: Vec<u8>,
    },
    /// A message this node wrote was accepted and will spread.
    Published {
        /// Message id (32 bytes).
        id: Vec<u8>,
    },
    /// A message this node wrote was refused.
    PublishRejected {
        /// Message id, if it could be computed.
        id: Option<Vec<u8>>,
        /// Why.
        reason: PublishFailure,
    },
    /// A neighbour was accepted.
    PeerConnected {
        /// The neighbour's handle.
        peer: u64,
    },
    /// A neighbour went away.
    PeerDisconnected {
        /// The neighbour's handle.
        peer: u64,
    },
    /// A neighbour was banned.
    PeerBanned {
        /// The neighbour's handle.
        peer: u64,
    },
}

impl From<Notification> for CoreNotification {
    fn from(note: Notification) -> Self {
        match note {
            Notification::MessageReceived {
                id,
                sender,
                kind,
                body,
            } => Self::MessageReceived {
                id: id.as_bytes().to_vec(),
                sender: sender.as_bytes().to_vec(),
                kind: kind.into(),
                body,
            },
            Notification::Delivered { id } => Self::Delivered {
                id: id.as_bytes().to_vec(),
            },
            Notification::Published { id } => Self::Published {
                id: id.as_bytes().to_vec(),
            },
            Notification::PublishRejected { id, reason } => Self::PublishRejected {
                id: id.map(|id| id.as_bytes().to_vec()),
                reason: reason.into(),
            },
            Notification::PeerConnected { peer } => Self::PeerConnected { peer: peer.0 },
            Notification::PeerDisconnected { peer } => Self::PeerDisconnected { peer: peer.0 },
            Notification::PeerBanned { peer } => Self::PeerBanned { peer: peer.0 },
        }
    }
}

/// The result of one call into the core: what to do and what to tell the user.
#[derive(Debug, Clone, Default, PartialEq, Eq, uniffi::Record)]
pub struct CoreOutput {
    /// For the transport: packets to send and connections to close, in order.
    pub transport: Vec<TransportCommand>,
    /// For the user interface.
    pub notifications: Vec<CoreNotification>,
    /// When to call `tick` next, in the caller's clock (milliseconds). `None` keeps the
    /// previous deadline.
    pub next_timer_ms: Option<u64>,
}

impl From<Output> for CoreOutput {
    fn from(output: Output) -> Self {
        Self {
            transport: output
                .transport
                .into_iter()
                .map(|action| match action {
                    TransportAction::SendPacket { peer, bytes } => TransportCommand::SendPacket {
                        peer: peer.0,
                        bytes,
                    },
                    TransportAction::Disconnect { peer, reason } => TransportCommand::Disconnect {
                        peer: peer.0,
                        cause: reason.into(),
                    },
                })
                .collect(),
            notifications: output.notifications.into_iter().map(Into::into).collect(),
            next_timer_ms: output.next_timer_ms,
        }
    }
}

/// The outcome of publishing a message.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PublishOutcome {
    /// The message id (32 bytes).
    pub message_id: Vec<u8>,
    /// What to do next; a refusal shows up as a `PublishRejected` notification.
    pub output: CoreOutput,
}

/// A verified contact card.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CardDetails {
    /// The owner's node id (32 bytes).
    pub node_id: Vec<u8>,
    /// The node id as lowercase hexadecimal.
    pub node_id_hex: String,
    /// Eight groups of four hexadecimal digits that two people can read to each other
    /// to confirm that they hold the same card.
    pub safety_number: String,
}

/// A peer this node knows.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ContactInfo {
    /// The peer's node id (32 bytes).
    pub node_id: Vec<u8>,
    /// The verified contact card (128 bytes), if one is stored.
    pub card: Option<Vec<u8>>,
    /// The safety number of the stored card.
    pub safety_number: Option<String>,
    /// How much the user trusts the peer.
    pub trust: TrustLevel,
    /// When the peer was first recorded (milliseconds).
    pub first_seen_ms: u64,
    /// When the peer was last recorded (milliseconds).
    pub last_seen_ms: u64,
}

impl From<Contact> for ContactInfo {
    fn from(contact: Contact) -> Self {
        Self {
            node_id: contact.node_id.as_bytes().to_vec(),
            safety_number: contact
                .card
                .as_ref()
                .map(crypto::ContactCard::safety_number),
            card: contact.card.map(|card| card.to_bytes().to_vec()),
            trust: contact.trust.into(),
            first_seen_ms: contact.first_seen_ms,
            last_seen_ms: contact.last_seen_ms,
        }
    }
}

/// One message of the conversation history.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryItem {
    /// Position in the local log; use it to continue paging.
    pub seq: u64,
    /// The message id (32 bytes).
    pub id: Vec<u8>,
    /// Written by this node or received.
    pub direction: MessageDirection,
    /// Broadcast or direct.
    pub kind: MessageKind,
    /// The other party's node id: the sender of an incoming message, the recipient of an
    /// outgoing direct message. `None` for outgoing broadcasts.
    pub counterpart: Option<Vec<u8>>,
    /// The readable content. For an outgoing direct message this is the text the user
    /// wrote (the node keeps a local copy, since the message itself is sealed to its
    /// recipient). `None` when it cannot be read: an incoming message that failed to
    /// decrypt, or an outgoing direct message written before interface version 2.
    pub body: Option<Vec<u8>>,
    /// The sender's creation time (milliseconds; the sender's clock, untrusted).
    pub created_at_ms: u64,
    /// When this node stored the message (milliseconds, local clock).
    pub received_at_ms: u64,
    /// For an outgoing direct message: when the recipient's acknowledgement arrived
    /// (milliseconds, local clock). `None` until then and for every other message. A
    /// message that is stored but not yet delivered counts as "published".
    pub delivered_at_ms: Option<u64>,
}

impl From<HistoryEntry> for HistoryItem {
    fn from(entry: HistoryEntry) -> Self {
        Self {
            seq: entry.seq,
            id: entry.id.as_bytes().to_vec(),
            direction: entry.direction.into(),
            kind: entry.kind.into(),
            counterpart: entry.counterpart.map(|node| node.as_bytes().to_vec()),
            body: entry.body,
            created_at_ms: entry.created_at_ms,
            received_at_ms: entry.received_at_ms,
            delivered_at_ms: entry.delivered_at_ms,
        }
    }
}

/// A page of history.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryPageInfo {
    /// The entries found, in log order.
    pub entries: Vec<HistoryItem>,
    /// Pass this as `after_seq` to read the next page.
    pub next_after_seq: u64,
}

impl From<HistoryPage> for HistoryPageInfo {
    fn from(page: HistoryPage) -> Self {
        Self {
            entries: page.entries.into_iter().map(Into::into).collect(),
            next_after_seq: page.next_after_seq,
        }
    }
}

/// Which conversation to read or mark as read.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ConversationRef {
    /// The public announcements: every broadcast this node holds.
    Announcements,
    /// The direct messages exchanged with one node.
    Direct {
        /// The other node's id (32 bytes).
        peer: Vec<u8>,
    },
}

impl From<Conversation> for ConversationRef {
    fn from(conversation: Conversation) -> Self {
        match conversation {
            Conversation::Broadcasts => Self::Announcements,
            Conversation::Direct(peer) => Self::Direct {
                peer: peer.as_bytes().to_vec(),
            },
        }
    }
}

/// Checks a [`ConversationRef`] received from the platform and converts it.
pub(crate) fn conversation_of(reference: ConversationRef) -> Result<Conversation, CoreError> {
    match reference {
        ConversationRef::Announcements => Ok(Conversation::Broadcasts),
        ConversationRef::Direct { peer } => {
            let bytes = fixed::<NODE_ID_LEN>("a node id", &peer)?;
            Ok(Conversation::Direct(NodeId::new(bytes)))
        }
    }
}

/// One line of the list of conversations.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ConversationInfo {
    /// Which conversation this is.
    pub conversation: ConversationRef,
    /// Its newest message, for a preview.
    pub last: Option<HistoryItem>,
    /// Live messages in the conversation.
    pub message_count: u64,
    /// Incoming messages after the conversation's read marker.
    pub unread: u64,
}

impl From<ConversationSummary> for ConversationInfo {
    fn from(summary: ConversationSummary) -> Self {
        Self {
            conversation: summary.conversation.into(),
            last: summary.last.map(Into::into),
            message_count: summary.message_count,
            unread: summary.unread,
        }
    }
}

/// One page of a conversation, read backwards from its newest message.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ConversationPageInfo {
    /// The entries of the page, oldest first.
    pub entries: Vec<HistoryItem>,
    /// Pass this as `before_seq` to read the next older page; `None` when there are no
    /// older messages.
    pub older_before_seq: Option<u64>,
    /// The conversation's read marker: incoming entries with `seq` at or below it are read.
    pub read_up_to_seq: u64,
}

impl From<ConversationPage> for ConversationPageInfo {
    fn from(page: ConversationPage) -> Self {
        Self {
            entries: page.entries.into_iter().map(Into::into).collect(),
            older_before_seq: page.older_before_seq,
            read_up_to_seq: page.read_up_to_seq,
        }
    }
}

/// Unread incoming messages, split by kind of conversation.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct UnreadInfo {
    /// Unread direct messages, over all peers.
    pub direct: u64,
    /// Unread announcements.
    pub announcements: u64,
}

impl From<UnreadCounts> for UnreadInfo {
    fn from(counts: UnreadCounts) -> Self {
        Self {
            direct: counts.direct,
            announcements: counts.broadcasts,
        }
    }
}

/// Counters describing what the node has done so far, for diagnostics.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NodeStats {
    /// Packets received from connected neighbours.
    pub packets_received: u64,
    /// Packets handed to the transport.
    pub packets_sent: u64,
    /// Packets from neighbours that are not connected.
    pub unknown_peer_packets: u64,
    /// Packets dropped by the per-neighbour rate limiter.
    pub rate_limited: u64,
    /// Packets that failed to parse.
    pub malformed_packets: u64,
    /// Fragments refused.
    pub fragments_rejected: u64,
    /// Incomplete transfers dropped after the timeout.
    pub expired_transfers: u64,
    /// Frames whose signature did not verify.
    pub invalid_signatures: u64,
    /// Frames from a blocked sender.
    pub blocked_dropped: u64,
    /// Frames dropped by the per-sender rate limiter.
    pub sender_rate_limited: u64,
    /// Frames for messages already stored.
    pub duplicates: u64,
    /// Frames for deleted or expired messages.
    pub tombstoned: u64,
    /// Frames refused because the store was full.
    pub quota_rejected: u64,
    /// New messages stored from the network.
    pub frames_accepted: u64,
    /// Direct messages for this node that could not be decrypted.
    pub undecryptable: u64,
    /// Delivery confirmations created.
    pub acks_sent: u64,
    /// Requests abandoned after too many attempts.
    pub want_gave_up: u64,
    /// Announcements ignored because too many requests were in flight.
    pub inflight_full: u64,
    /// Outgoing packets dropped because a send queue was full.
    pub queue_full: u64,
    /// Neighbours banned.
    pub peers_banned: u64,
    /// Messages currently stored.
    pub stored_messages: u64,
    /// Bytes currently used by stored messages.
    pub stored_bytes: u64,
}

impl NodeStats {
    /// Combines the mesh counters with the store's current size.
    pub(crate) fn new(mesh: &MeshStats, stored_messages: u64, stored_bytes: u64) -> Self {
        Self {
            packets_received: mesh.packets_received,
            packets_sent: mesh.packets_sent,
            unknown_peer_packets: mesh.unknown_peer_packets,
            rate_limited: mesh.rate_limited,
            malformed_packets: mesh.malformed_packets,
            fragments_rejected: mesh.fragments_rejected,
            expired_transfers: mesh.expired_transfers,
            invalid_signatures: mesh.invalid_signatures,
            blocked_dropped: mesh.blocked_dropped,
            sender_rate_limited: mesh.sender_rate_limited,
            duplicates: mesh.duplicates,
            tombstoned: mesh.tombstoned,
            quota_rejected: mesh.quota_rejected,
            frames_accepted: mesh.frames_accepted,
            undecryptable: mesh.undecryptable,
            acks_sent: mesh.acks_sent,
            want_gave_up: mesh.want_gave_up,
            inflight_full: mesh.inflight_full,
            queue_full: mesh.queue_full,
            peers_banned: mesh.peers_banned,
            stored_messages,
            stored_bytes,
        }
    }
}

/// Reads a fixed-size array out of a byte slice, naming the argument in the error.
pub(crate) fn fixed<const N: usize>(what: &str, bytes: &[u8]) -> Result<[u8; N], CoreError> {
    <[u8; N]>::try_from(bytes).map_err(|_| {
        invalid_argument(format!(
            "{what} must be exactly {N} bytes, got {}",
            bytes.len()
        ))
    })
}

#[cfg(test)]
mod tests {
    use mesh::PeerId;
    use proto::{MessageId, NodeId};

    use super::*;

    #[test]
    fn notifications_convert_losslessly() {
        let note = Notification::MessageReceived {
            id: MessageId::new([1; 32]),
            sender: NodeId::new([2; 32]),
            kind: Kind::Direct,
            body: b"hi".to_vec(),
        };
        assert_eq!(
            CoreNotification::from(note),
            CoreNotification::MessageReceived {
                id: vec![1; 32],
                sender: vec![2; 32],
                kind: MessageKind::Direct,
                body: b"hi".to_vec(),
            }
        );
        assert_eq!(
            CoreNotification::from(Notification::PublishRejected {
                id: None,
                reason: PublishError::QuotaExceeded
            }),
            CoreNotification::PublishRejected {
                id: None,
                reason: PublishFailure::QuotaExceeded
            }
        );
    }

    #[test]
    fn output_keeps_the_order_of_transport_commands() {
        let output = Output {
            transport: vec![
                TransportAction::SendPacket {
                    peer: PeerId(7),
                    bytes: vec![1, 2],
                },
                TransportAction::Disconnect {
                    peer: PeerId(7),
                    reason: DisconnectReason::TooManyPeers,
                },
            ],
            notifications: vec![],
            next_timer_ms: Some(42),
        };
        let converted = CoreOutput::from(output);
        assert_eq!(
            converted.transport,
            vec![
                TransportCommand::SendPacket {
                    peer: 7,
                    bytes: vec![1, 2]
                },
                TransportCommand::Disconnect {
                    peer: 7,
                    cause: DisconnectCause::TooManyPeers
                },
            ]
        );
        assert_eq!(converted.next_timer_ms, Some(42));
    }

    #[test]
    fn trust_levels_round_trip() {
        for level in [
            TrustLevel::Unknown,
            TrustLevel::Verified,
            TrustLevel::Blocked,
        ] {
            assert_eq!(TrustLevel::from(TrustState::from(level)), level);
        }
    }

    #[test]
    fn fixed_size_arguments_are_checked() {
        assert_eq!(fixed::<4>("x", &[1, 2, 3, 4]).unwrap(), [1, 2, 3, 4]);
        let err = fixed::<4>("an id", &[1, 2]).unwrap_err();
        assert!(matches!(err, CoreError::InvalidArgument { .. }));
        assert!(err.to_string().contains("an id"));
    }
}
