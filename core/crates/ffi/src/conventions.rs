//! The app conventions of the `appcodec` crate, exported for the clients.
//!
//! These are small pure functions that every client has to apply identically: cleaning a text
//! from another phone, putting the urgency of an announcement into its payload, the text form
//! of a contact card, byte budgets, lifetimes, nicknames and delivery status. They are not part
//! of the protocol or of a node; they need no [`crate::CrisisNode`].
//!
//! Counts that cannot be negative are `u32`; lifetimes are `i64` seconds (`Long` in Kotlin),
//! where a negative value counts as zero.

use appcodec::{LifetimeUnit as CodecUnit, Severity};

/// How urgent an announcement is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AnnouncementSeverity {
    /// An ordinary announcement. Its payload is the plain text.
    Normal,
    /// An announcement that deserves attention.
    Important,
    /// A warning that must not be missed.
    Emergency,
}

impl From<AnnouncementSeverity> for Severity {
    fn from(severity: AnnouncementSeverity) -> Self {
        match severity {
            AnnouncementSeverity::Normal => Self::Normal,
            AnnouncementSeverity::Important => Self::Important,
            AnnouncementSeverity::Emergency => Self::Emergency,
        }
    }
}

impl From<Severity> for AnnouncementSeverity {
    fn from(severity: Severity) -> Self {
        match severity {
            Severity::Normal => Self::Normal,
            Severity::Important => Self::Important,
            Severity::Emergency => Self::Emergency,
        }
    }
}

/// A unit of the lifetime of an announcement.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum LifetimeUnit {
    /// One second.
    Seconds,
    /// 60 seconds.
    Minutes,
    /// 3 600 seconds.
    Hours,
    /// 86 400 seconds.
    Days,
}

impl From<LifetimeUnit> for CodecUnit {
    fn from(unit: LifetimeUnit) -> Self {
        match unit {
            LifetimeUnit::Seconds => Self::Seconds,
            LifetimeUnit::Minutes => Self::Minutes,
            LifetimeUnit::Hours => Self::Hours,
            LifetimeUnit::Days => Self::Days,
        }
    }
}

impl From<CodecUnit> for LifetimeUnit {
    fn from(unit: CodecUnit) -> Self {
        match unit {
            CodecUnit::Seconds => Self::Seconds,
            CodecUnit::Minutes => Self::Minutes,
            CodecUnit::Hours => Self::Hours,
            CodecUnit::Days => Self::Days,
        }
    }
}

/// What a sent message has been through so far.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum DeliveryStatus {
    /// Stored on this phone and spreading through the mesh.
    Published,
    /// The recipient's acknowledgement came back.
    Delivered,
}

impl From<appcodec::DeliveryStatus> for DeliveryStatus {
    fn from(status: appcodec::DeliveryStatus) -> Self {
        match status {
            appcodec::DeliveryStatus::Published => Self::Published,
            appcodec::DeliveryStatus::Delivered => Self::Delivered,
        }
    }
}

/// An announcement payload read back.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DecodedAnnouncement {
    /// The text, already cleaned.
    pub text: String,
    /// The urgency.
    pub severity: AnnouncementSeverity,
}

/// How much of a byte limit a text uses.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct ByteCount {
    /// Bytes the text uses.
    pub used: u32,
    /// Bytes the text may use.
    pub limit: u32,
    /// Bytes still free; zero when the text is over the limit.
    pub remaining: u32,
    /// Whether the text is too long.
    pub over: bool,
    /// Whether the text is within the last tenth of the room (and not over): time to warn.
    pub near_limit: bool,
}

/// A lifetime shown in the largest unit that divides it evenly.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct LifetimeParts {
    /// How many units.
    pub value: i64,
    /// The unit.
    pub unit: LifetimeUnit,
}

/// The constants of the conventions, for screens that need them.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ConventionInfo {
    /// Length of a contact card in bytes.
    pub card_len: u32,
    /// The text that starts a contact card in text form.
    pub card_prefix: String,
    /// The longest nickname in characters.
    pub max_nickname_chars: u32,
    /// Characters of the short form of a node id.
    pub short_id_len: u32,
    /// What the sealed box of a direct message adds to its text, in bytes.
    pub direct_seal_overhead: u32,
    /// The shortest lifetime in seconds.
    pub min_lifetime_secs: i64,
    /// The lifetime selected by default in seconds.
    pub default_lifetime_secs: i64,
}

fn small(value: usize) -> u32 {
    u32::try_from(value).unwrap_or(u32::MAX)
}

fn seconds_out(value: u64) -> i64 {
    i64::try_from(value).unwrap_or(i64::MAX)
}

/// A negative number of seconds counts as zero.
fn seconds_in(value: i64) -> u64 {
    u64::try_from(value).unwrap_or(0)
}

/// Removes what must never reach a screen from a text written by somebody else: control
/// characters (except line feed and tab) and the bidirectional override characters.
#[uniffi::export]
pub fn sanitize_text(text: String) -> String {
    appcodec::clean(&text)
}

/// Builds the payload of an announcement: the cleaned text, with a two-byte marker in front
/// unless the severity is normal. Check the length with [`announcement_byte_limit`].
#[uniffi::export]
pub fn encode_announcement(text: String, severity: AnnouncementSeverity) -> Vec<u8> {
    appcodec::encode_announcement(&text, severity.into())
}

/// Reads the payload of an announcement. Never fails.
#[uniffi::export]
pub fn decode_announcement(payload: Vec<u8>) -> DecodedAnnouncement {
    let decoded = appcodec::decode_announcement(&payload);
    DecodedAnnouncement {
        text: decoded.text,
        severity: decoded.severity.into(),
    }
}

/// The bytes a severity takes from the payload of an announcement.
#[uniffi::export]
pub fn announcement_overhead(severity: AnnouncementSeverity) -> u32 {
    small(appcodec::announcement_overhead(severity.into()))
}

/// The text form of a contact card (for QR codes and sharing as text).
#[uniffi::export]
pub fn encode_card_text(card: Vec<u8>) -> String {
    appcodec::encode_card(&card)
}

/// The card bytes in a text, or nothing when it is not a card. Forgiving about white space,
/// the prefix and the base64 variant. The card's signature is checked by the core, not here.
#[uniffi::export]
pub fn decode_card_text(text: String) -> Option<Vec<u8>> {
    appcodec::decode_card(&text)
}

/// The longest text of a direct message, given the protocol's payload limit
/// (`CoreInfo.max_payload_bytes`).
#[uniffi::export]
pub fn direct_byte_limit(max_payload_bytes: u32) -> u32 {
    small(appcodec::direct_limit(
        usize::try_from(max_payload_bytes).unwrap_or(usize::MAX),
    ))
}

/// The longest text of an announcement of the given severity, given the protocol's payload
/// limit.
#[uniffi::export]
pub fn announcement_byte_limit(max_payload_bytes: u32, severity: AnnouncementSeverity) -> u32 {
    small(appcodec::announcement_limit(
        usize::try_from(max_payload_bytes).unwrap_or(usize::MAX),
        severity.into(),
    ))
}

/// Counts the UTF-8 bytes of a text against a limit.
#[uniffi::export]
pub fn count_bytes(text: String, limit: u32) -> ByteCount {
    budget_status(small(appcodec::utf8_length(&text)), limit)
}

/// How much of a limit `used` bytes take, for a payload that was already encoded (an
/// announcement counts its marker bytes too, so it is measured in bytes, not as a text).
#[uniffi::export]
pub fn budget_status(used: u32, limit: u32) -> ByteCount {
    let count = appcodec::ByteCount {
        used: usize::try_from(used).unwrap_or(usize::MAX),
        limit: usize::try_from(limit).unwrap_or(usize::MAX),
    };
    ByteCount {
        used,
        limit,
        remaining: small(count.remaining()),
        over: count.over(),
        near_limit: count.near_limit(),
    }
}

/// The lifetime presets (15 minutes ... 7 days) that do not exceed `max_secs`.
#[uniffi::export]
pub fn lifetime_presets(max_secs: i64) -> Vec<i64> {
    appcodec::lifetime_presets(seconds_in(max_secs))
        .into_iter()
        .map(seconds_out)
        .collect()
}

/// `value` times `unit` in seconds. Zero for a value of zero or less; saturates instead of
/// overflowing.
#[uniffi::export]
pub fn lifetime_to_seconds(value: i64, unit: LifetimeUnit) -> i64 {
    seconds_out(appcodec::lifetime_to_seconds(seconds_in(value), unit.into()))
}

/// Whether `secs` is between one second and `max_secs` (`CoreInfo.max_lifetime_secs`).
#[uniffi::export]
pub fn lifetime_is_valid(secs: i64, max_secs: i64) -> bool {
    match (u64::try_from(secs), u64::try_from(max_secs)) {
        (Ok(secs), Ok(max_secs)) => appcodec::valid_lifetime(secs, max_secs),
        _ => false,
    }
}

/// A lifetime in the largest unit that divides it evenly ("15 minutes" instead of "900
/// seconds"). Zero and anything not evenly divisible stays in seconds.
#[uniffi::export]
pub fn split_lifetime(secs: i64) -> LifetimeParts {
    let (value, unit) = appcodec::split_lifetime(seconds_in(secs));
    LifetimeParts {
        value: seconds_out(value),
        unit: unit.into(),
    }
}

/// Normalises a nickname typed by the user: one line, cleaned, trimmed, at most 40 characters.
/// Nothing means "no nickname".
#[uniffi::export]
pub fn normalize_nickname(raw: String) -> Option<String> {
    appcodec::normalize_nickname(&raw)
}

/// The short form (first eight characters) of a node id in hexadecimal.
#[uniffi::export]
pub fn short_node_id(node_id_hex: String) -> String {
    appcodec::short_id(&node_id_hex)
}

/// The delivery status of a message: nothing for a message received from someone else; for a
/// sent message, delivered once a direct message was acknowledged, otherwise published.
#[uniffi::export]
pub fn message_delivery_status(
    outgoing: bool,
    direct: bool,
    delivered: bool,
) -> Option<DeliveryStatus> {
    appcodec::message_status(outgoing, direct, delivered).map(Into::into)
}

/// The constants of the conventions.
#[uniffi::export]
pub fn convention_info() -> ConventionInfo {
    ConventionInfo {
        card_len: small(appcodec::CARD_LEN),
        card_prefix: appcodec::CARD_PREFIX.to_owned(),
        max_nickname_chars: small(appcodec::MAX_NICKNAME_CHARS),
        short_id_len: small(appcodec::SHORT_ID_LEN),
        direct_seal_overhead: small(appcodec::DIRECT_SEAL_OVERHEAD),
        min_lifetime_secs: seconds_out(appcodec::MIN_LIFETIME_SECS),
        default_lifetime_secs: seconds_out(appcodec::DEFAULT_LIFETIME_SECS),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn announcements_round_trip_through_the_boundary() {
        for severity in [
            AnnouncementSeverity::Normal,
            AnnouncementSeverity::Important,
            AnnouncementSeverity::Emergency,
        ] {
            let payload = encode_announcement("water at the school\u{7}".to_owned(), severity);
            let decoded = decode_announcement(payload.clone());
            assert_eq!(decoded.text, "water at the school");
            assert_eq!(decoded.severity, severity);
            let overhead = usize::try_from(announcement_overhead(severity)).unwrap();
            assert_eq!(payload.len(), "water at the school".len() + overhead);
        }
    }

    #[test]
    fn cards_round_trip_and_the_core_accepts_the_text_form() {
        let seed = crate::generate_seed().expect("a seed");
        let node = crate::CrisisNode::in_memory(seed, crate::default_settings(), 1_000)
            .expect("an in-memory node opens");
        let card = node.contact_card();
        let text = encode_card_text(card.clone());
        assert!(text.starts_with(&convention_info().card_prefix));
        assert_eq!(decode_card_text(text), Some(card));
    }

    #[test]
    fn the_constants_agree_with_the_core() {
        let info = convention_info();
        let core = crate::core_info();
        assert_eq!(info.direct_seal_overhead, 48);
        assert_eq!(direct_byte_limit(core.max_payload_bytes), core.max_payload_bytes - 48);
        assert!(lifetime_is_valid(info.default_lifetime_secs, i64::from(core.max_lifetime_secs)));
        assert_eq!(
            lifetime_presets(i64::from(core.max_lifetime_secs)).last().copied(),
            Some(i64::from(core.max_lifetime_secs))
        );
    }

    #[test]
    fn negative_seconds_count_as_zero() {
        assert_eq!(lifetime_to_seconds(-5, LifetimeUnit::Hours), 0);
        assert!(!lifetime_is_valid(-1, 100));
        assert!(lifetime_presets(-1).is_empty());
        assert_eq!(
            split_lifetime(-1),
            LifetimeParts {
                value: 0,
                unit: LifetimeUnit::Seconds
            }
        );
    }

    #[test]
    fn a_payload_size_is_judged_like_a_text() {
        let by_text = count_bytes("x".repeat(95), 100);
        assert_eq!(budget_status(95, 100), by_text);
        assert!(by_text.near_limit && !by_text.over);
        let over = budget_status(101, 100);
        assert!(over.over && !over.near_limit);
        assert_eq!(over.remaining, 0);
    }

    #[test]
    fn counts_and_status() {
        let count = count_bytes("héllo".to_owned(), 6);
        assert_eq!(
            (count.used, count.remaining, count.over, count.near_limit),
            (6, 0, false, true)
        );
        assert_eq!(message_delivery_status(false, true, true), None);
        assert_eq!(
            message_delivery_status(true, true, true),
            Some(DeliveryStatus::Delivered)
        );
    }
}
