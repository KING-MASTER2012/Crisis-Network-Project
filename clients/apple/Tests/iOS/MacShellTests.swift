import CoreGraphics
import CrisisCore
import XCTest
@testable import CrisisNetwork_iOS

/// Phase I8: the rules behind the Mac-specific parts of the app. They are plain Swift without AppKit, so they run here
/// on the simulator with the rest of the app's tests: how the app sits on the desktop, when it counts as on screen, what
/// the menu bar icon says, how the login item is offered, and how a picture of a QR code becomes a card. Not tested by a
/// machine: the menu bar, the Dock icon, the login item itself, the camera and the notarization (see the exit check in
/// the README); the sandbox rights are checked by `scripts/check-mac-app.sh` in CI.
final class MacShellTests: XCTestCase {
    // MARK: Helpers

    private func isolatedDefaults() -> (UserDefaults, String) {
        let suite = "org.crisisnetwork.tests.\(UUID().uuidString)"
        return (UserDefaults(suiteName: suite)!, suite)
    }

    private func card(_ seedByte: UInt8) throws -> Data {
        let node = try CrisisNode.inMemory(
            seed: Data(repeating: seedByte, count: 32), settings: defaultSettings(), nowMs: 1_000_000
        )
        return try node.contactCard()
    }

    private func grayPicture(width: Int, height: Int, white: Bool = true) throws -> CGImage {
        let context = try XCTUnwrap(CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width,
            space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue
        ))
        context.setFillColor(gray: white ? 1 : 0, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        return try XCTUnwrap(context.makeImage())
    }

    // MARK: How the app sits on the desktop

    func testTheDefaultsAreADockIconAMenuBarIconAndAHiddenWindowAtLogin() {
        let choices = MacShellChoices()
        XCTAssertTrue(choices.showMenuBarIcon)
        XCTAssertFalse(choices.hideDockIcon)
        XCTAssertTrue(choices.startHiddenAtLogin)
        XCTAssertFalse(choices.effectiveHideDock)
        XCTAssertTrue(choices.effectiveStartHidden)
    }

    func testTheDockIconAndTheWindowAreOnlyHiddenWhileTheMenuBarIconIsThere() {
        var choices = MacShellChoices(showMenuBarIcon: true, hideDockIcon: true, startHiddenAtLogin: true)
        XCTAssertTrue(choices.effectiveHideDock)
        XCTAssertTrue(choices.effectiveStartHidden)

        // Without the menu bar icon there would be no way back into the app.
        choices.showMenuBarIcon = false
        XCTAssertFalse(choices.effectiveHideDock)
        XCTAssertFalse(choices.effectiveStartHidden)
    }

    func testTheChoicesAreRemembered() {
        let (defaults, suite) = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertEqual(MacShellChoices.load(from: defaults), MacShellChoices())

        let chosen = MacShellChoices(showMenuBarIcon: false, hideDockIcon: true, startHiddenAtLogin: false)
        chosen.save(to: defaults)
        XCTAssertEqual(MacShellChoices.load(from: defaults), chosen)
    }

    @MainActor
    func testChangingAChoiceInThePreferencesSavesItAtOnce() {
        let (defaults, suite) = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = MacPreferences(defaults: defaults)
        preferences.choices.hideDockIcon = true
        XCTAssertEqual(MacShellChoices.load(from: defaults).hideDockIcon, true)
        XCTAssertEqual(MacPreferences(defaults: defaults).choices.hideDockIcon, true)
    }

    // MARK: On screen or not

    func testTheAppIsOnScreenOnlyWhenItIsActiveAndAWindowIsOpen() {
        XCTAssertTrue(MacPresence.isOnScreen(appActive: true, visibleWindows: 1))
        XCTAssertTrue(MacPresence.isOnScreen(appActive: true, visibleWindows: 3))
        // Another app in front: a message makes a notification.
        XCTAssertFalse(MacPresence.isOnScreen(appActive: false, visibleWindows: 1))
        // The window was closed and the mesh runs on in the menu bar.
        XCTAssertFalse(MacPresence.isOnScreen(appActive: true, visibleWindows: 0))
        XCTAssertFalse(MacPresence.isOnScreen(appActive: false, visibleWindows: 0))
    }

    // MARK: The menu bar icon

    func testTheMenuBarSummaryFollowsTheStateOfTheMesh() {
        func make(done: Bool = true, running: Bool = false, busy: Bool = false, neighbours: Int = 0, unread: Int = 0) -> MenuBarSummary {
            MenuBarSummary.make(onboardingDone: done, running: running, busy: busy, neighbours: neighbours, unread: unread)
        }
        XCTAssertEqual(make(done: false, running: true).state, .setupNeeded)
        XCTAssertEqual(make().state, .off)
        XCTAssertEqual(make(running: true, neighbours: 3).state, .on(neighbours: 3))
        XCTAssertEqual(make(running: true, busy: true).state, .busy)
        XCTAssertEqual(make(neighbours: -2, unread: -1).unread, 0)
        XCTAssertEqual(make(running: true, neighbours: -2).state, .on(neighbours: 0))
    }

    func testTheMeshCanOnlyBeToggledWhenItIsSetUpAndNotBusy() {
        func canToggle(done: Bool, running: Bool, busy: Bool) -> Bool {
            MenuBarSummary.make(onboardingDone: done, running: running, busy: busy, neighbours: 0, unread: 0).canToggleMesh
        }
        XCTAssertTrue(canToggle(done: true, running: false, busy: false))
        XCTAssertTrue(canToggle(done: true, running: true, busy: false))
        XCTAssertFalse(canToggle(done: true, running: false, busy: true))
        XCTAssertFalse(canToggle(done: false, running: false, busy: false))
    }

    func testTheMenuBarSymbolShowsWhetherTheMeshRuns() {
        let on = MenuBarSummary.make(onboardingDone: true, running: true, busy: false, neighbours: 1, unread: 0)
        let off = MenuBarSummary.make(onboardingDone: true, running: false, busy: false, neighbours: 0, unread: 0)
        XCTAssertTrue(on.isRunning)
        XCTAssertFalse(off.isRunning)
        XCTAssertNotEqual(on.symbol, off.symbol)
    }

    // MARK: Opening at login

    func testEachStateOfTheLoginItemHasItsOwnOffer() {
        XCTAssertEqual(LoginItemPlan.decide(.notRegistered), .offerEnable)
        XCTAssertEqual(LoginItemPlan.decide(.enabled), .offerDisable)
        XCTAssertEqual(LoginItemPlan.decide(.requiresApproval), .openSystemSettings)
        XCTAssertEqual(LoginItemPlan.decide(.unavailable), .unavailable)
    }

    func testTheSwitchIsOnForARegisteredItemThatStillWaitsForApproval() {
        XCTAssertFalse(LoginItemPlan.isOn(.notRegistered))
        XCTAssertTrue(LoginItemPlan.isOn(.enabled))
        XCTAssertTrue(LoginItemPlan.isOn(.requiresApproval))
        XCTAssertFalse(LoginItemPlan.isOn(.unavailable))
    }

    @MainActor
    func testTurningTheLoginItemOnAndOffGoesThroughTheService() {
        let service = FakeLoginItem(.notRegistered)
        let model = LoginItemModel(service: service)
        XCTAssertEqual(model.state, .notRegistered)

        model.setEnabled(true)
        XCTAssertEqual(model.state, .enabled)
        XCTAssertEqual(service.enableCalls, 1)

        // Already on: nothing is registered a second time.
        model.setEnabled(true)
        XCTAssertEqual(service.enableCalls, 1)

        model.setEnabled(false)
        XCTAssertEqual(model.state, .notRegistered)
        XCTAssertEqual(service.disableCalls, 1)

        // Already off: nothing is removed that is not there.
        model.setEnabled(false)
        XCTAssertEqual(service.disableCalls, 1)
        XCTAssertNil(model.errorText)
    }

    @MainActor
    func testAFailureIsKeptAsTextAndTheStateIsReadBackFromTheSystem() {
        let service = FakeLoginItem(.notRegistered)
        service.failNext = true
        let model = LoginItemModel(service: service)
        model.setEnabled(true)
        XCTAssertNotNil(model.errorText)
        XCTAssertEqual(model.state, .notRegistered)

        service.failNext = false
        model.setEnabled(true)
        XCTAssertNil(model.errorText)
        XCTAssertEqual(model.state, .enabled)
    }

    @MainActor
    func testTheModelSeesWhatThePersonChangedInTheSystemSettings() {
        let service = FakeLoginItem(.requiresApproval)
        let model = LoginItemModel(service: service)
        XCTAssertEqual(model.state, .requiresApproval)
        service.state = .enabled
        model.refresh()
        XCTAssertEqual(model.state, .enabled)

        model.openSystemSettings()
        XCTAssertEqual(service.settingsOpened, 1)
    }

    // MARK: A card from a picture

    func testAPictureOfACardIsReadAsThatCard() throws {
        let bytes = try card(8)
        let png = try XCTUnwrap(QrCode.pngData(of: CardText.encode(bytes)))
        XCTAssertEqual(QrImageImport.outcome(forImageData: png), .card(bytes))
    }

    func testAQrCodeThatIsNotACardIsToldApartFromNoQrCode() throws {
        let other = try XCTUnwrap(QrCode.pngData(of: "https://example.org/not-a-card"))
        XCTAssertEqual(QrImageImport.outcome(forImageData: other), .notACard)

        let blank = try XCTUnwrap(QrImageImport.pngData(of: try grayPicture(width: 200, height: 200)))
        XCTAssertEqual(QrImageImport.outcome(forImageData: blank), .noQrCode)
    }

    func testSomethingThatIsNoPictureIsUnreadableAndAHugeFileIsRefused() {
        XCTAssertEqual(QrImageImport.outcome(forImageData: Data("not a picture".utf8)), .unreadable)
        XCTAssertEqual(QrImageImport.outcome(forImageData: Data()), .unreadable)
        XCTAssertEqual(QrImageImport.outcome(forImageData: Data(count: QrImageImport.maxFileBytes + 1)), .tooLarge)
    }

    func testABigPictureIsDecodedAtALimitedSizeAndASmallOneAtItsOwn() throws {
        let wide = try XCTUnwrap(QrImageImport.pngData(of: try grayPicture(width: 6_000, height: 120)))
        let decoded = try XCTUnwrap(QrImageImport.decode(wide))
        XCTAssertLessThanOrEqual(max(decoded.width, decoded.height), QrImageImport.maxPixels)

        let small = try XCTUnwrap(QrImageImport.pngData(of: try grayPicture(width: 90, height: 60)))
        let kept = try XCTUnwrap(QrImageImport.decode(small))
        XCTAssertEqual(kept.width, 90)
        XCTAssertEqual(kept.height, 60)
    }

    func testEveryOutcomeThatIsNotACardHasASentenceInBothLanguages() {
        XCTAssertNil(QrImageImport.Outcome.card(Data([1])).problemKey)
        let problems: [QrImageImport.Outcome] = [.notACard, .noQrCode, .unreadable, .tooLarge]
        for outcome in problems {
            let key = outcome.problemKey
            XCTAssertNotNil(key)
            guard let key else { continue }
            XCTAssertNotEqual(L10n.string(key, language: .english), key, "\(key) is missing in English")
            XCTAssertNotEqual(L10n.string(key, language: .turkish), key, "\(key) is missing in Turkish")
        }
    }

    func testAQrCodeIsAValidPngPicture() throws {
        let png = try XCTUnwrap(QrCode.pngData(of: "crisis:test"))
        // The PNG signature.
        XCTAssertEqual(Array(png.prefix(8)), [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
    }

    // MARK: Strings

    func testTheMacStringsAreTranslated() {
        let keys = [
            "settings_mac_menubar", "settings_mac_dock", "settings_mac_login", "settings_mac_hidden",
            "settings_mac_hint", "settings_mac_login_approval", "settings_mac_login_open",
            "settings_mac_login_unavailable", "menubar_setup", "menubar_off", "menubar_busy", "menubar_open",
            "menubar_join", "menubar_leave", "menubar_quit", "menu_settings", "menu_go", "menu_mesh",
            "scan_mac_no_camera", "scan_mac_picture_title", "scan_mac_picture_hint", "scan_mac_choose",
            "scan_mac_paste", "scan_mac_drop", "scan_mac_clipboard_empty", "scan_image_no_qr",
            "scan_image_unreadable", "scan_image_too_large", "mycard_copy_picture", "mycard_save_picture",
            "mycard_picture_copied", "mycard_picture_saved", "mycard_picture_failed", "onb_bg_mac_body",
        ]
        for key in keys {
            let en = L10n.string(key, language: .english)
            let tr = L10n.string(key, language: .turkish)
            XCTAssertNotEqual(en, key, "\(key) is missing in English")
            XCTAssertNotEqual(tr, key, "\(key) is missing in Turkish")
            XCTAssertNotEqual(en, tr, "\(key) is the same in both languages")
        }
    }

    func testTheMacStringsWithArgumentsAndPluralsAreFilledIn() {
        XCTAssertEqual(L10n.string("menubar_on %lld", language: .english, 1), "Mesh on, 1 neighbour")
        XCTAssertEqual(L10n.string("menubar_on %lld", language: .english, 4), "Mesh on, 4 neighbours")
        XCTAssertEqual(L10n.string("menubar_on %lld", language: .turkish, 4), "Ağ açık, 4 komşu")
        XCTAssertEqual(
            L10n.string("settings_mac_login_error %@", language: .english, "boom"),
            "The setting could not be changed: boom"
        )
    }
}

/// A login item service that remembers what it was asked, so that the model is tested without touching the real login
/// items of the machine.
private final class FakeLoginItem: LoginItemService {
    struct Failure: Error {}

    var state: LoginItemState
    var failNext = false
    var enableCalls = 0
    var disableCalls = 0
    var settingsOpened = 0

    init(_ state: LoginItemState) {
        self.state = state
    }

    func enable() throws {
        enableCalls += 1
        if failNext { throw Failure() }
        state = .enabled
    }

    func disable() throws {
        disableCalls += 1
        if failNext { throw Failure() }
        state = .notRegistered
    }

    func openSystemSettings() {
        settingsOpened += 1
    }
}
