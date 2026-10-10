//! Tiny hexadecimal helpers shared by the tools built on the engine.

const DIGITS: &[u8; 16] = b"0123456789abcdef";

/// Lowercase hexadecimal encoding.
pub fn encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push(char::from(DIGITS[usize::from(b >> 4)]));
        out.push(char::from(DIGITS[usize::from(b & 0x0f)]));
    }
    out
}

/// Decodes hexadecimal text (surrounding whitespace is ignored). Returns `None` for
/// an odd length or a non-hex character.
pub fn decode(text: &str) -> Option<Vec<u8>> {
    let text = text.trim();
    if text.len() & 1 == 1 {
        return None;
    }
    let mut out = Vec::with_capacity(text.len() / 2);
    for pair in text.as_bytes().chunks(2) {
        let hi = char::from(pair[0]).to_digit(16)?;
        let lo = char::from(pair[1]).to_digit(16)?;
        out.push(u8::try_from(hi * 16 + lo).ok()?);
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_and_rejections() {
        let bytes = [0x00, 0x0f, 0xa5, 0xff];
        assert_eq!(encode(&bytes), "000fa5ff");
        assert_eq!(decode("000fa5ff").unwrap(), bytes);
        assert_eq!(decode("  000FA5FF\n").unwrap(), bytes);
        assert!(decode("abc").is_none());
        assert!(decode("zz").is_none());
        assert_eq!(decode("").unwrap(), Vec::<u8>::new());
    }
}
