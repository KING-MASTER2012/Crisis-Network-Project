//! Runs the shared test vectors (`core/testdata/appcodec.json`) against this crate.

use appcodec::{
    LifetimeUnit, Severity, announcement_limit, count_bytes, decode_announcement, decode_card,
    direct_limit, encode_announcement, encode_card, lifetime_presets, lifetime_to_seconds,
    message_status, normalize_nickname, short_id, split_lifetime,
};
use serde_json::Value;

fn vectors() -> Value {
    let text = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../testdata/appcodec.json"
    ));
    serde_json::from_str(text).expect("the vector file is valid JSON")
}

fn list<'a>(root: &'a Value, name: &str) -> &'a [Value] {
    let cases = root[name]
        .as_array()
        .unwrap_or_else(|| panic!("`{name}` is a list"));
    assert!(!cases.is_empty(), "`{name}` has cases");
    cases
}

fn text<'a>(case: &'a Value, key: &str) -> &'a str {
    case[key]
        .as_str()
        .unwrap_or_else(|| panic!("`{key}` is a string in {case}"))
}

fn number(case: &Value, key: &str) -> u64 {
    case[key]
        .as_u64()
        .unwrap_or_else(|| panic!("`{key}` is a number in {case}"))
}

fn flag(case: &Value, key: &str) -> bool {
    case[key]
        .as_bool()
        .unwrap_or_else(|| panic!("`{key}` is a bool in {case}"))
}

fn size(case: &Value, key: &str) -> usize {
    usize::try_from(number(case, key)).expect("fits in usize")
}

fn bytes(hex: &str) -> Vec<u8> {
    assert!(hex.len().is_multiple_of(2), "even hex length: {hex}");
    (0..hex.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).expect("hex digits"))
        .collect()
}

fn severity(case: &Value) -> Severity {
    match number(case, "severity") {
        0 => Severity::Normal,
        1 => Severity::Important,
        2 => Severity::Emergency,
        other => panic!("unknown severity {other}"),
    }
}

fn unit(name: &str) -> LifetimeUnit {
    match name {
        "seconds" => LifetimeUnit::Seconds,
        "minutes" => LifetimeUnit::Minutes,
        "hours" => LifetimeUnit::Hours,
        "days" => LifetimeUnit::Days,
        other => panic!("unknown unit {other}"),
    }
}

#[test]
fn sanitize() {
    for case in list(&vectors(), "sanitize") {
        assert_eq!(
            appcodec::clean(text(case, "input")),
            text(case, "output"),
            "{case}"
        );
    }
}

#[test]
fn announcement_encode() {
    for case in list(&vectors(), "announcement_encode") {
        let payload = encode_announcement(text(case, "text"), severity(case));
        assert_eq!(payload, bytes(text(case, "hex")), "{case}");
    }
}

#[test]
fn announcement_decode() {
    for case in list(&vectors(), "announcement_decode") {
        let decoded = decode_announcement(&bytes(text(case, "hex")));
        assert_eq!(decoded.text, text(case, "text"), "{case}");
        assert_eq!(decoded.severity, severity(case), "{case}");
    }
}

#[test]
fn card_encode() {
    for case in list(&vectors(), "card_encode") {
        assert_eq!(
            encode_card(&bytes(text(case, "hex"))),
            text(case, "text"),
            "{case}"
        );
    }
}

#[test]
fn card_decode() {
    for case in list(&vectors(), "card_decode") {
        let expected = case["hex"].as_str().map(bytes);
        assert_eq!(decode_card(text(case, "input")), expected, "{case}");
    }
}

#[test]
fn nickname() {
    for case in list(&vectors(), "nickname") {
        let expected = case["output"].as_str();
        assert_eq!(
            normalize_nickname(text(case, "input")).as_deref(),
            expected,
            "{case}"
        );
    }
}

#[test]
fn split_vectors() {
    for case in list(&vectors(), "lifetime_split") {
        assert_eq!(
            split_lifetime(number(case, "secs")),
            (number(case, "value"), unit(text(case, "unit"))),
            "{case}"
        );
    }
}

#[test]
fn to_seconds_vectors() {
    for case in list(&vectors(), "lifetime_to_seconds") {
        assert_eq!(
            lifetime_to_seconds(number(case, "value"), unit(text(case, "unit"))),
            number(case, "secs"),
            "{case}"
        );
    }
}

#[test]
fn presets_vectors() {
    for case in list(&vectors(), "lifetime_presets") {
        let expected: Vec<u64> = case["presets"]
            .as_array()
            .expect("a list")
            .iter()
            .map(|v| v.as_u64().expect("a number"))
            .collect();
        assert_eq!(
            lifetime_presets(number(case, "max_secs")),
            expected,
            "{case}"
        );
    }
}

#[test]
fn byte_budget() {
    for case in list(&vectors(), "byte_budget") {
        let max = size(case, "max_payload");
        let actual = match text(case, "kind") {
            "direct_limit" => direct_limit(max),
            "announcement_limit" => announcement_limit(max, severity(case)),
            other => panic!("unknown kind {other}"),
        };
        assert_eq!(actual, size(case, "limit"), "{case}");
    }
}

#[test]
fn byte_count() {
    for case in list(&vectors(), "byte_count") {
        let count = count_bytes(text(case, "text"), size(case, "limit"));
        assert_eq!(count.used, size(case, "used"), "{case}");
        assert_eq!(count.remaining(), size(case, "remaining"), "{case}");
        assert_eq!(count.over(), flag(case, "over"), "{case}");
        assert_eq!(count.near_limit(), flag(case, "near_limit"), "{case}");
    }
}

#[test]
fn delivery_status() {
    for case in list(&vectors(), "message_status") {
        let status = message_status(
            flag(case, "outgoing"),
            flag(case, "direct"),
            flag(case, "delivered"),
        );
        let expected = match case["status"].as_str() {
            None => None,
            Some("published") => Some(appcodec::DeliveryStatus::Published),
            Some("delivered") => Some(appcodec::DeliveryStatus::Delivered),
            Some(other) => panic!("unknown status {other}"),
        };
        assert_eq!(status, expected, "{case}");
    }
}

#[test]
fn short_ids() {
    for case in list(&vectors(), "short_id") {
        assert_eq!(
            short_id(text(case, "input")),
            text(case, "output"),
            "{case}"
        );
    }
}
