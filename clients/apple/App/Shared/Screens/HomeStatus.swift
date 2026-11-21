import Foundation

/// What the home screen says about the mesh, as one value (phase I9): the pill on the card and the sentence a screen
/// reader announces when it changes. No UI, so the rules are tested.
enum HomeStatus: Equatable, Sendable {
    case starting
    case stopping
    case offline
    case searching
    case connected

    static func make(busy: Bool, running: Bool, neighbours: Int) -> HomeStatus {
        if busy { return running ? .stopping : .starting }
        guard running else { return .offline }
        return neighbours > 0 ? .connected : .searching
    }

    /// The key of the sentence in the String Catalog.
    var key: String {
        switch self {
        case .starting: return "home_status_starting"
        case .stopping: return "home_status_stopping"
        case .offline: return "home_status_offline"
        case .searching: return "home_status_searching"
        case .connected: return "home_status_connected"
        }
    }

    var tone: Tone {
        self == .connected ? .success : .neutral
    }

    /// A screen reader announces a state that lasts, not the two that only mean "wait": a person who hears "starting"
    /// and then "searching" in a second has heard one change twice.
    var isAnnounced: Bool {
        self != .starting && self != .stopping
    }
}
