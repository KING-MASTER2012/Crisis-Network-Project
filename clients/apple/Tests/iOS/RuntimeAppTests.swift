import CrisisRuntime
import XCTest

/// Runs inside the iOS app's test host, on the simulator: the runtime with the real Keychain, the storage
/// directory and the clock of an iOS app. (The package tests use an in-memory secret store, because an unsigned
/// test run on the Mac has no usable Keychain.)
final class RuntimeAppTests: XCTestCase {
    func testTheRuntimeWorksInsideTheApp() async {
        let report = await RuntimeSelfCheck.run()
        XCTAssertFalse(report.steps.isEmpty)
        for step in report.steps {
            XCTAssertTrue(step.passed, "\(step.title): \(step.detail)")
        }
    }
}
