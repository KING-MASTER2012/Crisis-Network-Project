import CrisisCore
import SwiftUI

/// The private conversations, the one with the newest message first, with a preview and the unread count; and the way to
/// start one.
struct ChatsView: View {
    @ObservedObject private var messaging = MessagingModel.shared
    @ObservedObject private var contacts = ContactsModel.shared
    @ObservedObject private var prefs = UiPreferences.shared
    @Environment(\.palette) private var palette

    var body: some View {
        LazyScreenColumn {
            if !messaging.ready { MeshOffNotice() }
            NavigationLink(value: ChatRoute.newChat) { Text("chats_new") }
                .buttonStyle(CrisisButtonStyle(kind: .primary))
            if messaging.chats.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("chats_empty_title").font(TypeScale.titleSmall)
                    Text("chats_empty_body")
                        .font(TypeScale.body)
                        .foregroundStyle(palette.onSurfaceVariant.color)
                }
                .padding(.top, 8)
            } else {
                ForEach(messaging.chats) { chat in
                    NavigationLink(value: ChatRoute.chat(chat.peerHex)) { row(chat) }
                        .buttonStyle(.plain)
                }
            }
        }
        .navigationTitle("nav_chats")
        .task { await messaging.refresh() }
        .navigationDestination(for: ChatRoute.self) { route in
            switch route {
            case .newChat: NewChatView()
            case let .chat(hex): ChatView(peerHex: hex)
            case let .contact(hex): ContactDetailView(hex: hex)
            }
        }
    }

    private func row(_ chat: ChatRow) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Text(contacts.displayName(hex: chat.peerHex, language: prefs.language))
                    .font(TypeScale.titleSmall)
                    .fontWeight(chat.unread > 0 ? .bold : .regular)
                    .lineLimit(1)
                Spacer(minLength: 0)
                TimeLabel(ms: chat.lastTimeMs).font(TypeScale.bodySmall)
            }
            preview(chat)
                .font(TypeScale.body)
                .foregroundStyle(palette.onSurfaceVariant.color)
                .lineLimit(2)
            if chat.unread > 0 {
                StatusPill("chat_unread \(chat.unread)", tone: .warning)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .foregroundStyle(palette.onSurface.color)
        .background(palette.container.color, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func preview(_ chat: ChatRow) -> some View {
        if let text = chat.preview {
            if chat.lastOutgoing { Text("chat_preview_mine \(text)") } else { Text(text) }
        } else {
            Text("msg_unreadable")
        }
    }
}

/// Choosing the contact to write to: only people whose card is stored (and who are not blocked) can be written to.
struct NewChatView: View {
    @ObservedObject private var messaging = MessagingModel.shared
    @ObservedObject private var contacts = ContactsModel.shared
    @ObservedObject private var prefs = UiPreferences.shared
    @Environment(\.palette) private var palette

    private var candidates: [ContactRow] {
        contacts.contacts.filter { $0.hasCard && $0.trust != .blocked }
    }

    var body: some View {
        LazyScreenColumn {
            if !messaging.ready { MeshOffNotice() }
            if candidates.isEmpty {
                Text("newchat_none")
                    .font(TypeScale.body)
                    .foregroundStyle(palette.onSurfaceVariant.color)
            }
            ForEach(candidates) { contact in
                NavigationLink(value: ChatRoute.chat(contact.hex)) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(contacts.displayName(hex: contact.hex, language: prefs.language))
                            .font(TypeScale.titleSmall)
                        StatusPill(contact.trust.label, tone: contact.trust.tone)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .foregroundStyle(palette.onSurface.color)
                    .background(palette.container.color, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .accessibilityElement(children: .combine)
                }
                .buttonStyle(.plain)
            }
        }
        .navigationTitle("newchat_title")
        .inlineNavigationTitle()
        .task { await contacts.refresh() }
    }
}
