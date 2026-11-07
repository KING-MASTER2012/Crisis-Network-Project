import XCTest
@testable import CrisisRuntime

final class TimerPolicyTests: XCTestCase {
    func testTheFirstTickIsImmediate() {
        XCTAssertEqual(TimerPolicy().waitMs(nowMs: 1_000), 0)
    }

    func testAfterATickTheIdlePeriodApplies() {
        var policy = TimerPolicy()
        policy.onTick(nowMs: 1_000)
        XCTAssertEqual(policy.waitMs(nowMs: 1_000), 30_000)
        XCTAssertEqual(policy.waitMs(nowMs: 11_000), 20_000)
    }

    func testTheDeadlineOfTheCoreWinsWhenItIsSooner() {
        var policy = TimerPolicy()
        policy.onTick(nowMs: 1_000)
        policy.onOutput(nextTimerMs: 5_000)
        XCTAssertEqual(policy.waitMs(nowMs: 1_200), 3_800)
    }

    func testNoDeadlineKeepsThePreviousOne() {
        var policy = TimerPolicy()
        policy.onTick(nowMs: 1_000)
        policy.onOutput(nextTimerMs: 5_000)
        policy.onOutput(nextTimerMs: nil)
        XCTAssertEqual(policy.waitMs(nowMs: 1_000), 4_000)
    }

    func testALaterDeadlineThanTheIdleTickIsIgnored() {
        var policy = TimerPolicy()
        policy.onTick(nowMs: 0)
        policy.onOutput(nextTimerMs: 100_000)
        XCTAssertEqual(policy.waitMs(nowMs: 0), 30_000)
    }

    func testATickConsumesTheDeadline() {
        var policy = TimerPolicy()
        policy.onOutput(nextTimerMs: 5_000)
        policy.onTick(nowMs: 2_000)
        XCTAssertEqual(policy.waitMs(nowMs: 2_000), 30_000)
    }

    func testADeadlineInThePastCannotMakeTheRuntimeSpin() {
        var policy = TimerPolicy()
        policy.onTick(nowMs: 1_000)
        policy.onOutput(nextTimerMs: 900)
        XCTAssertEqual(policy.waitMs(nowMs: 1_000), 10)
        XCTAssertEqual(policy.waitMs(nowMs: 1_005), 5)
        XCTAssertEqual(policy.waitMs(nowMs: 1_010), 0)
    }
}
