//! How a node is called on screen when nobody gave it a nickname.

/// Characters of the short form of a node id.
pub const SHORT_ID_LEN: usize = 8;

/// The first [`SHORT_ID_LEN`] characters of a node id in hexadecimal: enough to tell nodes
/// apart at a glance. A shorter text is returned as it is.
#[must_use]
pub fn short_id(node_id_hex: &str) -> String {
    node_id_hex.chars().take(SHORT_ID_LEN).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn takes_the_first_eight_characters() {
        assert_eq!(short_id("0123456789abcdef"), "01234567");
        assert_eq!(short_id("abc"), "abc");
        assert_eq!(short_id(""), "");
    }
}
