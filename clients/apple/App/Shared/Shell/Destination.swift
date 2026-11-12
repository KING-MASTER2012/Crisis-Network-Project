import SwiftUI

/// The places the shell leads to: the tabs of an iPhone and the sidebar of an iPad or a Mac. The same five as the
/// Android client's bottom bar.
enum Destination: String, CaseIterable, Identifiable, Hashable, Sendable {
    case home
    case chats
    case announcements
    case contacts
    case settings

    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .home: return "nav_home"
        case .chats: return "nav_chats"
        case .announcements: return "nav_announcements"
        case .contacts: return "nav_contacts"
        case .settings: return "nav_settings"
        }
    }

    var systemImage: String {
        switch self {
        case .home: return "house"
        case .chats: return "bubble.left.and.bubble.right"
        case .announcements: return "megaphone"
        case .contacts: return "person.2"
        case .settings: return "gearshape"
        }
    }

    /// The destination stored by an earlier run; the home screen for anything unknown.
    static func restored(_ raw: String) -> Destination {
        Destination(rawValue: raw) ?? .home
    }
}
