import CrisisRuntime
import SwiftUI
#if os(iOS)
import UIKit
#endif

/// The introduction shown on first launch (phase I4): what the app is, creating the identity, the Bluetooth permission
/// with its reason, and what happens in the background. Only the identity is required.
///
/// The steps are pushed on a native `NavigationStack`, so the back button and the swipe from the edge work as everywhere
/// else on the platform. `onFinished` gets `true` when the person chose to join the mesh right away.
struct OnboardingView: View {
    @ObservedObject private var prefs = UiPreferences.shared
    @State private var path: [OnboardingStep]
    let onFinished: (_ joinNow: Bool) -> Void

    init(identityCreated: Bool, onFinished: @escaping (Bool) -> Void) {
        _path = State(initialValue: OnboardingFlow.path(to: OnboardingFlow.firstStep(identityCreated: identityCreated)))
        self.onFinished = onFinished
    }

    var body: some View {
        NavigationStack(path: $path) {
            WelcomeStep { advance(from: .welcome) }
                .navigationDestination(for: OnboardingStep.self) { step in
                    destination(step)
                }
        }
    }

    private func advance(from step: OnboardingStep) {
        guard OnboardingFlow.canAdvance(step, identityCreated: prefs.identityCreated) else { return }
        path.append(OnboardingFlow.next(step))
    }

    @ViewBuilder
    private func destination(_ step: OnboardingStep) -> some View {
        switch step {
        case .welcome: WelcomeStep { advance(from: .welcome) }
        case .identity: IdentityStep { advance(from: .identity) }
        case .nearby: NearbyStep { advance(from: .nearby) }
        case .background: BackgroundStep { advance(from: .background) }
        case .done: DoneStep(onFinished: onFinished)
        }
    }
}

/// The frame of a step: the progress on top, the content in the middle, the buttons under it.
private struct StepFrame<Content: View, Actions: View>: View {
    @Environment(\.palette) private var palette
    let step: OnboardingStep
    @ViewBuilder let content: Content
    @ViewBuilder let actions: Actions

    var body: some View {
        ScreenColumn {
            progress
            content
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 4) { actions }
                .frame(maxWidth: 640)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity)
                .background(palette.background.color)
        }
        .inlineNavigationTitle()
    }

    private var progress: some View {
        let position = OnboardingFlow.position(step)
        return VStack(alignment: .leading, spacing: 8) {
            Text("onb_step_of \(position) \(OnboardingFlow.total)")
                .font(TypeScale.label)
            ProgressView(value: Double(position), total: Double(OnboardingFlow.total))
                .accessibilityHidden(true)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct WelcomeStep: View {
    let onNext: () -> Void

    var body: some View {
        StepFrame(step: .welcome) {
            ScreenTitle("onb_welcome_title")
            Text("onb_welcome_body").font(TypeScale.bodyLarge)
            InfoCard(title: "onb_point_offline_title") { Text("onb_point_offline_body") }
            InfoCard(title: "onb_point_relay_title") { Text("onb_point_relay_body") }
            InfoCard(title: "onb_point_private_title") { Text("onb_point_private_body") }
        } actions: {
            PrimaryButton("onb_get_started", action: onNext)
        }
    }
}

private struct IdentityStep: View {
    @ObservedObject private var prefs = UiPreferences.shared
    @ObservedObject private var mesh = BleLinkTestModel.shared
    @State private var creating = false
    @State private var failure: CoreFailure?
    @State private var confirmReset = false
    let onNext: () -> Void

    var body: some View {
        StepFrame(step: .identity) {
            ScreenTitle("onb_identity_title")
            Text("onb_identity_body").font(TypeScale.bodyLarge)
            if creating {
                HStack(spacing: 16) {
                    ProgressView()
                    Text("onb_identity_creating")
                }
                .accessibilityElement(children: .combine)
            }
            if prefs.identityCreated {
                InfoCard(title: "onb_identity_created", tone: .success) {
                    Text("onb_identity_safety_label").font(TypeScale.label)
                    Text(OnboardingFlow.formatSafetyNumber(prefs.safetyNumber ?? ""))
                        .font(TypeScale.monospace)
                        .textSelection(.enabled)
                    Text("onb_identity_safety_hint").font(TypeScale.bodySmall)
                }
            }
            if let failure {
                InfoCard(title: "onb_identity_failed", tone: .error) {
                    Text(LocalizedStringKey(OnboardingFlow.failureMessageKey(failure)))
                    if OnboardingFlow.offersReset(failure) {
                        SecondaryButton("onb_identity_reset") { confirmReset = true }
                    }
                }
            }
        } actions: {
            if prefs.identityCreated {
                PrimaryButton("onb_next", action: onNext)
            } else {
                PrimaryButton("onb_identity_create") { create() }.disabled(creating)
            }
        }
        .alert("onb_reset_title", isPresented: $confirmReset) {
            Button("onb_reset_confirm", role: .destructive) { reset() }
            Button("onb_reset_cancel", role: .cancel) {}
        } message: {
            Text("onb_reset_body")
        }
    }

    private func create() {
        creating = true
        failure = nil
        Task { @MainActor in
            switch await IdentityCreator.create(mesh: mesh) {
            case let .created(safetyNumber, nodeIdHex):
                prefs.saveIdentity(safetyNumber: safetyNumber, nodeIdHex: nodeIdHex)
            case let .failed(error):
                failure = error
            }
            creating = false
        }
    }

    private func reset() {
        Task { @MainActor in
            await IdentityCreator.reset()
            prefs.clearIdentity()
            ContactsModel.shared.reset()
            failure = nil
        }
    }
}

private struct NearbyStep: View {
    @StateObject private var access = BluetoothAccess()
    let onNext: () -> Void

    var body: some View {
        StepFrame(step: .nearby) {
            ScreenTitle("onb_nearby_title")
            Text("onb_nearby_body").font(TypeScale.bodyLarge)
            InfoCard(title: "onb_nearby_why_title") {
                Text("onb_nearby_why_body")
            }
            if access.readiness == .ready {
                InfoCard(tone: .success) { Text("onb_nearby_ready") }
            } else {
                BluetoothBlockerCard(access: access)
            }
        } actions: {
            if access.readiness == .ready {
                PrimaryButton("onb_next", action: onNext)
            } else {
                switch access.readiness {
                case .denied:
                    PrimaryButton("bt_open_settings") { access.openSettings() }
                case .restricted, .unsupported:
                    EmptyView()
                default:
                    PrimaryButton("onb_nearby_allow") { access.request() }
                }
                QuietButton("onb_skip", action: onNext)
            }
        }
        // The person may come back from the Settings app with the permission given.
        .onAppear { access.refresh() }
        #if os(iOS)
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in access.refresh() }
        #endif
    }
}

private struct BackgroundStep: View {
    let onNext: () -> Void
    @State private var lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled

    var body: some View {
        StepFrame(step: .background) {
            ScreenTitle("onb_background_title")
            #if os(iOS)
            Text("onb_bg_body").font(TypeScale.bodyLarge)
            InfoCard(title: "onb_bg_refresh_title", tone: refreshTone) {
                Text("onb_bg_refresh_body")
                Text(refreshStatus).font(TypeScale.titleSmall)
                if refreshTone != .success {
                    SecondaryButton("bt_open_settings") { openSettings() }
                }
            }
            InfoCard(title: "onb_bg_lowpower_title") {
                Text("onb_bg_lowpower_body")
                Text(LocalizedStringKey(lowPower ? "onb_bg_lowpower_on" : "onb_bg_lowpower_off")).font(TypeScale.titleSmall)
            }
            InfoCard(title: "onb_bg_forcequit_title") {
                Text("onb_bg_forcequit_body")
            }
            #else
            Text("onb_bg_mac_body").font(TypeScale.bodyLarge)
            #endif
        } actions: {
            PrimaryButton("onb_next", action: onNext)
        }
        #if os(iOS)
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
        }
        #endif
    }

    #if os(iOS)
    private var refreshTone: Tone {
        UIApplication.shared.backgroundRefreshStatus == .available ? .success : .warning
    }

    private var refreshStatus: LocalizedStringKey {
        switch UIApplication.shared.backgroundRefreshStatus {
        case .available: return "onb_bg_refresh_on"
        case .restricted: return "onb_bg_refresh_restricted"
        default: return "onb_bg_refresh_off"
        }
    }

    private func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }
    #endif
}

private struct DoneStep: View {
    @ObservedObject private var prefs = UiPreferences.shared
    @StateObject private var access = BluetoothAccess()
    let onFinished: (Bool) -> Void

    var body: some View {
        StepFrame(step: .done) {
            ScreenTitle("onb_done_title")
            Text("onb_done_body").font(TypeScale.bodyLarge)
            InfoCard(tone: prefs.identityCreated ? .success : .warning) {
                Text(LocalizedStringKey(prefs.identityCreated ? "onb_done_identity_ok" : "onb_done_identity_missing"))
            }
            InfoCard(tone: access.readiness == .ready ? .success : .warning) {
                Text(LocalizedStringKey(access.readiness == .ready ? "onb_done_nearby_ok" : "onb_done_nearby_missing"))
            }
            BluetoothBlockerCard(access: access)
        } actions: {
            PrimaryButton("onb_join_now") { onFinished(true) }.disabled(!prefs.identityCreated)
            SecondaryButton("onb_open_app") { onFinished(false) }
        }
        .onAppear { access.refresh() }
    }
}
