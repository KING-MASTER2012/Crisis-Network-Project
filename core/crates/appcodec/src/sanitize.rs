//! Making text from other phones safe to show.

/// Removes what must never reach a screen from a text written by somebody else.
///
/// Dropped characters:
///
/// * control characters `U+0000..=U+001F` and `U+007F`, **except** the line feed and the
///   tab (so a carriage return is dropped too);
/// * the bidirectional embedding and override characters `U+202A..=U+202E`;
/// * the bidirectional isolate characters `U+2066..=U+2069`.
///
/// The last two groups can make a text read as something else than what it is. Everything
/// else is kept, including the C1 controls (`U+0080..=U+009F`) and every printable character
/// of every script.
///
/// The function is idempotent: cleaning a clean text changes nothing.
#[must_use]
pub fn clean(text: &str) -> String {
    text.chars().filter(|&c| is_allowed(c)).collect()
}

fn is_allowed(c: char) -> bool {
    !matches!(
        c,
        '\u{0}'..='\u{8}'
            | '\u{B}'..='\u{1F}'
            | '\u{7F}'
            | '\u{202A}'..='\u{202E}'
            | '\u{2066}'..='\u{2069}'
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_new_lines_and_tabs() {
        assert_eq!(clean("a\nb\tc"), "a\nb\tc");
    }

    #[test]
    fn drops_controls_including_carriage_return() {
        assert_eq!(clean("a\u{0}b\u{7}c\rd\u{7F}e"), "abcde");
    }

    #[test]
    fn drops_direction_tricks() {
        assert_eq!(clean("\u{202E}evil\u{2066}x\u{2069}"), "evilx");
    }

    #[test]
    fn keeps_c1_controls_and_astral_characters() {
        assert_eq!(clean("\u{85}\u{1F600}"), "\u{85}\u{1F600}");
    }
}
