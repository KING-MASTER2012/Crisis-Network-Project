//! What the engine hands back to the platform after each call.

use mesh::{Action, DisconnectReason, Notification, PeerId};

/// Something the transport layer must do.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TransportAction {
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
}

/// The result of one engine call, sorted by audience.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Output {
    /// For the transport: packets to send and connections to close, in order.
    pub transport: Vec<TransportAction>,
    /// For the user interface: new messages, delivery confirmations, peer changes.
    pub notifications: Vec<Notification>,
    /// When the engine wants to be ticked next (milliseconds, the caller's clock).
    pub next_timer_ms: Option<u64>,
}

impl Output {
    /// Sorts raw mesh actions by audience.
    pub fn from_actions(actions: Vec<Action>) -> Self {
        let mut out = Self::default();
        for action in actions {
            match action {
                Action::SendPacket { peer, bytes } => {
                    out.transport
                        .push(TransportAction::SendPacket { peer, bytes });
                }
                Action::Disconnect { peer, reason } => {
                    out.transport
                        .push(TransportAction::Disconnect { peer, reason });
                }
                Action::SetTimer { at_ms } => out.next_timer_ms = Some(at_ms),
                Action::Notify(note) => out.notifications.push(note),
            }
        }
        out
    }

    /// Whether there is nothing to do and nothing to tell.
    pub fn is_empty(&self) -> bool {
        self.transport.is_empty() && self.notifications.is_empty() && self.next_timer_ms.is_none()
    }

    /// Appends another output after this one. The newer timer wins.
    pub fn extend(&mut self, other: Output) {
        self.transport.extend(other.transport);
        self.notifications.extend(other.notifications);
        if other.next_timer_ms.is_some() {
            self.next_timer_ms = other.next_timer_ms;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn actions_are_sorted_by_audience() {
        let peer = PeerId(3);
        let out = Output::from_actions(vec![
            Action::SendPacket {
                peer,
                bytes: vec![1],
            },
            Action::SetTimer { at_ms: 10 },
            Action::Notify(Notification::PeerConnected { peer }),
            Action::Disconnect {
                peer,
                reason: DisconnectReason::Banned,
            },
            Action::SetTimer { at_ms: 20 },
        ]);
        assert_eq!(out.transport.len(), 2);
        assert_eq!(
            out.notifications,
            vec![Notification::PeerConnected { peer }]
        );
        assert_eq!(out.next_timer_ms, Some(20));
        assert!(!out.is_empty());
        assert!(Output::default().is_empty());
    }

    #[test]
    fn extend_keeps_order_and_the_newer_timer() {
        let mut a = Output::from_actions(vec![Action::SetTimer { at_ms: 5 }]);
        let b = Output::from_actions(vec![Action::Notify(Notification::PeerConnected {
            peer: PeerId(1),
        })]);
        a.extend(b);
        assert_eq!(a.next_timer_ms, Some(5));
        assert_eq!(a.notifications.len(), 1);
        a.extend(Output::from_actions(vec![Action::SetTimer { at_ms: 9 }]));
        assert_eq!(a.next_timer_ms, Some(9));
    }
}
