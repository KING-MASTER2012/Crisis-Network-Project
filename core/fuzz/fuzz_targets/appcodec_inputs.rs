//! App conventions: decoding arbitrary bytes and text never panics, and what decodes is stable.
#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let decoded = appcodec::decode_announcement(data);
    let again = appcodec::decode_announcement(&appcodec::encode_announcement(
        &decoded.text,
        decoded.severity,
    ));
    assert_eq!(again, decoded);

    if let Ok(text) = std::str::from_utf8(data) {
        let cleaned = appcodec::clean(text);
        assert_eq!(appcodec::clean(&cleaned), cleaned);

        if let Some(card) = appcodec::decode_card(text) {
            assert_eq!(card.len(), appcodec::CARD_LEN);
            assert_eq!(appcodec::decode_card(&appcodec::encode_card(&card)), Some(card));
        }

        if let Some(nickname) = appcodec::normalize_nickname(text) {
            assert!(nickname.chars().count() <= appcodec::MAX_NICKNAME_CHARS);
            assert_eq!(
                appcodec::normalize_nickname(&nickname).as_deref(),
                Some(nickname.as_str())
            );
        }
    }
});
