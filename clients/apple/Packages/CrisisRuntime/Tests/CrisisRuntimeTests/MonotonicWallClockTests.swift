import XCTest
@testable import CrisisRuntime

private final class Time: @unchecked Sendable {
    var wall: Int64
    var elapsed: Int64

    init(wall: Int64, elapsed: Int64 = 0) {
        self.wall = wall
        self.elapsed = elapsed
    }
}

private struct FailingStore: HighWaterStore {
    func read() -> Int64? { nil }
    func write(_ valueMs: Int64) throws { throw TestError(message: "disk full") }
}

final class MonotonicWallClockTests: XCTestCase {
    private let start: Int64 = 1_800_000_000_000 // after 2020

    private func clock(_ time: Time, store: HighWaterStore = MemoryHighWaterStore()) -> MonotonicWallClock {
        MonotonicWallClock(wallMs: { time.wall }, elapsedMs: { time.elapsed }, store: store)
    }

    func testTimeAdvancesWithTheMonotonicClock() {
        let time = Time(wall: start)
        let clock = clock(time)
        XCTAssertEqual(clock.nowMs(), start)
        time.elapsed += 1_000
        time.wall += 1_000
        XCTAssertEqual(clock.nowMs(), start + 1_000)
    }

    func testAWallClockThatGoesBackwardsIsIgnored() {
        let time = Time(wall: start)
        let clock = clock(time)
        time.elapsed += 5_000
        time.wall -= 10 * 60 * 1_000
        XCTAssertEqual(clock.nowMs(), start + 5_000)
    }

    func testAForwardCorrectionIsAdopted() {
        let time = Time(wall: start)
        let clock = clock(time)
        time.elapsed += 1_000
        time.wall += 3_600_000
        XCTAssertEqual(clock.nowMs(), start + 3_600_000)
    }

    func testSmallDriftIsIgnored() {
        let time = Time(wall: start)
        let clock = clock(time)
        time.elapsed += 1_000
        time.wall += 2_000 // exactly the tolerance: not adopted
        XCTAssertEqual(clock.nowMs(), start + 1_000)
    }

    func testTheStartTimeFollowsTheRulesAcrossRestarts() {
        let hour: Int64 = 3_600_000
        let wall = MonotonicWallClock.plausibleMinMs + 10 * hour
        XCTAssertEqual(MonotonicWallClock.chooseStart(wall: wall, mark: nil), wall)
        XCTAssertEqual(MonotonicWallClock.chooseStart(wall: 0, mark: wall), wall, "an unset clock uses the mark")
        XCTAssertEqual(MonotonicWallClock.chooseStart(wall: wall, mark: wall + hour), wall + hour, "a little behind: keep the mark")
        XCTAssertEqual(MonotonicWallClock.chooseStart(wall: wall, mark: wall + 48 * hour), wall, "far behind: trust the wall clock")
        XCTAssertEqual(MonotonicWallClock.chooseStart(wall: wall, mark: wall - hour), wall, "the mark is in the past")
    }

    func testTheMarkIsStoredAndFlushed() {
        let time = Time(wall: start)
        let store = MemoryHighWaterStore()
        let clock = clock(time, store: store)
        XCTAssertEqual(clock.nowMs(), start)
        XCTAssertEqual(store.read(), start, "the first call stores the mark")
        time.elapsed += 1_000
        time.wall += 1_000
        _ = clock.nowMs()
        XCTAssertEqual(store.read(), start, "not again before the period is over")
        clock.flush()
        XCTAssertEqual(store.read(), start + 1_000)
    }

    func testAFailingStoreDoesNotBreakTheClock() {
        let time = Time(wall: start)
        let clock = clock(time, store: FailingStore())
        time.elapsed += 20_000
        time.wall += 20_000
        XCTAssertEqual(clock.nowMs(), start + 20_000)
        clock.flush()
    }

    func testTheSystemClocksAreSane() {
        XCTAssertGreaterThan(SystemClocks.wallMs(), MonotonicWallClock.plausibleMinMs)
        let first = SystemClocks.elapsedMs()
        XCTAssertGreaterThanOrEqual(SystemClocks.elapsedMs(), first)
    }
}
