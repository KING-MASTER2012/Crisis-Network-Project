import SwiftUI

/// The root of the app: the introduction until it was completed, then the shell. The theme and the language chosen in
/// the app are applied here, to everything.
struct RootView: View {
    @ObservedObject private var prefs = UiPreferences.shared
    @ObservedObject private var mesh = BleLinkTestModel.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if prefs.onboardingDone {
                AppShell()
            } else {
                OnboardingView(identityCreated: prefs.identityCreated) { joinNow in
                    prefs.completeOnboarding()
                    if joinNow { Task { await mesh.start() } }
                }
            }
        }
        .crisisTheme(prefs.theme)
        // The language takes effect at once: Text looks its keys up with this locale.
        .environment(\.locale, prefs.language.locale)
        // The app switcher takes its snapshot when the app stops being active: nothing of the messages is in it.
        .overlay {
            if PrivacyCover.shouldCover(enabled: prefs.hideInSwitcher, active: scenePhase == .active) {
                PrivacyCoverView().crisisTheme(prefs.theme)
            }
        }
    }
}
