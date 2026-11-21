import CrisisCore
import SwiftUI

/// One private conversation. The newest messages are at the bottom; scrolling up loads older pages. While the screen is
/// open incoming messages are marked read at once. The box at the bottom counts the bytes of the text (UTF-8, not
/// characters) against what a private message can carry.
struct ChatView: View {
    let peerHex: String

    @ObservedObject private var messaging = MessagingModel.shared
    @ObservedObject private var contacts = ContactsModel.shared
    @ObservedObject private var prefs = UiPreferences.shared
    @Environment(\.palette) private var palette
    @State private var text = ""
    @State private var error: LocalizedStringKey?

    private var contact: ContactRow? {
        contacts.contacts.first { $0.hex == peerHex }
    }

    private var blocked: Bool { contact?.trust == .blocked }
    private var hasCard: Bool { contact?.hasCard == true }
    private var canWrite: Bool { messaging.ready && hasCard && !blocked }

    private var note: LocalizedStringKey? {
        if blocked { return "chat_blocked" }
        if !hasCard { return "chat_no_card" }
        return nil
    }

    var body: some View {
        VStack(spacing: 0) {
            if !messaging.ready { MeshOffNotice().padding(.horizontal, 16).padding(.top, 8) }
            messages
            composer
        }
        .background(palette.background.color)
        .navigationTitle(contacts.displayName(hex: peerHex, language: prefs.language))
        .inlineNavigationTitle()
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                NavigationLink(value: ChatRoute.contact(peerHex)) { Text("chat_details") }
            }
        }
        .onAppear {
            messaging.openChat(peerHex)
            MessageNotifier.shared.chatOpened(peerHex)
        }
        .onDisappear { messaging.closeChat(peerHex) }
        .task { await contacts.refresh() }
    }

    // MARK: The messages

    private var messages: some View {
        let chat = messaging.chat
        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 8) {
                    if chat.hasOlder {
                        // Reaching the top of the list asks for the next older page.
                        Text("loading_older")
                            .font(TypeScale.bodySmall)
                            .onAppear { Task { await messaging.loadOlderChat() } }
                    }
                    if chat.loaded && chat.messages.isEmpty {
                        Text("chat_empty")
                            .font(TypeScale.body)
                            .foregroundStyle(palette.onSurfaceVariant.color)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity)
                            .padding(24)
                    }
                    ForEach(chat.messages) { message in
                        VStack(spacing: 8) {
                            if message.seq == chat.firstUnreadSeq { UnreadDivider() }
                            MessageBubble(message: message)
                        }
                        .id(message.seq)
                    }
                }
                .frame(maxWidth: 640)
                .padding(16)
                .frame(maxWidth: .infinity)
            }
            .defaultScrollAnchor(.bottom)
            #if os(iOS)
            .scrollDismissesKeyboard(.interactively)
            #endif
            // A new message at the bottom is followed.
            .onChange(of: chat.messages.last?.seq) { _, newest in
                guard let newest else { return }
                withAnimation { proxy.scrollTo(newest, anchor: .bottom) }
            }
            // An older page was put in front: stay where the reader was.
            .onChange(of: chat.messages.first?.seq) { old, new in
                if let old, let new, new < old { proxy.scrollTo(old, anchor: .top) }
            }
        }
    }

    // MARK: Writing

    private var composer: some View {
        let limit = messaging.limits.directLimit
        let count = countBytes(text: text, limit: UInt32(clamping: limit))
        let canSend = canWrite && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !count.over
        return VStack(alignment: .leading, spacing: 4) {
            if let note {
                Text(note).font(TypeScale.bodySmall).foregroundStyle(palette.onSurfaceVariant.color)
            }
            // A failed send is announced: the person may not see the line (screen reader, large text).
            if let error {
                Text(error)
                    .font(TypeScale.bodySmall)
                    .foregroundStyle(palette.error.color)
                    .accessibilityAddTraits(.updatesFrequently)
            }
            AdaptiveStack(spacing: 8) {
                TextField("chat_input_hint", text: $text, axis: .vertical)
                    .lineLimit(1...5)
                    .textFieldStyle(.roundedBorder)
                    .disabled(!canWrite)
                    .onChange(of: text) { _, _ in error = nil }
                Button { send() } label: { Text("chat_send") }
                    .buttonStyle(CrisisButtonStyle(kind: .primary))
                    .scaledWidth(104)
                    .disabled(!canSend)
            }
            ByteCounter(count: count)
        }
        .padding(12)
        .frame(maxWidth: 640)
        .frame(maxWidth: .infinity)
        .background(palette.background.color)
    }

    private func send() {
        let sending = text
        Task {
            let result = await messaging.sendDirect(peerHex: peerHex, text: sending)
            if result == .sent {
                // Keep what the person typed meanwhile.
                if text == sending { text = "" }
                error = nil
            } else {
                error = result.messageKey
            }
        }
    }
}
