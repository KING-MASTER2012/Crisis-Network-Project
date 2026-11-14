import CrisisCore
import SwiftUI

/// One contact: the nickname, the trust level and the comparison of safety numbers. Two people who read their numbers to
/// each other and find them identical know that nobody replaced a card on the way; only then does the contact count as
/// verified. Changing the trust needs the node, so those buttons wait for the mesh.
///
/// `onMessage` is the way into the conversation with this contact; without it (until the chats exist, phase I6) the
/// button is not shown.
struct ContactDetailView: View {
    let hex: String
    var onMessage: ((String) -> Void)?

    @ObservedObject private var model = ContactsModel.shared
    @ObservedObject private var prefs = UiPreferences.shared
    @Environment(\.palette) private var palette
    @State private var nickname = ""
    @State private var confirmBlock = false
    @State private var failed = false

    private var contact: ContactRow? {
        model.contacts.first { $0.hex == hex }
    }

    var body: some View {
        ScreenColumn {
            nicknameCard
            if let contact {
                trustCard(contact)
                safetyCard(contact)
                if !model.ready { Text("contact_needs_mesh").font(TypeScale.body) }
                if failed {
                    Text("contact_change_failed").font(TypeScale.body).foregroundStyle(palette.error.color)
                }
                actions(contact)
            } else {
                InfoCard(tone: .warning) { Text("contact_unknown_hint") }
            }
        }
        .navigationTitle(model.displayName(hex: hex, language: prefs.language))
        .inlineNavigationTitle()
        .onAppear { nickname = model.names[hex] ?? "" }
        .alert("contact_block_title", isPresented: $confirmBlock) {
            Button("contact_block", role: .destructive) { change(.blocked) }
            Button("add_cancel", role: .cancel) {}
        } message: {
            Text("contact_block_hint")
        }
    }

    private var nicknameCard: some View {
        InfoCard(title: "contact_nickname") {
            TextField("contact_nickname", text: $nickname)
                .textFieldStyle(.roundedBorder)
                .onChange(of: nickname) { _, value in
                    if value.count > NicknameStore.maxLength { nickname = String(value.prefix(NicknameStore.maxLength)) }
                }
                .onSubmit { model.setNickname(hex: hex, raw: nickname) }
            SecondaryButton("contact_save") { model.setNickname(hex: hex, raw: nickname) }
            Text("contact_id_line \(shortNodeId(nodeIdHex: hex))")
                .font(TypeScale.monospaceSmall)
                .textSelection(.enabled)
        }
    }

    private func trustCard(_ contact: ContactRow) -> some View {
        InfoCard(title: "contact_trust_title") {
            StatusPill(contact.trust.label, tone: contact.trust.tone)
            if contact.trust == .blocked { Text("contact_block_hint").font(TypeScale.bodySmall) }
        }
    }

    private func safetyCard(_ contact: ContactRow) -> some View {
        InfoCard(title: "contact_safety_title") {
            if let theirs = contact.safetyNumber {
                Text("contact_safety_theirs").font(TypeScale.label)
                Text(OnboardingFlow.formatSafetyNumber(theirs)).font(TypeScale.monospace).textSelection(.enabled)
                if let own = prefs.safetyNumber {
                    Text("contact_safety_yours").font(TypeScale.label)
                    Text(OnboardingFlow.formatSafetyNumber(own)).font(TypeScale.monospace).textSelection(.enabled)
                }
                Text("contact_safety_hint").font(TypeScale.bodySmall)
            } else {
                Text("contact_no_card_hint")
            }
        }
    }

    @ViewBuilder
    private func actions(_ contact: ContactRow) -> some View {
        if let onMessage, contact.hasCard, contact.trust != .blocked {
            PrimaryButton("contact_message") { onMessage(hex) }
        }
        switch contact.trust {
        case .unknown:
            if contact.hasCard { SecondaryButton("contact_verify") { change(.verified) }.disabled(!model.ready) }
        case .verified:
            SecondaryButton("contact_unverify") { change(.unknown) }.disabled(!model.ready)
        case .blocked:
            SecondaryButton("contact_unblock") { change(.unknown) }.disabled(!model.ready)
        }
        if contact.trust != .blocked {
            SecondaryButton("contact_block") { confirmBlock = true }.disabled(!model.ready)
        }
    }

    private func change(_ trust: TrustLevel) {
        Task { failed = !(await model.setTrust(hex: hex, trust: trust)) }
    }
}
