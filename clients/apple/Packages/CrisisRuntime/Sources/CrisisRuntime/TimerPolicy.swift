import Foundation

/// Decides when the runtime must call `tick` next. Pure logic, no clock of its own.
///
/// * The core's `nextTimerMs` replaces the deadline; `nil` keeps the previous one.
/// * A tick consumes the deadline: the core must name a new one if it needs it.
/// * There is always an idle tick every `idleTickMs` ("it is cheap"), so purging and housekeeping happen even if
///   the core asks for nothing.
/// * Two ticks are at least `minGapMs` apart, so a core that keeps returning a deadline in the past cannot spin
///   the runtime.
struct TimerPolicy {
    static let defaultIdleTickMs: Int64 = 30_000
    static let defaultMinGapMs: Int64 = 10

    private let idleTickMs: Int64
    private let minGapMs: Int64
    private var deadline: Int64?
    private var lastTickAt: Int64?

    init(idleTickMs: Int64 = TimerPolicy.defaultIdleTickMs, minGapMs: Int64 = TimerPolicy.defaultMinGapMs) {
        self.idleTickMs = idleTickMs
        self.minGapMs = minGapMs
    }

    mutating func onOutput(nextTimerMs: Int64?) {
        if let nextTimerMs { deadline = nextTimerMs }
    }

    mutating func onTick(nowMs: Int64) {
        deadline = nil
        lastTickAt = nowMs
    }

    /// Milliseconds to wait before the next tick. Zero means "tick now".
    func waitMs(nowMs: Int64) -> Int64 {
        var due: Int64
        if let last = lastTickAt { due = last + idleTickMs } else { due = nowMs }
        if let deadline { due = min(due, deadline) }
        if let last = lastTickAt { due = max(due, last + minGapMs) }
        return max(due - nowMs, 0)
    }
}
