import SwiftUI

/// What the screens of the later phases (chats I6, announcements I6, contacts I5) show until they exist.
struct PlaceholderView: View {
    let destination: Destination
    let title: LocalizedStringKey
    let message: LocalizedStringKey

    var body: some View {
        EmptyState(systemImage: destination.systemImage, title: title, message: message)
            .navigationTitle(destination.title)
    }
}
