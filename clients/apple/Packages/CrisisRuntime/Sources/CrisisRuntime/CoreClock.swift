import Foundation

/// The clock the runtime hands to the core (the `nowMs` of every call).
///
/// The core stores receive times in its database and counts message expiry from them, so the clock must be
/// comparable across restarts and must never run backwards. See ``MonotonicWallClock`` for the policy.
public protocol CoreClock: Sendable {
    /// Milliseconds since the Unix epoch, never decreasing for one clock instance.
    func nowMs() -> Int64

    /// Persists whatever the clock needs to survive a restart. Called when the runtime stops.
    func flush()
}

public extension CoreClock {
    func flush() {}
}
