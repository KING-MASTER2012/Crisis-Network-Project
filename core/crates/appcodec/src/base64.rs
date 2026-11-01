//! Base64, just enough for contact cards: URL-safe encoding without padding, and a lenient
//! decoder for both alphabets. Private: the rest of the workspace has no use for it.

/// The URL-safe alphabet (`-` and `_`).
pub(crate) const URL_SAFE: &[u8; 64] =
    b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/// The standard alphabet (`+` and `/`).
pub(crate) const STANDARD: &[u8; 64] =
    b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/// Encodes with the URL-safe alphabet and no padding.
pub(crate) fn encode_url_safe_no_pad(data: &[u8]) -> String {
    let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
    for chunk in data.chunks(3) {
        let second = chunk.get(1).copied();
        let third = chunk.get(2).copied();
        let group = (u32::from(chunk[0]) << 16)
            | (u32::from(second.unwrap_or(0)) << 8)
            | u32::from(third.unwrap_or(0));
        out.push(symbol(group >> 18));
        out.push(symbol(group >> 12));
        if second.is_some() {
            out.push(symbol(group >> 6));
        }
        if third.is_some() {
            out.push(symbol(group));
        }
    }
    out
}

fn symbol(bits: u32) -> char {
    char::from(URL_SAFE[usize::from(u8::try_from(bits & 0x3F).unwrap_or(0))])
}

/// Decodes `input` with `alphabet`. Up to two `=` at the end are accepted (and then the
/// length must be a multiple of four); the unused trailing bits are ignored. `None` for any
/// character outside the alphabet or an impossible length.
pub(crate) fn decode(input: &str, alphabet: &[u8; 64]) -> Option<Vec<u8>> {
    let bytes = input.as_bytes();
    let padding = bytes.iter().rev().take_while(|&&b| b == b'=').count();
    if padding > 2 {
        return None;
    }
    let body = &bytes[..bytes.len() - padding];
    if body.len() % 4 == 1 || (padding > 0 && !(body.len() + padding).is_multiple_of(4)) {
        return None;
    }

    let mut lookup = [u8::MAX; 256];
    for (value, &symbol) in (0u8..).zip(alphabet.iter()) {
        lookup[usize::from(symbol)] = value;
    }

    let mut out = Vec::with_capacity(body.len() * 3 / 4);
    let mut acc: u32 = 0;
    let mut bits: u32 = 0;
    for &b in body {
        let value = lookup[usize::from(b)];
        if value == u8::MAX {
            return None;
        }
        acc = (acc << 6) | u32::from(value);
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push(u8::try_from((acc >> bits) & 0xFF).unwrap_or(0));
            acc &= (1u32 << bits) - 1;
        }
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rfc_4648_vectors() {
        for (plain, encoded) in [
            ("", ""),
            ("f", "Zg"),
            ("fo", "Zm8"),
            ("foo", "Zm9v"),
            ("foob", "Zm9vYg"),
            ("fooba", "Zm9vYmE"),
            ("foobar", "Zm9vYmFy"),
        ] {
            assert_eq!(encode_url_safe_no_pad(plain.as_bytes()), encoded);
            assert_eq!(decode(encoded, URL_SAFE).unwrap(), plain.as_bytes());
        }
    }

    #[test]
    fn accepts_padding_and_rejects_bad_input() {
        assert_eq!(decode("Zg==", URL_SAFE).unwrap(), b"f");
        assert_eq!(decode("Zm8=", URL_SAFE).unwrap(), b"fo");
        assert!(decode("Zg=", URL_SAFE).is_none());
        assert!(decode("Z", URL_SAFE).is_none());
        assert!(decode("Zg===", URL_SAFE).is_none());
        assert!(decode("Zm+v", URL_SAFE).is_none());
        assert!(decode("Zm-v", STANDARD).is_none());
        assert_eq!(decode("Zm+v", STANDARD).unwrap(), [0x66, 0x6F, 0xAF]);
    }
}
