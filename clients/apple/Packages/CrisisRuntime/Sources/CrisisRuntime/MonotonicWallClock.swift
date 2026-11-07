import Darwin
import Foundation

/// The system's two time sources, as the clock policy wants them.
public enum SystemClocks {
    /// Milliseconds since the Unix epoch (the wall clock: the user or the network can change it).
    public static func wallMs() -> Int64 {
        Int64((Date().timeIntervalSince1970 * 1000).rounded(.down))
    }

    /// Milliseconds on a monotonic clock that **keeps counting while the device sleeps** (the counterpart of Android's
    /// `elapsedRealtime`) and starts again at every boot. `ProcessInfo.systemUptime` would stop during sleep.
    public static func elapsedMs() -> Int64 {
        let ticks = mach_continuous_time()
        let nanoseconds = ticks * timebase.numer / timebase.denom
        return Int64(nanoseconds / 1_000_000)
    }

    private static let timebase: (numer: UInt64, denom: UInt64) = {
        var info = mach_timebase_info_data_t()
        mach_timebase_info(&info)
        return (UInt64(info.numer), UInt64(max(info.denom, 1)))
    }()
}

/// The clock policy of the runtime.
///
/// The core persists `received_at_ms` and counts expiry from it. The monotonic clock alone restarts at every boot,
/// so stored times would become meaningless; the wall clock alone can jump (the user changes it, the network
/// corrects it, a device boots with a clock at 1970).
///
/// 1. **Inside one process** time advances with the monotonic clock only: a wall clock that jumps backwards, or
///    that is wrong, cannot make the returned time go backwards.
/// 2. **Forward corrections are adopted.** When the wall clock is ahead of the monotonic time by more than
///    `forwardToleranceMs` (the network set the clock after boot), the clock jumps forward to it. Ordinary drift
///    stays below the tolerance and is ignored.
/// 3. **Across restarts** the latest handed-out time is remembered (the "high-water mark"). At start-up:
///    an implausible wall clock (before 2020) means "not set yet": use the larger of wall clock and mark; a
///    plausible wall clock at most 24 hours behind the mark: keep the mark, so time stays monotonic across a
///    restart despite small adjustments; otherwise trust the wall clock, so that a clock that was wrongly far in
///    the future heals after a restart instead of staying ahead forever.
///
/// Known limitation: a wall clock that jumps far *forward* while the app runs is adopted (rule 2), because it
/// cannot be told from a legitimate correction. Messages then expire earlier than intended. The threat model
/// already treats the sender's clock as untrusted; the local clock is trusted to be set by the owner.
public final class MonotonicWallClock: CoreClock, @unchecked Sendable {
    public static let defaultForwardToleranceMs: Int64 = 2_000
    public static let defaultPersistEveryMs: Int64 = 15_000

    /// 2020-01-01T00:00:00Z: a wall clock before this has not been set.
    public static let plausibleMinMs: Int64 = 1_577_836_800_000

    /// A plausible wall clock this far behind the mark is trusted over the mark.
    public static let smallBackwardMs: Int64 = 24 * 60 * 60 * 1000

    private let wallMs: @Sendable () -> Int64
    private let elapsedMs: @Sendable () -> Int64
    private let store: HighWaterStore
    private let forwardToleranceMs: Int64
    private let persistEveryMs: Int64
    private let lock = NSLock()
    private var anchorTime: Int64
    private var anchorElapsed: Int64
    private var lastReturned: Int64
    private var lastPersisted: Int64 = 0

    public init(
        wallMs: @escaping @Sendable () -> Int64,
        elapsedMs: @escaping @Sendable () -> Int64,
        store: HighWaterStore,
        forwardToleranceMs: Int64 = MonotonicWallClock.defaultForwardToleranceMs,
        persistEveryMs: Int64 = MonotonicWallClock.defaultPersistEveryMs
    ) {
        self.wallMs = wallMs
        self.elapsedMs = elapsedMs
        self.store = store
        self.forwardToleranceMs = forwardToleranceMs
        self.persistEveryMs = persistEveryMs
        let start = MonotonicWallClock.chooseStart(wall: wallMs(), mark: store.read())
        anchorTime = start
        anchorElapsed = elapsedMs()
        lastReturned = start
    }

    /// The clock of a device, with its time kept in `store`.
    public convenience init(store: HighWaterStore) {
        self.init(wallMs: SystemClocks.wallMs, elapsedMs: SystemClocks.elapsedMs, store: store)
    }

    public func nowMs() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        let elapsed = elapsedMs()
        let wall = wallMs()
        var now = anchorTime + (elapsed - anchorElapsed)
        if wall - now > forwardToleranceMs {
            anchorTime = wall
            anchorElapsed = elapsed
            now = wall
        }
        if now < lastReturned { now = lastReturned }
        lastReturned = now
        if now - lastPersisted >= persistEveryMs { persist(now) }
        return now
    }

    public func flush() {
        lock.lock()
        defer { lock.unlock() }
        persist(lastReturned)
    }

    private func persist(_ value: Int64) {
        // Set first: a failing store must not be retried on every call.
        lastPersisted = value
        // Losing the high-water mark only weakens rule 3; it never breaks the running clock.
        try? store.write(value)
    }

    /// The start time for a process, from the wall clock and the stored mark (rule 3).
    static func chooseStart(wall: Int64, mark: Int64?) -> Int64 {
        guard let mark else { return wall }
        if wall < plausibleMinMs { return max(wall, mark) }
        let behind = mark - wall
        return (1...smallBackwardMs).contains(behind) ? mark : wall
    }
}
