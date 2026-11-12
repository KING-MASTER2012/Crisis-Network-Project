import CoreBluetooth
import CrisisRuntime
import XCTest
@testable import CrisisNetwork_iOS

/// The rules of the introduction and of the shell, the remembered preferences, the Bluetooth readiness and the String
/// Catalog (phase I4).
final class OnboardingTests: XCTestCase {
    // MARK: Flow

    func testTheIntroductionStartsAtTheWelcomeOrAfterTheIdentity() {
        XCTAssertEqual(OnboardingFlow.firstStep(identityCreated: false), .welcome)
        XCTAssertEqual(OnboardingFlow.firstStep(identityCreated: true), .nearby)
    }

    func testTheStepsFollowEachOtherAndStopAtTheEnds() {
        XCTAssertEqual(OnboardingFlow.next(.welcome), .identity)
        XCTAssertEqual(OnboardingFlow.next(.background), .done)
        XCTAssertEqual(OnboardingFlow.next(.done), .done)
        XCTAssertEqual(OnboardingFlow.previous(.identity), .welcome)
        XCTAssertEqual(OnboardingFlow.previous(.welcome), .welcome)
        XCTAssertFalse(OnboardingFlow.canGoBack(.welcome))
        XCTAssertTrue(OnboardingFlow.canGoBack(.done))
    }

    func testOnlyTheIdentityBlocksAndOnlyTheNearbyStepCanBeSkipped() {
        XCTAssertFalse(OnboardingFlow.canAdvance(.identity, identityCreated: false))
        XCTAssertTrue(OnboardingFlow.canAdvance(.identity, identityCreated: true))
        for step in [OnboardingStep.welcome, .nearby, .background, .done] {
            XCTAssertTrue(OnboardingFlow.canAdvance(step, identityCreated: false))
        }
        XCTAssertEqual(OnboardingStep.allCases.filter(OnboardingFlow.canSkip), [.nearby])
    }

    func testThePositionCountsFromOne() {
        XCTAssertEqual(OnboardingFlow.position(.welcome), 1)
        XCTAssertEqual(OnboardingFlow.position(.done), OnboardingFlow.total)
        XCTAssertEqual(OnboardingFlow.total, 5)
    }

    func testTheBackStackToAStepHoldsTheStepsInBetween() {
        XCTAssertEqual(OnboardingFlow.path(to: .welcome), [])
        XCTAssertEqual(OnboardingFlow.path(to: .identity), [.identity])
        XCTAssertEqual(OnboardingFlow.path(to: .nearby), [.identity, .nearby])
        XCTAssertEqual(OnboardingFlow.path(to: .done), [.identity, .nearby, .background, .done])
    }

    func testTheSafetyNumberIsShownInTwoLinesOfFourGroups() {
        XCTAssertEqual(
            OnboardingFlow.formatSafetyNumber("1111 2222 3333 4444 5555 6666 7777 8888"),
            "1111  2222  3333  4444\n5555  6666  7777  8888"
        )
        XCTAssertEqual(
            OnboardingFlow.formatSafetyNumber("1111-2222-3333-4444-5555-6666-7777-8888"),
            "1111  2222  3333  4444\n5555  6666  7777  8888"
        )
    }

    func testASafetyNumberOfAnotherShapeIsShownAsIs() {
        XCTAssertEqual(OnboardingFlow.formatSafetyNumber("12345"), "12345")
        XCTAssertEqual(OnboardingFlow.formatSafetyNumber(""), "")
    }

    func testALongNodeIdIsShortenedAndAShortOneIsNot() {
        let long = String(repeating: "ab", count: 32)
        let short = OnboardingFlow.shortNodeId(long)
        XCTAssertTrue(short.hasPrefix(String(long.prefix(16))))
        XCTAssertTrue(short.hasSuffix(String(long.suffix(8))))
        XCTAssertTrue(short.contains("…"))
        XCTAssertEqual(OnboardingFlow.shortNodeId("abcd"), "abcd")
    }

    func testFailuresMapToTheirMessagesAndOnlyAResettableOneOffersAReset() {
        XCTAssertEqual(OnboardingFlow.failureMessageKey(.secretsUnavailable(reason: "x", resettable: true)), "onb_failure_secrets_resettable")
        XCTAssertEqual(OnboardingFlow.failureMessageKey(.secretsUnavailable(reason: "x", resettable: false)), "onb_failure_secrets")
        XCTAssertEqual(OnboardingFlow.failureMessageKey(.databaseUnreadable), "onb_failure_database")
        XCTAssertEqual(OnboardingFlow.failureMessageKey(.encryptionUnavailable), "onb_failure_encryption")
        XCTAssertEqual(OnboardingFlow.failureMessageKey(.schemaTooNew(found: 9, supported: 1)), "onb_failure_newer")
        XCTAssertEqual(OnboardingFlow.failureMessageKey(.other(detail: "x")), "onb_failure_generic")
        XCTAssertTrue(OnboardingFlow.offersReset(.secretsUnavailable(reason: "x", resettable: true)))
        XCTAssertFalse(OnboardingFlow.offersReset(.secretsUnavailable(reason: "x", resettable: false)))
        XCTAssertFalse(OnboardingFlow.offersReset(.databaseUnreadable))
    }

    // MARK: Shell

    func testTheShellHasTheFiveDestinationsOfTheAndroidClientInOrder() {
        XCTAssertEqual(Destination.allCases, [.home, .chats, .announcements, .contacts, .settings])
    }

    func testAnUnknownStoredDestinationFallsBackToHome() {
        XCTAssertEqual(Destination.restored("settings"), .settings)
        XCTAssertEqual(Destination.restored("nonsense"), .home)
        XCTAssertEqual(Destination.restored(""), .home)
    }

    // MARK: Bluetooth readiness

    func testTheReadinessFollowsTheAuthorisationAndThePowerState() {
        typealias Readiness = BluetoothReadiness
        XCTAssertEqual(Readiness.of(authorization: .notDetermined, state: nil), .notDetermined)
        XCTAssertEqual(Readiness.of(authorization: .denied, state: .poweredOn), .denied)
        XCTAssertEqual(Readiness.of(authorization: .restricted, state: nil), .restricted)
        XCTAssertEqual(Readiness.of(authorization: .allowedAlways, state: .poweredOn), .ready)
        XCTAssertEqual(Readiness.of(authorization: .allowedAlways, state: .poweredOff), .poweredOff)
        XCTAssertEqual(Readiness.of(authorization: .allowedAlways, state: .unsupported), .unsupported)
        XCTAssertEqual(Readiness.of(authorization: .allowedAlways, state: .unauthorized), .denied)
        XCTAssertEqual(Readiness.of(authorization: .allowedAlways, state: nil), .unknown)
        XCTAssertEqual(Readiness.of(authorization: .allowedAlways, state: .resetting), .unknown)
    }

    // MARK: Preferences

    @MainActor
    private func makePreferences() -> (UiPreferences, UserDefaults, String) {
        let suite = "crisis-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        return (UiPreferences(defaults: defaults), defaults, suite)
    }

    @MainActor
    func testPreferencesStartWithTheDefaults() {
        let (prefs, defaults, suite) = makePreferences()
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertEqual(prefs.theme, ThemeSettings())
        XCTAssertEqual(prefs.language, .system)
        XCTAssertFalse(prefs.onboardingDone)
        XCTAssertFalse(prefs.identityCreated)
        XCTAssertNil(prefs.safetyNumber)
    }

    @MainActor
    func testPreferencesAreRemembered() {
        let (prefs, defaults, suite) = makePreferences()
        defer { defaults.removePersistentDomain(forName: suite) }
        prefs.theme = ThemeSettings(mode: .amoled, highContrast: true, textScale: 1.3)
        prefs.language = .turkish
        prefs.saveIdentity(safetyNumber: "1111 2222", nodeIdHex: "abcd")
        prefs.completeOnboarding()

        let again = UiPreferences(defaults: defaults)
        XCTAssertEqual(again.theme, ThemeSettings(mode: .amoled, highContrast: true, textScale: 1.3))
        XCTAssertEqual(again.language, .turkish)
        XCTAssertTrue(again.identityCreated)
        XCTAssertEqual(again.safetyNumber, "1111 2222")
        XCTAssertEqual(again.nodeIdHex, "abcd")
        XCTAssertTrue(again.onboardingDone)
    }

    @MainActor
    func testClearingTheIdentityForgetsItsPublicValuesAndTheIntroductionCanBeShownAgain() {
        let (prefs, defaults, suite) = makePreferences()
        defer { defaults.removePersistentDomain(forName: suite) }
        prefs.saveIdentity(safetyNumber: "x", nodeIdHex: "y")
        prefs.completeOnboarding()
        prefs.clearIdentity()
        XCTAssertFalse(prefs.identityCreated)
        XCTAssertNil(prefs.safetyNumber)
        XCTAssertNil(prefs.nodeIdHex)
        prefs.resetOnboarding()
        XCTAssertFalse(prefs.onboardingDone)
        XCTAssertFalse(UiPreferences(defaults: defaults).onboardingDone)
    }

    @MainActor
    func testAnUnknownStoredThemeOrLanguageFallsBackToTheDefault() {
        let suite = "crisis-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set("neon", forKey: "ui.theme.mode")
        defaults.set("klingon", forKey: "ui.language")
        let prefs = UiPreferences(defaults: defaults)
        XCTAssertEqual(prefs.theme.mode, .system)
        XCTAssertEqual(prefs.language, .system)
    }

    // MARK: Language and String Catalog

    func testTheLanguagesNameTheirCodes() {
        XCTAssertNil(AppLanguage.system.code)
        XCTAssertEqual(AppLanguage.english.code, "en")
        XCTAssertEqual(AppLanguage.turkish.code, "tr")
        XCTAssertEqual(AppLanguage.turkish.locale.identifier, "tr")
    }

    func testTheCatalogHasBothLanguagesAndTheChosenOneIsUsed() {
        XCTAssertEqual(L10n.string("app_name", language: .english), "Crisis Network")
        XCTAssertEqual(L10n.string("app_name", language: .turkish), "Kriz Ağı")
        XCTAssertEqual(L10n.string("nav_settings", language: .english), "Settings")
        XCTAssertNotEqual(L10n.string("nav_settings", language: .turkish), "Settings")
        XCTAssertNotEqual(L10n.string("nav_settings", language: .turkish), "nav_settings")
    }

    func testTheStringsOfTheNewScreensAreTranslated() {
        let keys = [
            "bt_denied_title", "bt_off_title", "onb_nearby_allow", "onb_bg_body", "ph_chats_title",
            "settings_language", "settings_theme_title", "lang_system", "onb_done_title", "home_join",
        ]
        for key in keys {
            for language in [AppLanguage.english, .turkish] {
                let text = L10n.string(key, language: language)
                XCTAssertNotEqual(text, key, "\(key) is missing in \(language)")
                XCTAssertFalse(text.isEmpty)
            }
            XCTAssertNotEqual(L10n.string(key, language: .english), L10n.string(key, language: .turkish), "\(key) is the same in both languages")
        }
    }

    func testAStringWithAnArgumentIsFilledIn() {
        XCTAssertEqual(L10n.string("home_profile_line %@", language: .english, "Saver"), "Power profile: Saver")
        XCTAssertEqual(L10n.string("onb_step_of %lld %lld", language: .english, 2, 5), "Step 2 of 5")
        XCTAssertEqual(L10n.string("onb_step_of %lld %lld", language: .turkish, 2, 5), "Adım 2 / 5")
    }
}
