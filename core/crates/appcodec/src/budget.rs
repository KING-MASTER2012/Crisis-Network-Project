//! Counting the bytes of a text against the limit of the message it goes into.
//!
//! What counts is the number of bytes of the UTF-8 text, not the number of characters.

use crate::announcement::Severity;

/// What the sealed box of a direct message adds to the text: an ephemeral key (32 bytes) and a
/// tag (16 bytes). Taken from the protocol's limits.
pub const DIRECT_SEAL_OVERHEAD: usize = proto::limits::SEAL_OVERHEAD;

/// The longest text of a direct message when the protocol allows `max_payload_bytes`.
#[must_use]
pub const fn direct_limit(max_payload_bytes: usize) -> usize {
    max_payload_bytes.saturating_sub(DIRECT_SEAL_OVERHEAD)
}

/// The longest text of an announcement of `severity` when the protocol allows
/// `max_payload_bytes`.
#[must_use]
pub const fn announcement_limit(max_payload_bytes: usize, severity: Severity) -> usize {
    max_payload_bytes.saturating_sub(severity.overhead())
}

/// The bytes of `text` as UTF-8.
#[must_use]
pub const fn utf8_length(text: &str) -> usize {
    text.len()
}

/// How much of a limit a text uses.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ByteCount {
    /// Bytes the text uses.
    pub used: usize,
    /// Bytes the text may use.
    pub limit: usize,
}

impl ByteCount {
    /// Bytes still free; zero when the text is over the limit.
    #[must_use]
    pub const fn remaining(&self) -> usize {
        self.limit.saturating_sub(self.used)
    }

    /// Whether the text is too long.
    #[must_use]
    pub const fn over(&self) -> bool {
        self.used > self.limit
    }

    /// Within the last tenth of the room (and not over): time to warn.
    #[must_use]
    pub const fn near_limit(&self) -> bool {
        !self.over() && self.remaining() <= self.limit / 10
    }
}

/// Counts `text` against `limit`.
#[must_use]
pub const fn count_bytes(text: &str, limit: usize) -> ByteCount {
    ByteCount {
        used: utf8_length(text),
        limit,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn limits_follow_the_protocol() {
        assert_eq!(DIRECT_SEAL_OVERHEAD, 48);
        assert_eq!(direct_limit(1024), 976);
        assert_eq!(announcement_limit(1024, Severity::Normal), 1024);
        assert_eq!(announcement_limit(1024, Severity::Important), 1022);
        assert_eq!(announcement_limit(1024, Severity::Emergency), 1022);
        assert_eq!(direct_limit(10), 0);
    }

    #[test]
    fn counts_bytes_not_characters() {
        assert_eq!(count_bytes("héllo", 10).used, 6);
    }

    #[test]
    fn warns_in_the_last_tenth() {
        let near = |used: usize, limit: usize| ByteCount { used, limit }.near_limit();
        assert!(!near(89, 100));
        assert!(near(90, 100));
        assert!(near(100, 100));
        assert!(!near(101, 100));
    }
}
