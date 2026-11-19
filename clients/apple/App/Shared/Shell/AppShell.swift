import SwiftUI

/// The frame of the app after the introduction: a **tab bar** on an iPhone (a compact width) and a **sidebar with a
/// detail column** on an iPad and a Mac (a regular width). Every destination owns a native `NavigationStack`: the back
/// button and the swipe from the edge are the platform's, and a screen pushed on top of a destination goes away with a
/// swipe.
struct AppShell: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @SceneStorage("shell.destination") private var storedDestination = Destination.home.rawValue

    @ObservedObject private var messaging = MessagingModel.shared
    @ObservedObject private var navigator = AppNavigator.shared

    /// Unread counts per destination: the chats and the announcements.
    private var badges: [Destination: Int] {
        [.chats: messaging.unread.chats, .announcements: messaging.unread.announcements]
    }

    private var selection: Binding<Destination> {
        Binding(
            get: { Destination.restored(storedDestination) },
            set: { storedDestination = $0.rawValue }
        )
    }

    var body: some View {
        content
            // A tap on a notification that started the app asked for a destination before this view was there.
            .onAppear {
                if let request = navigator.destinationRequest {
                    selection.wrappedValue = request
                    navigator.destinationRequest = nil
                }
            }
            // "Send a message" on a contact (and later a notification) asks for the chat destination.
            .onChange(of: navigator.destinationRequest) { _, request in
                guard let request else { return }
                selection.wrappedValue = request
                navigator.destinationRequest = nil
            }
    }

    @ViewBuilder
    private var content: some View {
        #if os(macOS)
        splitView
        #else
        if sizeClass == .compact {
            tabView
        } else {
            splitView
        }
        #endif
    }

    /// The stack of one destination. The chat stack's path is held by the navigator so that other screens can push a
    /// conversation on it.
    @ViewBuilder
    private func stack(for destination: Destination) -> some View {
        if destination == .chats {
            NavigationStack(path: $navigator.chatsPath) {
                DestinationView(destination: destination)
            }
        } else {
            NavigationStack {
                DestinationView(destination: destination)
            }
        }
    }

    private var tabView: some View {
        TabView(selection: selection) {
            ForEach(Destination.allCases) { destination in
                stack(for: destination)
                .tabItem { Label(destination.title, systemImage: destination.systemImage) }
                .badge(badges[destination] ?? 0)
                .tag(destination)
            }
        }
    }

    private var splitView: some View {
        NavigationSplitView {
            List(Destination.allCases, selection: Binding<Destination?>(
                get: { selection.wrappedValue },
                set: { if let value = $0 { selection.wrappedValue = value } }
            )) { destination in
                Label(destination.title, systemImage: destination.systemImage)
                    .badge(badges[destination] ?? 0)
                    .tag(destination)
            }
            .navigationTitle("app_name")
        } detail: {
            stack(for: selection.wrappedValue)
            // A new destination starts with an empty stack.
            .id(selection.wrappedValue)
        }
    }
}

/// The screen of one destination.
struct DestinationView: View {
    let destination: Destination

    var body: some View {
        switch destination {
        case .home:
            HomeView()
        case .chats:
            ChatsView()
        case .announcements:
            AnnouncementsView()
        case .contacts:
            ContactsView()
        case .settings:
            SettingsView()
        }
    }
}
