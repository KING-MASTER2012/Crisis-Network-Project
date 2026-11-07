import XCTest
import CrisisCore

/// Phase I0: the Rust core runs on Apple platforms. The heavy lifting is `CoreSelfCheck`, which the app shows
/// on screen too; these tests make every step a separate result.
final class CoreSmokeTests: XCTestCase {
    func testTheLoadedCoreSpeaksTheExpectedInterface() throws {
        let info = try CoreRuntime.verify()
        XCTAssertEqual(info.apiVersion, expectedCoreApiVersion)
        XCTAssertGreaterThanOrEqual(info.minLinkMtu, 64)
        XCTAssertGreaterThan(info.maxPayloadBytes, 0)
    }

    func testAWrongInterfaceVersionIsRefused() {
        XCTAssertThrowsError(try requireApiVersion(expected: expectedCoreApiVersion + 1))
    }

    func testEveryStepOfTheSelfCheckPasses() {
        let report = CoreSelfCheck.run()
        XCTAssertFalse(report.steps.isEmpty)
        for step in report.steps {
            XCTAssertTrue(step.passed, "\(step.title): \(step.detail)")
        }
    }

    func testTheBuildTellsWhatItIs() {
        XCTAssertFalse(CoreBuildInfo.slices.isEmpty)
        XCTAssertTrue(["dev", "release"].contains(CoreBuildInfo.profile))
    }

    /// CI builds with SQLCipher and sets the variable, so that a build that silently lost the feature fails.
    func testTheBuildHasSQLCipherWhenTheEnvironmentSaysSo() throws {
        guard ProcessInfo.processInfo.environment["CRISIS_EXPECT_SQLCIPHER"] == "1" else {
            throw XCTSkip("CRISIS_EXPECT_SQLCIPHER is not set")
        }
        XCTAssertTrue(CoreBuildInfo.sqlcipher)
    }

    func testSeedsAreRandomAndDatabaseKeysAreTheRightSize() throws {
        let first = try generateSeed()
        let second = try generateSeed()
        XCTAssertEqual(first.count, 32)
        XCTAssertNotEqual(first, second)
        XCTAssertEqual(try generateDatabaseKey().count, 32)
    }
}
