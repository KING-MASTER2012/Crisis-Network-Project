import SwiftUI

/// The screens that can be pushed on the chat destination's stack.
enum ChatRoute: Hashable {
    case newChat
    case chat(String)
    case contact(String)
}

/// Navigation that crosses destinations: "send a message" on a contact leads to the chat tab with that chat open, and a
/// notification (phase I7) will do the same. Each destination keeps its own stack; only the chat stack is driven from
/// outside, so only its path lives here.
@MainActor
final class AppNavigator: ObservableObject {
    static let shared = AppNavigator()

    /// The path of the chat destination's `NavigationStack`.
    @Published var chatsPath: [ChatRoute] = []

    /// A destination the shell should switch to; the shell clears it once it has.
    @Published var destinationRequest: Destination?

    /// Shows the chat with `peerHex`: the chat destination, the list below, the conversation on top.
    func openChat(_ peerHex: String) {
        chatsPath = [.chat(peerHex)]
        destinationRequest = .chats
    }
}
