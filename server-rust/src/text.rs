//! Small things Kotlin's strings do, done the way Kotlin does them, where a difference would be a
//! difference in what the server says: which characters are blank, how a name is cut, how text is
//! put in order, and how bytes become text.

use sha2::{Digest, Sha256};

/// What Kotlin's `Char.isWhitespace` says: the Unicode separators and the ASCII controls Java
/// counts, and not U+0085, which Rust's own `is_whitespace` would count.
pub fn is_kotlin_whitespace(c: char) -> bool {
    matches!(
        c,
        '\u{0009}'..='\u{000D}'
            | '\u{001C}'..='\u{001F}'
            | '\u{0020}'
            | '\u{00A0}'
            | '\u{1680}'
            | '\u{2000}'..='\u{200A}'
            | '\u{2028}'
            | '\u{2029}'
            | '\u{202F}'
            | '\u{205F}'
            | '\u{3000}'
    )
}

/// Kotlin's `isBlank`: empty, or nothing but whitespace.
pub fn is_blank(text: &str) -> bool {
    text.chars().all(is_kotlin_whitespace)
}

/// Kotlin's `trim`.
pub fn trim(text: &str) -> &str {
    text.trim_matches(is_kotlin_whitespace)
}

/// Kotlin's `take(n)`, which counts UTF-16 units. A character that would be cut in half is left out.
pub fn take_utf16(text: &str, units: usize) -> &str {
    let mut taken = 0;
    for (at, c) in text.char_indices() {
        taken += c.len_utf16();
        if taken > units {
            return &text[..at];
        }
    }
    text
}

/// How long Kotlin says a string is.
pub fn utf16_length(text: &str) -> usize {
    text.chars().map(char::len_utf16).sum()
}

/// Kotlin compares strings by UTF-16 code unit, and the manifest is in that order.
pub fn utf16_order(a: &str, b: &str) -> std::cmp::Ordering {
    a.encode_utf16().cmp(b.encode_utf16())
}

/// The SHA-256 of `bytes` in lower-case hex, as a certificate's fingerprint is written.
pub fn sha256_hex(bytes: &[u8]) -> String {
    hex(&Sha256::digest(bytes))
}

/// The SHA-256 of a file's content, read a piece at a time, so that telling which version a large
/// note is does not take the whole note in memory.
pub fn sha256_hex_of_file(file: &std::path::Path) -> std::io::Result<String> {
    use std::io::Read;
    let mut opened = std::fs::File::open(file)?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0u8; 64 * 1024];
    loop {
        match opened.read(&mut buffer)? {
            0 => return Ok(hex(&hasher.finalize())),
            read => hasher.update(&buffer[..read]),
        }
    }
}

pub fn hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(char::from_digit((byte >> 4) as u32, 16).unwrap());
        out.push(char::from_digit((byte & 0xf) as u32, 16).unwrap());
    }
    out
}

/// `bytes` random bytes as hex, from the system's strong source: tokens, codes, passwords.
pub fn random_hex(bytes: usize) -> String {
    let mut buffer = vec![0u8; bytes];
    getrandom::fill(&mut buffer).expect("the system has no source of random numbers");
    hex(&buffer)
}

/// A random identifier written as Java's `UUID.randomUUID()` writes one.
pub fn random_uuid() -> String {
    let mut b = [0u8; 16];
    getrandom::fill(&mut b).expect("the system has no source of random numbers");
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    let h = hex(&b);
    format!("{}-{}-{}-{}-{}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32])
}

/// Whether two strings are the same, taking as long whatever they hold, for tokens and codes.
pub fn constant_time_equals(a: &str, b: &str) -> bool {
    let (a, b) = (a.as_bytes(), b.as_bytes());
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |difference, (x, y)| difference | (x ^ y)) == 0
}

/// Bytes as the text Kotlin's `decodeToString` makes of them: what is not UTF-8 becomes U+FFFD.
/// No copy is made of bytes that are UTF-8 already.
pub fn decode_utf8(bytes: Vec<u8>) -> String {
    match String::from_utf8(bytes) {
        Ok(text) => text,
        Err(not_utf8) => decode_as_java(not_utf8.as_bytes()),
    }
}

fn is_continuation(byte: u8) -> bool {
    byte & 0xc0 == 0x80
}

/// Bytes that are not all UTF-8 as Java reads them. Rust's own lossy reading agrees with Java on
/// which bytes are wrong, but not always on how many U+FFFD stand for them: a surrogate written in
/// three bytes is one to Java and three to Rust. A note with such bytes in it must be the same text
/// from both servers, so this follows `java.lang.String`'s decoder, case for case.
fn decode_as_java(src: &[u8]) -> String {
    const REPLACEMENT: char = '\u{fffd}';
    let mut out = String::with_capacity(src.len() + 16);
    let end = src.len();
    let mut at = 0;
    while at < end {
        let b1 = src[at];
        at += 1;
        match b1 {
            0x00..=0x7f => out.push(b1 as char),
            // Two bytes. C0 and C1 could only write what one byte writes, and are not leads.
            0xc2..=0xdf => {
                if at == end {
                    out.push(REPLACEMENT);
                    break;
                }
                if is_continuation(src[at]) {
                    out.push(char::from_u32((u32::from(b1 & 0x1f) << 6) | u32::from(src[at] & 0x3f)).unwrap_or(REPLACEMENT));
                    at += 1;
                } else {
                    out.push(REPLACEMENT);
                }
            }
            // Three bytes.
            0xe0..=0xef => {
                let overlong = |b2: u8| b1 == 0xe0 && b2 & 0xe0 == 0x80;
                if at + 1 < end {
                    let (b2, b3) = (src[at], src[at + 1]);
                    if overlong(b2) || !is_continuation(b2) || !is_continuation(b3) {
                        out.push(REPLACEMENT);
                        // The lead alone when the byte after it cannot follow it, and both when it can.
                        at += if overlong(b2) || !is_continuation(b2) { 0 } else { 1 };
                    } else {
                        let code = (u32::from(b1 & 0x0f) << 12) | (u32::from(b2 & 0x3f) << 6) | u32::from(b3 & 0x3f);
                        // A surrogate, which is no character by itself: one replacement for its three bytes.
                        out.push(char::from_u32(code).unwrap_or(REPLACEMENT));
                        at += 2;
                    }
                    continue;
                }
                if at < end && (overlong(src[at]) || !is_continuation(src[at])) {
                    out.push(REPLACEMENT);
                    continue;
                }
                // Cut short by the end of the text.
                out.push(REPLACEMENT);
                break;
            }
            // Four bytes.
            0xf0..=0xf7 => {
                let bad_second = |b2: u8| (b1 == 0xf0 && !(0x90..=0xbf).contains(&b2)) || (b1 == 0xf4 && b2 & 0xf0 != 0x80) || !is_continuation(b2);
                if at + 2 < end {
                    let (b2, b3, b4) = (src[at], src[at + 1], src[at + 2]);
                    let code = (u32::from(b1 & 0x07) << 18) | (u32::from(b2 & 0x3f) << 12) | (u32::from(b3 & 0x3f) << 6) | u32::from(b4 & 0x3f);
                    let malformed = !is_continuation(b2) || !is_continuation(b3) || !is_continuation(b4) || !(0x1_0000..=0x10_ffff).contains(&code);
                    if malformed {
                        out.push(REPLACEMENT);
                        at += if b1 > 0xf4 || bad_second(b2) {
                            0
                        } else if !is_continuation(b3) {
                            1
                        } else {
                            2
                        };
                    } else {
                        out.push(char::from_u32(code).unwrap_or(REPLACEMENT));
                        at += 3;
                    }
                    continue;
                }
                if b1 > 0xf4 || (at < end && bad_second(src[at])) {
                    out.push(REPLACEMENT);
                    continue;
                }
                // Cut short by the end of the text: one replacement for what there is of it.
                at += 1;
                out.push(REPLACEMENT);
                if at < end && !is_continuation(src[at]) {
                    continue;
                }
                break;
            }
            // A continuation with nothing to continue, C0, C1, or F8 and above.
            _ => out.push(REPLACEMENT),
        }
    }
    out
}

/// `&`, `<`, `>` and `"` written so that HTML shows them and does not read them.
pub fn escape_html(text: &str) -> String {
    text.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

/// Percent-encodes everything but letters, digits and `-._~`, as a URI component.
pub fn encode_uri_component(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.bytes() {
        if byte.is_ascii_alphanumeric() || b"-._~".contains(&byte) {
            out.push(byte as char);
        } else {
            out.push('%');
            out.push_str(&format!("{byte:02X}"));
        }
    }
    out
}

/// A `%XX`-encoded piece of a path or a query as text, or None when a `%` is not followed by two
/// hex digits. `plus_is_space` is for queries and forms. Bytes that are not UTF-8 become U+FFFD.
pub fn percent_decode(text: &str, plus_is_space: bool) -> Option<String> {
    let bytes = text.as_bytes();
    if !bytes.contains(&b'%') && !(plus_is_space && bytes.contains(&b'+')) {
        return Some(text.to_string());
    }
    let mut out = Vec::with_capacity(bytes.len());
    let mut at = 0;
    while at < bytes.len() {
        match bytes[at] {
            b'%' => {
                let high = bytes.get(at + 1).and_then(|b| (*b as char).to_digit(16))?;
                let low = bytes.get(at + 2).and_then(|b| (*b as char).to_digit(16))?;
                out.push((high * 16 + low) as u8);
                at += 3;
            }
            b'+' if plus_is_space => {
                out.push(b' ');
                at += 1;
            }
            other => {
                out.push(other);
                at += 1;
            }
        }
    }
    Some(decode_utf8(out))
}

/// A query or a form with a `%` in it that is not followed by two hex digits.
#[derive(Debug, PartialEq, Eq)]
pub struct Undecodable;

/// The first value of `name` in a query string or a form body, decoded; `Err` when anything in it
/// cannot be decoded, which the Kotlin server refuses the whole request for.
pub fn query_value(query: &str, name: &str) -> Result<Option<String>, Undecodable> {
    let mut found = None;
    for part in query.split('&') {
        if part.is_empty() {
            continue;
        }
        let (key, value) = match part.split_once('=') {
            Some((key, value)) => (key, value),
            None => (part, ""),
        };
        let key = percent_decode(key, true).ok_or(Undecodable)?;
        let value = percent_decode(value, true).ok_or(Undecodable)?;
        if key == name && found.is_none() {
            found = Some(value);
        }
    }
    Ok(found)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blank_is_what_kotlin_calls_blank() {
        for blank in ["", " ", "\t\n", "\u{00a0}", "\u{2003}", "\u{001c}", "\u{3000}"] {
            assert!(is_blank(blank), "{blank:?}");
        }
        for not_blank in ["a", " a ", "\u{0085}", "\u{200b}", "\u{feff}"] {
            assert!(!is_blank(not_blank), "{not_blank:?}");
        }
    }

    /// Each of these was put to the Kotlin server and read back; the right-hand side is what it kept.
    #[test]
    fn bytes_that_are_not_utf8_are_read_as_java_reads_them() {
        let read = |bytes: &[u8]| decode_utf8(bytes.to_vec());
        let r = "\u{fffd}";
        assert_eq!(read(b"caf\xc3\xa9 \xe2\x98\x95 \xf0\x9f\x98\x80"), "café ☕ 😀");
        assert_eq!(read(b"\xff\xfe"), r.repeat(2));
        assert_eq!(read(b"\xc3\x28"), format!("{r}("));
        assert_eq!(read(b"\xe2\x82 "), format!("{r} "));
        assert_eq!(read(b"\xf0\x9f\x98 "), format!("{r} "));
        assert_eq!(read(b"\xed\xa0\x80"), r, "a surrogate in three bytes is one to Java, and three to Rust");
        assert_eq!(String::from_utf8_lossy(b"\xed\xa0\x80"), r.repeat(3));
        assert_eq!(read(b"\xf4\x90\x80\x80"), r.repeat(4));
        assert_eq!(read(b"\xc0\xaf"), r.repeat(2));
        assert_eq!(read(b"\xf8\x88\x80\x80\x80"), r.repeat(5));
        assert_eq!(read(b"\xe0\x80\x80"), r.repeat(3));
        assert_eq!(read(b"end \xf0\x9f"), format!("end {r}"));
        assert_eq!(read(b"end \xe2\x82"), format!("end {r}"));
        assert_eq!(read(b"end \xc3"), format!("end {r}"));
        assert_eq!(read(b"end \xf0"), format!("end {r}"));
    }

    #[test]
    fn a_name_is_cut_by_utf16_units_and_never_through_a_character() {
        assert_eq!(take_utf16("abcdef", 3), "abc");
        assert_eq!(take_utf16("ab", 3), "ab");
        assert_eq!(take_utf16("a😀b", 3), "a😀");
        assert_eq!(take_utf16("a😀b", 2), "a");
    }

    #[test]
    fn the_order_is_by_utf16_unit() {
        // U+1F600 is written D83D DE00, which comes before U+FF5E though it is the greater character.
        assert_eq!(utf16_order("😀", "～"), std::cmp::Ordering::Less);
        assert_eq!("😀".cmp("～"), std::cmp::Ordering::Greater);
        assert_eq!(utf16_order("a", "b"), std::cmp::Ordering::Less);
    }

    #[test]
    fn percent_decoding_refuses_what_is_not_hex() {
        assert_eq!(percent_decode("a%20b", false).as_deref(), Some("a b"));
        assert_eq!(percent_decode("a+b", false).as_deref(), Some("a+b"));
        assert_eq!(percent_decode("a+b", true).as_deref(), Some("a b"));
        assert_eq!(percent_decode("%C3%A9", false).as_deref(), Some("é"));
        assert_eq!(percent_decode("bad%FFname", false).as_deref(), Some("bad\u{fffd}name"));
        assert_eq!(percent_decode("%zz", false), None);
        assert_eq!(percent_decode("%2", false), None);
        assert_eq!(percent_decode("%", false), None);
    }

    #[test]
    fn a_query_gives_its_first_value() {
        assert_eq!(query_value("since=%31&since=2", "since"), Ok(Some("1".to_string())));
        assert_eq!(query_value("other=1", "since"), Ok(None));
        assert_eq!(query_value("&&since", "since"), Ok(Some(String::new())));
        assert_eq!(query_value("since=5%", "since"), Err(Undecodable));
        assert_eq!(query_value("x=%zz&since=5", "since"), Err(Undecodable));
    }

    #[test]
    fn a_uuid_looks_like_one() {
        let id = random_uuid();
        assert_eq!(id.len(), 36);
        assert_eq!(&id[14..15], "4");
        assert!(matches!(&id[19..20], "8" | "9" | "a" | "b"));
    }

    #[test]
    fn a_uri_component_keeps_only_what_is_unreserved() {
        assert_eq!(encode_uri_component("Test Laptop"), "Test%20Laptop");
        assert_eq!(encode_uri_component("fe80::1%en0"), "fe80%3A%3A1%25en0");
        assert_eq!(encode_uri_component("a-b._~"), "a-b._~");
        assert_eq!(encode_uri_component("é"), "%C3%A9");
    }
}
