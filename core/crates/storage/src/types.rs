//! Public value types.

use proto::{Envelope, MessageId, NodeId};

/// Retention priority of a stored message. Under quota pressure, lower
/// priorities are evicted first.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
#[repr(u8)]
pub enum Priority {
    /// An Ack in transit (cheap to regenerate).
    Ack = 0,
    /// A broadcast in transit.
    Broadcast = 1,
    /// A direct message in transit for someone else.
    Direct = 2,
    /// Sent by, or addressed to, the local node. Never evicted by quota pressure.
    Own = 3,
}

/// What happened to an insertion.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum InsertOutcome {
    /// The message was stored.
    Inserted {
        /// The log sequence number assigned to it (strictly increasing).
        seq: u64,
        /// How many lower-or-equal-priority messages were evicted to make room.
        evicted: usize,
    },
    /// A message with the same id is already stored; nothing changed.
    Duplicate,
    /// The id has a live tombstone (it was deleted or expired); nothing changed.
    Tombstoned,
    /// There was no room and nothing could be evicted for it; nothing changed.
    QuotaExceeded,
}

/// A message read back from the log.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StoredMessage {
    /// Position in the append-only log.
    pub seq: u64,
    /// The message id.
    pub id: MessageId,
    /// The stored envelope.
    pub envelope: Envelope,
    /// When this node received the message (local clock, ms).
    pub received_at_ms: u64,
    /// When this node will drop the message (`received_at_ms + lifetime`, ms).
    pub expires_at_ms: u64,
    /// When the message was uploaded to a server, if ever (ms).
    pub uploaded_at_ms: Option<u64>,
}

/// Counters describing the store.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Stats {
    /// Messages currently stored (including expired ones not purged yet).
    pub message_count: u64,
    /// Sum of the encoded sizes of the stored envelopes.
    pub total_bytes: u64,
    /// Tombstones currently stored.
    pub tombstone_count: u64,
    /// Peers currently stored.
    pub peer_count: u64,
}

/// What a [`crate::Storage::purge_expired`] call removed.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct PurgeReport {
    /// Expired messages removed (each also received a tombstone).
    pub messages_expired: u64,
    /// Tombstones whose retention ended.
    pub tombstones_expired: u64,
    /// Tombstones trimmed because the tombstone budget was exceeded.
    pub tombstones_trimmed: u64,
}

/// How much a peer is trusted.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum TrustState {
    /// Seen on the network, never verified.
    Unknown = 0,
    /// The user verified the safety number in person.
    Verified = 1,
    /// The user blocked this peer.
    Blocked = 2,
}

impl TrustState {
    pub(crate) fn from_i64(v: i64) -> Option<Self> {
        match v {
            0 => Some(Self::Unknown),
            1 => Some(Self::Verified),
            2 => Some(Self::Blocked),
            _ => None,
        }
    }
}

/// A known peer.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Peer {
    /// The peer's node id.
    pub node_id: NodeId,
    /// When the peer was first seen (ms).
    pub first_seen_ms: u64,
    /// When the peer was last seen (ms).
    pub last_seen_ms: u64,
    /// Trust state.
    pub trust: TrustState,
    /// The peer's encoded contact card, if known. Stored opaquely; it is parsed
    /// and verified by the `crypto` crate.
    pub contact_card: Option<Vec<u8>>,
}

/// One conversation of the local user: the public announcements, or the direct messages
/// exchanged with one peer.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Conversation {
    /// Every broadcast this node holds, its own and everybody else's.
    Broadcasts,
    /// The direct messages between this node and one peer, in both directions.
    Direct(NodeId),
}

/// What this node remembers about a stored message besides its envelope.
///
/// A note lives exactly as long as its message: when the message expires, is evicted or is
/// deleted, the note goes with it.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MessageNote {
    /// For a direct message this node sent: the text the user wrote. The envelope itself is
    /// sealed to the recipient, so this local copy is the only way to read it back.
    pub plaintext: Option<Vec<u8>>,
    /// When the recipient's acknowledgement arrived (local clock, ms), if it did.
    pub delivered_at_ms: Option<u64>,
}

/// One line of the conversation list, see [`crate::Storage::conversations`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ConversationRow {
    /// Which conversation this is.
    pub conversation: Conversation,
    /// Log position of its newest live message.
    pub last_seq: u64,
    /// Live messages in it (acknowledgements are not counted).
    pub message_count: u64,
    /// Incoming messages after the conversation's read marker.
    pub unread: u64,
}

/// One page of a conversation, see [`crate::Storage::conversation_messages`].
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ConversationMessages {
    /// The messages of the page, oldest first.
    pub messages: Vec<StoredMessage>,
    /// Whether older live messages exist before the first one of this page.
    pub more_before: bool,
}

/// Unread incoming messages, split by kind of conversation.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct UnreadCounts {
    /// Unread direct messages, over all peers.
    pub direct: u64,
    /// Unread announcements.
    pub broadcasts: u64,
}
