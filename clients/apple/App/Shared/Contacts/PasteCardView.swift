import SwiftUI

/// Entering a card as text: for devices without a camera, and for cards that were sent as a message.
struct PasteCardView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette
    @State private var text = ""
    @State private var card: Data?
    @State private var invalid = false

    var body: some View {
        ScreenColumn {
            if let card {
                AddContactForm(card: card, onDone: { dismiss() }, onCancel: { self.card = nil })
            } else {
                entry
            }
        }
        .navigationTitle("paste_title")
        .inlineNavigationTitle()
    }

    @ViewBuilder
    private var entry: some View {
        Text("paste_hint").font(TypeScale.body)
        TextEditor(text: $text)
            .font(TypeScale.monospaceSmall)
            .frame(minHeight: 110)
            .scrollContentBackground(.hidden)
            .padding(8)
            .background(.quaternary, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .autocorrectionDisabled()
            #if os(iOS)
            .textInputAutocapitalization(.never)
            #endif
            .accessibilityLabel("paste_field")
            .onChange(of: text) { _, _ in invalid = false }
        if invalid { Text("paste_invalid").font(TypeScale.body).foregroundStyle(palette.error.color) }
        SecondaryButton("paste_clipboard") {
            if let pasted = Clipboard.text() {
                text = pasted
                invalid = false
            }
        }
        PrimaryButton("paste_continue") {
            if let bytes = CardText.decode(text) { card = bytes } else { invalid = true }
        }
        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }
}
