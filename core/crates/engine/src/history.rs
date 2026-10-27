//! Readable views of what the store holds.

use crypto::ContactCard;
use proto::{Kind, MessageId, NodeId};
use storage::{Conversation, TrustState};

/// Whether the local user wrote a message or received it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Direction {
    /// Written by this node.
    Outgoing,
    /// Written by someone else and addressed to everybody or to this node.
    Incoming,
}

/// One message of the user's conversation history.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HistoryEntry {
    /// Position in the local log; use it to continue paging.
    pub seq: u64,
    /// The message id.
    pub id: MessageId,
    /// Outgoing or incoming.
    pub direction: Direction,
    /// `Broadcast` or `Direct`.
    pub kind: Kind,
    /// The other party: the sender of an incoming message, the recipient of an
    /// outgoing direct message. `None` for outgoing broadcasts.
    pub counterpart: Option<NodeId>,
    /// The readable content. `None` when it cannot be read: an incoming direct message
    /// may fail to decrypt, and an outgoing direct message sent before the local copy of
    /// its text existed (schema version 3) is sealed to its recipient. Outgoing direct
    /// messages written since then carry the text the user wrote.
    pub body: Option<Vec<u8>>,
    /// The sender's creation time (milliseconds; untrusted clock).
    pub created_at_ms: u64,
    /// When this node received or stored the message (milliseconds, local clock).
    pub received_at_ms: u64,
    /// For an outgoing direct message: when the recipient's acknowledgement arrived
    /// (milliseconds, local clock). `None` until then, and for every other message.
    pub delivered_at_ms: Option<u64>,
}

/// A page of history.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HistoryPage {
    /// The entries found, in log order.
    pub entries: Vec<HistoryEntry>,
    /// Pass this as `after_seq` to continue after the last position that was examined.
    pub next_after_seq: u64,
}

/// A known peer identity.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Contact {
    /// The peer's address.
    pub node_id: NodeId,
    /// The verified contact card, if one is stored.
    pub card: Option<ContactCard>,
    /// How much the user trusts this peer.
    pub trust: TrustState,
    /// When the peer was first recorded (milliseconds).
    pub first_seen_ms: u64,
    /// When the peer was last recorded (milliseconds).
    pub last_seen_ms: u64,
}

/// One line of the list of conversations.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ConversationSummary {
    /// Which conversation this is.
    pub conversation: Conversation,
    /// Its newest message, for a preview. `None` only if that message vanished between
    /// the two reads that build this value (it expired or was evicted just then).
    pub last: Option<HistoryEntry>,
    /// Live messages in the conversation.
    pub message_count: u64,
    /// Incoming messages after the conversation's read marker.
    pub unread: u64,
}

/// One page of a conversation, read backwards from its newest message.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ConversationPage {
    /// The entries of the page, **oldest first**.
    pub entries: Vec<HistoryEntry>,
    /// Pass this as `before_seq` to read the next older page; `None` when there are no
    /// older messages.
    pub older_before_seq: Option<u64>,
    /// The conversation's read marker: incoming entries with `seq` at or below it have
    /// been read, the ones above it are unread.
    pub read_up_to_seq: u64,
}
