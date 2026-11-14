import SwiftUI

/// The frame of the app after the introduction: a **tab bar** on an iPhone (a compact width) and a **sidebar with a
/// detail column** on an iPad and a Mac (a regular width). Every destination owns a native `NavigationStack`: the back
/// button and the swipe from the edge are the platform's, and a screen pushed on top of a destination goes away with a
/// swipe.
struct AppShell: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @SceneStorage("shell.destination") private var storedDestination = Destination.home.rawValue

    /// Unread counts and the like, per destination. Empty until the chats and announcements exist (phase I6).
    var badges: [Destination: Int] = [:]

    private var selection: Binding<Destination> {
        Binding(
            get: { Destination.restored(storedDestination) },
            set: { storedDestination = $0.rawValue }
        )
    }

    var body: some View {
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

    private var tabView: some View {
        TabView(selection: selection) {
            ForEach(Destination.allCases) { destination in
                NavigationStack {
                    DestinationView(destination: destination)
                }
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
            NavigationStack {
                DestinationView(destination: selection.wrappedValue)
            }
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
            PlaceholderView(destination: .chats, title: "ph_chats_title", message: "ph_chats_body")
        case .announcements:
            PlaceholderView(destination: .announcements, title: "ph_announcements_title", message: "ph_announcements_body")
        case .contacts:
            ContactsView()
        case .settings:
            SettingsView()
        }
    }
}
