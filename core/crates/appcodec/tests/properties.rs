//! Property tests: the conventions round-trip, are idempotent and never panic.

use appcodec::{
    CARD_LEN, CARD_PREFIX, LifetimeUnit, MAX_NICKNAME_CHARS, Severity, clean, decode_announcement,
    decode_card, encode_announcement, encode_card, lifetime_to_seconds, normalize_nickname,
    split_lifetime,
};
use proptest::collection::vec;
use proptest::prelude::*;

/// What `clean` must leave in a text. A function, not a closure inside `prop_assert!`: that macro
/// turns the text of its argument into a format string, and braces (`'\u{7F}'`, a closure body)
/// are not valid there.
fn is_allowed(c: char) -> bool {
    if c == '\n' || c == '\t' {
        return true;
    }
    let forbidden = c < ' '
        || c == '\u{7F}'
        || ('\u{202A}'..='\u{202E}').contains(&c)
        || ('\u{2066}'..='\u{2069}').contains(&c);
    !forbidden
}

fn arb_severity() -> impl Strategy<Value = Severity> {
    prop_oneof![
        Just(Severity::Normal),
        Just(Severity::Important),
        Just(Severity::Emergency)
    ]
}

proptest! {
    #[test]
    fn clean_is_idempotent_and_leaves_no_forbidden_character(text in any::<String>()) {
        let once = clean(&text);
        prop_assert_eq!(clean(&once), once.clone());
        prop_assert!(once.chars().all(is_allowed));
    }

    #[test]
    fn announcements_round_trip(text in any::<String>(), severity in arb_severity()) {
        let decoded = decode_announcement(&encode_announcement(&text, severity));
        prop_assert_eq!(decoded.text, clean(&text));
        prop_assert_eq!(decoded.severity, severity);
    }

    #[test]
    fn decoding_an_announcement_never_panics_and_is_stable(payload in vec(any::<u8>(), 0..64)) {
        let decoded = decode_announcement(&payload);
        let again = decode_announcement(&encode_announcement(&decoded.text, decoded.severity));
        prop_assert_eq!(again, decoded);
    }

    #[test]
    fn cards_round_trip(card in vec(any::<u8>(), CARD_LEN)) {
        let text = encode_card(&card);
        prop_assert_eq!(decode_card(&text), Some(card.clone()));
        let spaced = text.replace(CARD_PREFIX, "  CRISIS:\n ");
        prop_assert_eq!(decode_card(&spaced), Some(card));
    }

    #[test]
    fn cards_of_another_length_are_rejected(card in vec(any::<u8>(), 0..300)) {
        prop_assume!(card.len() != CARD_LEN);
        prop_assert_eq!(decode_card(&encode_card(&card)), None);
    }

    #[test]
    fn decoding_a_card_never_panics(text in any::<String>()) {
        if let Some(card) = decode_card(&text) {
            prop_assert_eq!(card.len(), CARD_LEN);
        }
    }

    #[test]
    fn nicknames_are_short_clean_and_stable(raw in any::<String>()) {
        if let Some(nickname) = normalize_nickname(&raw) {
            prop_assert!(nickname.chars().count() <= MAX_NICKNAME_CHARS);
            prop_assert_eq!(nickname.trim(), nickname.as_str());
            prop_assert!(!nickname.contains(['\n', '\t']));
            prop_assert_eq!(normalize_nickname(&nickname), Some(nickname));
        }
    }

    #[test]
    fn lifetimes_survive_splitting(secs in 1u64..=u64::from(u32::MAX)) {
        let (value, unit) = split_lifetime(secs);
        prop_assert_eq!(lifetime_to_seconds(value, unit), secs);
        prop_assert!(LifetimeUnit::ALL.contains(&unit));
    }
}
