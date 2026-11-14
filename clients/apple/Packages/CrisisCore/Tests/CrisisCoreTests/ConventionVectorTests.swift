import CrisisCore
import Foundation
import XCTest

/// Phase I5: the app conventions of the core (`appcodec`, exported through UniFFI) agree with the shared test vectors in
/// `core/testdata/appcodec.json`, the same file the Rust tests and the Android client read. A client and the core can
/// therefore never disagree silently.
///
/// The expected values were produced by an independent implementation of the Android client's rules, not by the Rust
/// code under test (see `core/testdata/README.md`).
final class ConventionVectorTests: XCTestCase {
    // MARK: Loading

    private static let vectors: [String: [[String: Any]]] = {
        guard let url = locateVectors(), let data = try? Data(contentsOf: url),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: [[String: Any]]]
        else { return [:] }
        return object
    }()

    /// Walks up from this file to the repository root (the folder that has `core/testdata/appcodec.json`).
    private static func locateVectors(file: String = #filePath) -> URL? {
        var folder = URL(fileURLWithPath: file).deletingLastPathComponent()
        for _ in 0..<12 {
            let candidate = folder.appendingPathComponent("core/testdata/appcodec.json")
            if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            folder.deleteLastPathComponent()
        }
        return nil
    }

    private func cases(_ name: String, file: StaticString = #filePath, line: UInt = #line) throws -> [[String: Any]] {
        let all = Self.vectors
        guard !all.isEmpty else {
            XCTFail("core/testdata/appcodec.json was not found or cannot be read", file: file, line: line)
            return []
        }
        let list = try XCTUnwrap(all[name], "the vector group \(name) is missing", file: file, line: line)
        XCTAssertFalse(list.isEmpty, "the vector group \(name) is empty", file: file, line: line)
        return list
    }

    private static func bytes(_ hex: String) -> Data {
        var data = Data()
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            data.append(UInt8(hex[index..<next], radix: 16) ?? 0)
            index = next
        }
        return data
    }

    private static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    private static func severity(_ value: Int) -> AnnouncementSeverity {
        switch value {
        case 1: return .important
        case 2: return .emergency
        default: return .normal
        }
    }

    private static func unit(_ name: String) -> LifetimeUnit {
        switch name {
        case "minutes": return .minutes
        case "hours": return .hours
        case "days": return .days
        default: return .seconds
        }
    }

    private static func unitName(_ unit: LifetimeUnit) -> String {
        switch unit {
        case .seconds: return "seconds"
        case .minutes: return "minutes"
        case .hours: return "hours"
        case .days: return "days"
        }
    }

    // MARK: Texts and announcements

    func testSanitize() throws {
        for item in try cases("sanitize") {
            let input = item["input"] as? String ?? ""
            XCTAssertEqual(sanitizeText(text: input), item["output"] as? String, "sanitize \(input.debugDescription)")
        }
    }

    func testAnnouncementEncode() throws {
        for item in try cases("announcement_encode") {
            let text = item["text"] as? String ?? ""
            let level = Self.severity(item["severity"] as? Int ?? 0)
            let payload = encodeAnnouncement(text: text, severity: level)
            XCTAssertEqual(Self.hex(payload), item["hex"] as? String, "encode \(text.debugDescription) \(level)")
        }
    }

    func testAnnouncementDecode() throws {
        for item in try cases("announcement_decode") {
            let hex = item["hex"] as? String ?? ""
            let decoded = decodeAnnouncement(payload: Self.bytes(hex))
            XCTAssertEqual(decoded.text, item["text"] as? String, "decode \(hex)")
            XCTAssertEqual(decoded.severity, Self.severity(item["severity"] as? Int ?? 0), "decode \(hex)")
        }
    }

    // MARK: Cards

    func testCardEncode() throws {
        for item in try cases("card_encode") {
            let card = Self.bytes(item["hex"] as? String ?? "")
            XCTAssertEqual(encodeCardText(card: card), item["text"] as? String)
        }
    }

    func testCardDecode() throws {
        for item in try cases("card_decode") {
            let input = item["input"] as? String ?? ""
            let expected = (item["hex"] as? String).map(Self.bytes)
            XCTAssertEqual(decodeCardText(text: input), expected, "decode card \(input.debugDescription)")
        }
    }

    // MARK: Nicknames and short ids

    func testNickname() throws {
        for item in try cases("nickname") {
            let input = item["input"] as? String ?? ""
            XCTAssertEqual(normalizeNickname(raw: input), item["output"] as? String, "nickname \(input.debugDescription)")
        }
    }

    func testShortId() throws {
        for item in try cases("short_id") {
            let input = item["input"] as? String ?? ""
            XCTAssertEqual(shortNodeId(nodeIdHex: input), item["output"] as? String, "short id \(input)")
        }
    }

    // MARK: Lifetimes

    func testLifetimeSplit() throws {
        for item in try cases("lifetime_split") {
            let secs = (item["secs"] as? NSNumber)?.int64Value ?? 0
            let parts = splitLifetime(secs: secs)
            XCTAssertEqual(parts.value, (item["value"] as? NSNumber)?.int64Value, "split \(secs)")
            XCTAssertEqual(Self.unitName(parts.unit), item["unit"] as? String, "split \(secs)")
        }
    }

    func testLifetimeToSeconds() throws {
        for item in try cases("lifetime_to_seconds") {
            let value = (item["value"] as? NSNumber)?.int64Value ?? 0
            let unit = Self.unit(item["unit"] as? String ?? "")
            XCTAssertEqual(lifetimeToSeconds(value: value, unit: unit), (item["secs"] as? NSNumber)?.int64Value, "\(value) \(unit)")
        }
    }

    func testLifetimePresets() throws {
        for item in try cases("lifetime_presets") {
            let max = (item["max_secs"] as? NSNumber)?.int64Value ?? 0
            let expected = (item["presets"] as? [NSNumber] ?? []).map(\.int64Value)
            XCTAssertEqual(lifetimePresets(maxSecs: max), expected, "presets up to \(max)")
        }
    }

    // MARK: Byte budgets

    func testByteBudgets() throws {
        for item in try cases("byte_budget") {
            let maxPayload = UInt32((item["max_payload"] as? NSNumber)?.uint32Value ?? 0)
            let expected = (item["limit"] as? NSNumber)?.uint32Value
            switch item["kind"] as? String {
            case "direct_limit":
                XCTAssertEqual(directByteLimit(maxPayloadBytes: maxPayload), expected, "direct limit \(maxPayload)")
            case "announcement_limit":
                let level = Self.severity(item["severity"] as? Int ?? 0)
                XCTAssertEqual(announcementByteLimit(maxPayloadBytes: maxPayload, severity: level), expected, "announcement limit \(maxPayload) \(level)")
            default:
                XCTFail("unknown budget kind \(String(describing: item["kind"]))")
            }
        }
    }

    func testByteCounts() throws {
        for item in try cases("byte_count") {
            let text = item["text"] as? String ?? ""
            let limit = (item["limit"] as? NSNumber)?.uint32Value ?? 0
            let count = countBytes(text: text, limit: limit)
            XCTAssertEqual(count.used, (item["used"] as? NSNumber)?.uint32Value, "used of \(text.debugDescription)")
            XCTAssertEqual(count.remaining, (item["remaining"] as? NSNumber)?.uint32Value, "remaining of \(text.debugDescription)")
            XCTAssertEqual(count.over, item["over"] as? Bool, "over for \(text.debugDescription)")
            XCTAssertEqual(count.nearLimit, item["near_limit"] as? Bool, "near limit for \(text.debugDescription)")
        }
    }

    // MARK: Delivery status

    func testMessageStatus() throws {
        for item in try cases("message_status") {
            let status = messageDeliveryStatus(
                outgoing: item["outgoing"] as? Bool ?? false,
                direct: item["direct"] as? Bool ?? false,
                delivered: item["delivered"] as? Bool ?? false
            )
            switch item["status"] as? String {
            case nil: XCTAssertNil(status, "\(item)")
            case "published": XCTAssertEqual(status, .published, "\(item)")
            case "delivered": XCTAssertEqual(status, .delivered, "\(item)")
            default: XCTFail("unknown status in \(item)")
            }
        }
    }
}
