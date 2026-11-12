import Foundation
import SwiftUI

/// What the interface remembers (phase I4): the appearance and language choices, whether the introduction was completed
/// and the public parts of the identity (safety number, node id), so that screens can show them without opening the
/// node. Nothing secret is kept here: the seed and the database key live in the Keychain, behind the runtime's vault.
///
/// Plain `UserDefaults`, like the Android client's `SharedPreferences`.
@MainActor
final class UiPreferences: ObservableObject {
    static let shared = UiPreferences()

    private enum Keys {
        static let mode = "ui.theme.mode"
        static let contrast = "ui.theme.highContrast"
        static let textScale = "ui.theme.textScale"
        static let language = "ui.language"
        static let onboarded = "ui.onboarding.done"
        static let identity = "ui.identity.created"
        static let safety = "ui.identity.safetyNumber"
        static let node = "ui.identity.nodeId"
    }

    private let defaults: UserDefaults

    @Published var theme: ThemeSettings {
        didSet {
            defaults.set(theme.mode.rawValue, forKey: Keys.mode)
            defaults.set(theme.highContrast, forKey: Keys.contrast)
            defaults.set(theme.textScale, forKey: Keys.textScale)
        }
    }

    @Published var language: AppLanguage {
        didSet { defaults.set(language.rawValue, forKey: Keys.language) }
    }

    @Published private(set) var onboardingDone: Bool
    @Published private(set) var identityCreated: Bool
    @Published private(set) var safetyNumber: String?
    @Published private(set) var nodeIdHex: String?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        theme = ThemeSettings(
            mode: ThemeMode(rawValue: defaults.string(forKey: Keys.mode) ?? "") ?? .system,
            highContrast: defaults.bool(forKey: Keys.contrast),
            textScale: ThemeSettings.nearestTextScale(defaults.object(forKey: Keys.textScale) as? Double ?? 1.0)
        )
        language = AppLanguage(rawValue: defaults.string(forKey: Keys.language) ?? "") ?? .system
        onboardingDone = defaults.bool(forKey: Keys.onboarded)
        identityCreated = defaults.bool(forKey: Keys.identity)
        safetyNumber = defaults.string(forKey: Keys.safety)
        nodeIdHex = defaults.string(forKey: Keys.node)
    }

    /// Remembers that the identity exists, with the public values the screens show.
    func saveIdentity(safetyNumber: String, nodeIdHex: String) {
        defaults.set(true, forKey: Keys.identity)
        defaults.set(safetyNumber, forKey: Keys.safety)
        defaults.set(nodeIdHex, forKey: Keys.node)
        identityCreated = true
        self.safetyNumber = safetyNumber
        self.nodeIdHex = nodeIdHex
    }

    /// The identity was deleted (explicit reset): forget what was shown of it.
    func clearIdentity() {
        defaults.removeObject(forKey: Keys.identity)
        defaults.removeObject(forKey: Keys.safety)
        defaults.removeObject(forKey: Keys.node)
        identityCreated = false
        safetyNumber = nil
        nodeIdHex = nil
    }

    func completeOnboarding() {
        defaults.set(true, forKey: Keys.onboarded)
        onboardingDone = true
    }

    /// Shows the introduction again (a developer convenience in the settings).
    func resetOnboarding() {
        defaults.set(false, forKey: Keys.onboarded)
        onboardingDone = false
    }
}
