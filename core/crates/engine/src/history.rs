//! Readable views of what the store holds.

use crypto::ContactCard;
use proto::{Kind, MessageId, NodeId};
use storage::TrustState;

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
    /// The readable content. `None` when it cannot be read: an outgoing direct
    /// message is sealed to its recipient, and an incoming one may fail to decrypt.
    pub body: Option<Vec<u8>>,
    /// The sender's creation time (milliseconds; untrusted clock).
    pub created_at_ms: u64,
    /// When this node received or stored the message (milliseconds, local clock).
    pub received_at_ms: u64,
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
