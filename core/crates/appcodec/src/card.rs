//! The text form of a contact card, used in QR codes and when a card is shared as text.
//!
//! A card is [`CARD_LEN`] bytes (a public key and its signature: nothing secret). As text it
//! is the prefix [`CARD_PREFIX`] followed by the card in URL-safe base64 without padding
//! (171 characters), which fits a QR code of version 8 at the lowest error correction level:
//! few, large modules that scan easily.
//!
//! [`decode_card`] is forgiving: it ignores white space and the case of the prefix, the prefix
//! may be missing, padding is accepted, and standard base64 is accepted as well.

use crate::base64::{STANDARD, URL_SAFE, decode, encode_url_safe_no_pad};

/// Length of a contact card in bytes.
pub const CARD_LEN: usize = 128;

/// The text that starts a card in text form.
pub const CARD_PREFIX: &str = "crisis:";

/// The text form of a card: [`CARD_PREFIX`] and the base64 of the bytes.
#[must_use]
pub fn encode_card(card: &[u8]) -> String {
    let mut text = String::from(CARD_PREFIX);
    text.push_str(&encode_url_safe_no_pad(card));
    text
}

/// The card bytes in `text`, or `None` when it is not a card (wrong characters or a length
/// other than [`CARD_LEN`]). This does not check the card's signature; that is the core's job.
#[must_use]
pub fn decode_card(text: &str) -> Option<Vec<u8>> {
    let cleaned: String = text.chars().filter(|c| !c.is_whitespace()).collect();
    let has_prefix = cleaned
        .get(..CARD_PREFIX.len())
        .is_some_and(|start| start.eq_ignore_ascii_case(CARD_PREFIX));
    let body = if has_prefix {
        &cleaned[CARD_PREFIX.len()..]
    } else {
        cleaned.as_str()
    };
    if body.is_empty() {
        return None;
    }
    let bytes = decode(body, URL_SAFE).or_else(|| decode(body, STANDARD))?;
    (bytes.len() == CARD_LEN).then_some(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Vec<u8> {
        (0..CARD_LEN)
            .map(|i| u8::try_from(i * 7 % 256).unwrap())
            .collect()
    }

    #[test]
    fn round_trips() {
        let card = sample();
        let text = encode_card(&card);
        assert!(text.starts_with(CARD_PREFIX));
        assert_eq!(text.len(), CARD_PREFIX.len() + 171);
        assert_eq!(decode_card(&text), Some(card));
    }

    #[test]
    fn is_forgiving() {
        let card = sample();
        let text = encode_card(&card);
        let body = &text[CARD_PREFIX.len()..];
        assert_eq!(decode_card(body), Some(card.clone()));
        assert_eq!(
            decode_card(&format!("CRISIS: {body}\n")),
            Some(card.clone())
        );
        assert_eq!(decode_card(&format!("{text}=")), Some(card));
    }

    #[test]
    fn rejects_what_is_not_a_card() {
        assert_eq!(decode_card(""), None);
        assert_eq!(decode_card("crisis:"), None);
        assert_eq!(decode_card("crisis:!!!"), None);
        assert_eq!(decode_card(&encode_card(&sample()[..127])), None);
    }
}
