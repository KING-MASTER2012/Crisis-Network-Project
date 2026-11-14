import CrisisCore
import SwiftUI

/// What happens after a card was read (from a QR code or typed): shows whose it is, with the safety number to compare,
/// asks for an optional nickname and adds the contact. While the node is not running the card is kept and added as soon
/// as it runs.
struct AddContactForm: View {
    let card: Data
    let onDone: () -> Void
    let onCancel: () -> Void

    @ObservedObject private var model = ContactsModel.shared
    @Environment(\.palette) private var palette
    @State private var nickname = ""
    @State private var result: AddResult?
    @State private var working = false

    private var details: CardDetails? {
        try? inspectContactCard(card: card)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let details {
                switch result {
                case .added, .queued:
                    done
                default:
                    form(details)
                }
            } else {
                InfoCard(title: "add_title", tone: .error) { Text("add_invalid") }
                QuietButton("add_cancel", action: onCancel)
            }
        }
    }

    private var done: some View {
        VStack(alignment: .leading, spacing: 16) {
            InfoCard(title: "add_title", tone: .success) {
                if case .queued = result { Text("add_queued") } else { Text("add_done") }
            }
            PrimaryButton("add_finish", action: onDone)
        }
    }

    private func form(_ details: CardDetails) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            InfoCard(title: "add_title") {
                Text("add_safety_label").font(TypeScale.label)
                Text(OnboardingFlow.formatSafetyNumber(details.safetyNumber))
                    .font(TypeScale.monospace)
                    .textSelection(.enabled)
                Text("add_safety_hint").font(TypeScale.bodySmall)
            }
            TextField("add_nickname", text: $nickname)
                .textFieldStyle(.roundedBorder)
                .onChange(of: nickname) { _, value in
                    if value.count > NicknameStore.maxLength { nickname = String(value.prefix(NicknameStore.maxLength)) }
                }
            if let message = failureKey {
                Text(message).font(TypeScale.body).foregroundStyle(palette.error.color)
            }
            PrimaryButton("add_confirm") { add() }.disabled(working)
            QuietButton("add_cancel", action: onCancel)
        }
    }

    private var failureKey: LocalizedStringKey? {
        switch result {
        case .ownCard: return "add_own"
        case .invalid: return "add_invalid"
        case .failed: return "add_failed"
        default: return nil
        }
    }

    private func add() {
        working = true
        Task {
            result = await model.addContact(card: card, nickname: nickname.isEmpty ? nil : nickname)
            working = false
        }
    }
}
