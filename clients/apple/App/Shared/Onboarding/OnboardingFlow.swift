import CrisisRuntime
import Foundation

/// The steps of the introduction, in order.
enum OnboardingStep: Int, CaseIterable, Hashable, Sendable {
    case welcome
    case identity
    case nearby
    case background
    case done
}

/// The rules of the introduction, without any user interface (so that they can be tested): where it starts, which step
/// follows, what must be done before going on, what may be skipped. The same rules as the Android client's
/// `OnboardingFlow`.
enum OnboardingFlow {
    static let steps = OnboardingStep.allCases

    /// A person who already has an identity (the app was closed in the middle) does not create a second one.
    static func firstStep(identityCreated: Bool) -> OnboardingStep {
        identityCreated ? .nearby : .welcome
    }

    static func next(_ step: OnboardingStep) -> OnboardingStep {
        OnboardingStep(rawValue: step.rawValue + 1) ?? step
    }

    static func previous(_ step: OnboardingStep) -> OnboardingStep {
        OnboardingStep(rawValue: step.rawValue - 1) ?? step
    }

    static func canGoBack(_ step: OnboardingStep) -> Bool {
        step != .welcome
    }

    /// The identity is required; the permission and the explanation of the background are strongly advised but never
    /// block.
    static func canAdvance(_ step: OnboardingStep, identityCreated: Bool) -> Bool {
        step == .identity ? identityCreated : true
    }

    /// Steps with an optional action: the person may go on without doing it.
    static func canSkip(_ step: OnboardingStep) -> Bool {
        step == .nearby
    }

    /// "Step 2 of 5": the position of `step` counted from 1.
    static func position(_ step: OnboardingStep) -> Int {
        step.rawValue + 1
    }

    static var total: Int { steps.count }

    /// The screens pushed on top of the welcome step to arrive at `step` (the welcome step is the root): `[]` for the
    /// welcome step, `[.identity, .nearby]` for the nearby step. The back button and the swipe then walk back through
    /// the same steps as the user would have.
    static func path(to step: OnboardingStep) -> [OnboardingStep] {
        steps.filter { $0.rawValue > OnboardingStep.welcome.rawValue && $0.rawValue <= step.rawValue }
    }

    /// The safety number as lines of four groups, which is how two people read it to each other. The core returns eight
    /// groups of four digits separated by spaces; anything else is returned unchanged.
    static func formatSafetyNumber(_ raw: String) -> String {
        let separators = CharacterSet(charactersIn: " -\u{00A0}")
        let groups = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .components(separatedBy: separators)
            .filter { !$0.isEmpty }
        guard groups.count == 8, groups.allSatisfy({ $0.count == 4 }) else { return raw }
        return stride(from: 0, to: 8, by: 4)
            .map { groups[$0..<($0 + 4)].joined(separator: "  ") }
            .joined(separator: "\n")
    }

    /// The first 16 and the last 8 digits of a node id: enough to recognise it.
    static func shortNodeId(_ hex: String) -> String {
        hex.count <= 28 ? hex : String(hex.prefix(16)) + "…" + String(hex.suffix(8))
    }

    /// The key of the text that tells a person what went wrong, short and without jargon; the details stay in the logs.
    static func failureMessageKey(_ failure: CoreFailure) -> String {
        switch failure {
        case let .secretsUnavailable(_, resettable):
            return resettable ? "onb_failure_secrets_resettable" : "onb_failure_secrets"
        case .databaseUnreadable:
            return "onb_failure_database"
        case .encryptionUnavailable:
            return "onb_failure_encryption"
        case .schemaTooNew:
            return "onb_failure_newer"
        default:
            return "onb_failure_generic"
        }
    }

    /// Whether the person can fix the failure by resetting the identity.
    static func offersReset(_ failure: CoreFailure) -> Bool {
        if case let .secretsUnavailable(_, resettable) = failure { return resettable }
        return false
    }
}
