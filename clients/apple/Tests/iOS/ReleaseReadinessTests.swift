import XCTest
@testable import CrisisNetwork_iOS

/// Phase I9: what an App Store submission and a screen reader look at. The privacy manifest, the localized permission
/// texts and the export compliance answer are things of the **built app**, so these tests read them from the app bundle
/// that hosts the tests; the home status that a screen reader announces is plain logic.
final class ReleaseReadinessTests: XCTestCase {
    // MARK: The status that VoiceOver announces

    func testTheHomeStatusFollowsTheMesh() {
        XCTAssertEqual(HomeStatus.make(busy: true, running: false, neighbours: 0), .starting)
        XCTAssertEqual(HomeStatus.make(busy: true, running: true, neighbours: 3), .stopping)
        XCTAssertEqual(HomeStatus.make(busy: false, running: false, neighbours: 0), .offline)
        XCTAssertEqual(HomeStatus.make(busy: false, running: true, neighbours: 0), .searching)
        XCTAssertEqual(HomeStatus.make(busy: false, running: true, neighbours: 2), .connected)
    }

    func testOnlyAStateThatLastsIsAnnounced() {
        XCTAssertFalse(HomeStatus.starting.isAnnounced)
        XCTAssertFalse(HomeStatus.stopping.isAnnounced)
        for status in [HomeStatus.offline, .searching, .connected] {
            XCTAssertTrue(status.isAnnounced, "\(status)")
        }
    }

    func testTheHomeStatusIsGreenOnlyWhenConnected() {
        XCTAssertEqual(HomeStatus.connected.tone, .success)
        for status in [HomeStatus.starting, .stopping, .offline, .searching] {
            XCTAssertEqual(status.tone, .neutral, "\(status)")
        }
    }

    func testEveryHomeStatusHasASentenceInBothLanguages() {
        for status in [HomeStatus.starting, .stopping, .offline, .searching, .connected] {
            XCTAssertNotEqual(L10n.string(status.key, language: .english), status.key, "\(status.key) is missing in English")
            XCTAssertNotEqual(L10n.string(status.key, language: .turkish), status.key, "\(status.key) is missing in Turkish")
        }
    }

    // MARK: The privacy manifest

    private func manifest() throws -> [String: Any] {
        let url = try XCTUnwrap(
            Bundle.main.url(forResource: "PrivacyInfo", withExtension: "xcprivacy"),
            "PrivacyInfo.xcprivacy is not in the app bundle (App/Shared/Resources)"
        )
        let data = try Data(contentsOf: url)
        return try XCTUnwrap(PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any])
    }

    func testTheAppSaysItDoesNotTrackAndCollectsNothing() throws {
        let plist = try manifest()
        XCTAssertEqual(plist["NSPrivacyTracking"] as? Bool, false)
        XCTAssertEqual((plist["NSPrivacyTrackingDomains"] as? [Any])?.count, 0)
        XCTAssertEqual((plist["NSPrivacyCollectedDataTypes"] as? [Any])?.count, 0)
    }

    func testTheManifestGivesAReasonForEveryRequiredReasonApi() throws {
        let plist = try manifest()
        let types = try XCTUnwrap(plist["NSPrivacyAccessedAPITypes"] as? [[String: Any]])
        var reasons: [String: [String]] = [:]
        for type in types {
            let category = try XCTUnwrap(type["NSPrivacyAccessedAPIType"] as? String)
            reasons[category] = type["NSPrivacyAccessedAPITypeReasons"] as? [String]
        }
        // The APIs the app calls: its settings, the files of the node, the clock of the node, the volume the node writes
        // to. Keep in step with scripts/check-privacy-manifest.sh, which compares the manifest with the built binary.
        XCTAssertEqual(reasons["NSPrivacyAccessedAPICategoryUserDefaults"], ["CA92.1"])
        XCTAssertEqual(reasons["NSPrivacyAccessedAPICategoryFileTimestamp"], ["C617.1"])
        XCTAssertEqual(reasons["NSPrivacyAccessedAPICategorySystemBootTime"], ["35F9.1"])
        XCTAssertEqual(reasons["NSPrivacyAccessedAPICategoryDiskSpace"], ["E174.1"])
        XCTAssertEqual(reasons.count, 4)
    }

    // MARK: What the system shows when it asks for a permission

    func testThePermissionTextsAreInTheInfoPlistAndTranslated() throws {
        for key in ["NSBluetoothAlwaysUsageDescription", "NSCameraUsageDescription"] {
            let value = Bundle.main.object(forInfoDictionaryKey: key) as? String
            XCTAssertFalse((value ?? "").isEmpty, "\(key) is missing from Info.plist")
        }
        var texts: [String: Set<String>] = [:]
        for language in ["en", "tr"] {
            let path = try XCTUnwrap(Bundle.main.path(forResource: language, ofType: "lproj"), "no \(language).lproj in the app")
            let bundle = try XCTUnwrap(Bundle(path: path))
            for key in ["NSBluetoothAlwaysUsageDescription", "NSCameraUsageDescription", "CFBundleDisplayName"] {
                let text = bundle.localizedString(forKey: key, value: "MISSING", table: "InfoPlist")
                XCTAssertNotEqual(text, "MISSING", "\(key) has no \(language) text in InfoPlist.xcstrings")
                texts[key, default: []].insert(text)
            }
        }
        // The two languages say the permission texts differently (the display name may be the same in both).
        XCTAssertEqual(texts["NSBluetoothAlwaysUsageDescription"]?.count, 2)
        XCTAssertEqual(texts["NSCameraUsageDescription"]?.count, 2)
    }

    // MARK: Export compliance

    func testTheExportComplianceAnswerIsDeliberate() {
        // The app has encryption of its own, so the answer is yes (clients/apple/EXPORT_COMPLIANCE.md). If this changes,
        // that file changes with it, and so does EXPECT_NON_EXEMPT_ENCRYPTION in scripts/check-mac-app.sh.
        XCTAssertEqual(Bundle.main.object(forInfoDictionaryKey: "ITSAppUsesNonExemptEncryption") as? Bool, true)
    }
}
