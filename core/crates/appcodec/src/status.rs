//! What a sent message has been through so far.

/// The delivery status of a message the user sent. Received messages have none.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum DeliveryStatus {
    /// Stored on this phone and spreading through the mesh.
    Published,
    /// The recipient's acknowledgement came back.
    Delivered,
}

/// The status of a message: `None` for one received from someone else. A direct message the
/// user sent is delivered once the recipient's acknowledgement came back, otherwise it is only
/// published. An announcement never gets an acknowledgement.
#[must_use]
pub const fn message_status(
    outgoing: bool,
    direct: bool,
    delivered: bool,
) -> Option<DeliveryStatus> {
    if !outgoing {
        None
    } else if direct && delivered {
        Some(DeliveryStatus::Delivered)
    } else {
        Some(DeliveryStatus::Published)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_acknowledged_direct_messages_are_delivered() {
        assert_eq!(message_status(false, true, true), None);
        assert_eq!(
            message_status(true, true, true),
            Some(DeliveryStatus::Delivered)
        );
        assert_eq!(
            message_status(true, true, false),
            Some(DeliveryStatus::Published)
        );
        assert_eq!(
            message_status(true, false, true),
            Some(DeliveryStatus::Published)
        );
    }
}
