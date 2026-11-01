//! How the urgency of an announcement travels inside its payload.
//!
//! This is a convention of the apps, not of the protocol:
//!
//! * A **normal** announcement is the plain UTF-8 text. Nothing is added, so phones and tools
//!   that know nothing about severities show it correctly.
//! * An **important** or **emergency** announcement starts with two bytes: the control byte
//!   `0x1E` (record separator) and the ASCII digit of the level (`1` or `2`), then the UTF-8
//!   text.
//!
//! When reading, a digit above `2` counts as an emergency, `0` as normal, and a payload that
//! does not start with the marker is plain text. Text typed by a person never contains control
//! characters (see [`crate::sanitize::clean`]), so nobody can forge the marker from inside a
//! text.

use crate::sanitize::clean;

/// The control byte that starts a payload with a severity.
const MARKER: u8 = 0x1E;

/// How urgent an announcement is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum Severity {
    /// An ordinary announcement. Carries no marker.
    #[default]
    Normal,
    /// An announcement that deserves attention.
    Important,
    /// A warning that must not be missed.
    Emergency,
}

impl Severity {
    /// The ASCII digit that follows the marker in a payload.
    #[must_use]
    pub const fn digit(self) -> u8 {
        match self {
            Self::Normal => b'0',
            Self::Important => b'1',
            Self::Emergency => b'2',
        }
    }

    /// The bytes the severity takes from the payload: none for [`Severity::Normal`], two
    /// otherwise.
    #[must_use]
    pub const fn overhead(self) -> usize {
        match self {
            Self::Normal => 0,
            Self::Important | Self::Emergency => 2,
        }
    }

    const fn from_digit(digit: u8) -> Self {
        match digit {
            b'0' => Self::Normal,
            b'1' => Self::Important,
            _ => Self::Emergency,
        }
    }
}

/// The bytes a severity takes from the payload of an announcement. See [`Severity::overhead`].
#[must_use]
pub const fn announcement_overhead(severity: Severity) -> usize {
    severity.overhead()
}

/// What [`decode_announcement`] found in a payload.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodedAnnouncement {
    /// The text, already cleaned with [`clean`].
    pub text: String,
    /// The urgency.
    pub severity: Severity,
}

/// Builds the payload of an announcement: the cleaned text, with the marker in front unless
/// the severity is [`Severity::Normal`].
///
/// The result may exceed the protocol's payload limit; check it with
/// [`crate::budget::announcement_limit`] first.
#[must_use]
pub fn encode_announcement(text: &str, severity: Severity) -> Vec<u8> {
    let body = clean(text);
    if severity == Severity::Normal {
        return body.into_bytes();
    }
    let mut payload = Vec::with_capacity(severity.overhead() + body.len());
    payload.push(MARKER);
    payload.push(severity.digit());
    payload.extend_from_slice(body.as_bytes());
    payload
}

/// Reads the payload of an announcement. Never fails: invalid UTF-8 becomes `U+FFFD`, and a
/// payload without a (well-formed) marker is plain text of normal severity.
#[must_use]
pub fn decode_announcement(payload: &[u8]) -> DecodedAnnouncement {
    if let [MARKER, digit, rest @ ..] = payload
        && digit.is_ascii_digit()
    {
        return DecodedAnnouncement {
            text: clean(&String::from_utf8_lossy(rest)),
            severity: Severity::from_digit(*digit),
        };
    }
    DecodedAnnouncement {
        text: clean(&String::from_utf8_lossy(payload)),
        severity: Severity::Normal,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normal_is_plain_text() {
        assert_eq!(encode_announcement("hello", Severity::Normal), b"hello");
    }

    #[test]
    fn important_and_emergency_carry_the_marker() {
        assert_eq!(
            encode_announcement("hi", Severity::Important),
            [0x1E, b'1', b'h', b'i']
        );
        assert_eq!(
            encode_announcement("hi", Severity::Emergency),
            [0x1E, b'2', b'h', b'i']
        );
    }

    #[test]
    fn a_digit_above_two_is_an_emergency() {
        let decoded = decode_announcement(&[0x1E, b'9', b'x']);
        assert_eq!(decoded.severity, Severity::Emergency);
        assert_eq!(decoded.text, "x");
    }

    #[test]
    fn a_marker_without_a_digit_is_plain_text() {
        let decoded = decode_announcement(&[0x1E, b'A']);
        assert_eq!(decoded.severity, Severity::Normal);
        assert_eq!(decoded.text, "A");
    }

    #[test]
    fn short_payloads_do_not_panic() {
        assert_eq!(decode_announcement(&[]).text, "");
        assert_eq!(decode_announcement(&[0x1E]).text, "");
    }
}
