//! Nicknames: what the user calls a contact. They are kept on one phone only; the core knows
//! keys, not names.

use crate::sanitize::clean;

/// The longest nickname in characters (Unicode scalar values).
pub const MAX_NICKNAME_CHARS: usize = 40;

/// Normalises a nickname typed by the user: one line (new lines and tabs become spaces), no
/// control characters, trimmed, at most [`MAX_NICKNAME_CHARS`] characters. `None` when nothing
/// is left, which means "no nickname".
///
/// The function is idempotent.
#[must_use]
pub fn normalize_nickname(raw: &str) -> Option<String> {
    let one_line: String = clean(raw)
        .chars()
        .map(|c| if matches!(c, '\n' | '\t') { ' ' } else { c })
        .collect();
    let shortened: String = one_line.trim().chars().take(MAX_NICKNAME_CHARS).collect();
    let nickname = shortened.trim();
    (!nickname.is_empty()).then(|| nickname.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn trims_and_flattens() {
        assert_eq!(normalize_nickname("  Ali  ").as_deref(), Some("Ali"));
        assert_eq!(normalize_nickname("a\tb\nc").as_deref(), Some("a b c"));
    }

    #[test]
    fn empty_means_no_nickname() {
        assert_eq!(normalize_nickname(""), None);
        assert_eq!(normalize_nickname(" \t\n "), None);
        assert_eq!(normalize_nickname("\u{7}"), None);
    }

    #[test]
    fn is_limited_and_trimmed_after_cutting() {
        let long = "x".repeat(60);
        assert_eq!(normalize_nickname(&long).unwrap().chars().count(), 40);
        let cut_at_a_space = format!("{} yyyyy", "x".repeat(39));
        assert_eq!(normalize_nickname(&cut_at_a_space), Some("x".repeat(39)));
    }
}
