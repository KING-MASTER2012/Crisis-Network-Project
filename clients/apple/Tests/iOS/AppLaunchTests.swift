import CrisisCore
import XCTest

/// Runs inside the iOS app's test host, on the simulator: the Rust static library is linked into a real iOS app
/// and works there (the package tests alone only prove it on the Mac).
final class AppLaunchTests: XCTestCase {
    func testTheCoreWorksInsideTheApp() {
        let report = CoreSelfCheck.run()
        for step in report.steps {
            XCTAssertTrue(step.passed, "\(step.title): \(step.detail)")
        }
    }
}
