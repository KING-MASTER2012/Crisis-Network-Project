import CrisisCore
import XCTest
@testable import CrisisRuntime

/// The mapping is checked against errors the real core produces, not against hand-made ones.
final class CoreFailureTests: XCTestCase {
    func testAFailureStaysAsItIs() {
        XCTAssertEqual(CoreFailure(CoreFailure.runtimeStopped), .runtimeStopped)
    }

    func testAnInterfaceMismatchCarriesBothVersions() {
        do {
            try requireApiVersion(expected: 999)
            XCTFail("a wrong interface version must be refused")
        } catch {
            XCTAssertEqual(CoreFailure(error), .versionMismatch(expected: 999, actual: expectedCoreApiVersion))
        }
    }

    func testABadSeedIsAnInvalidArgument() {
        do {
            _ = try CrisisNode.inMemory(seed: Data(count: 5), settings: defaultSettings(), nowMs: 1)
            XCTFail("a seed of 5 bytes must be refused")
        } catch {
            guard case .invalidArgument = CoreFailure(error) else {
                return XCTFail("expected invalidArgument, got \(CoreFailure(error))")
            }
        }
    }

    func testGarbageIsNotAContactCard() {
        do {
            _ = try inspectContactCard(card: Data(count: 3))
            XCTFail("garbage must be refused")
        } catch {
            // Depending on how the core checks, it is a bad card or a bad argument; both are the caller's mistake.
            switch CoreFailure(error) {
            case .invalidContactCard, .invalidArgument: break
            default: XCTFail("unexpected mapping: \(CoreFailure(error))")
            }
        }
    }

    func testAnErrorThatIsNotFromTheCoreIsUnexpectedAndFatal() {
        let failure = CoreFailure(TestError(message: "boom"))
        guard case .unexpected = failure else { return XCTFail("expected unexpected, got \(failure)") }
        XCTAssertTrue(failure.isFatal)
    }

    func testOnlyTheseFailuresAreFatal() {
        XCTAssertTrue(CoreFailure.internalError(detail: "x").isFatal)
        XCTAssertFalse(CoreFailure.databaseUnreadable.isFatal)
        XCTAssertFalse(CoreFailure.other(detail: "x").isFatal)
        XCTAssertFalse(CoreFailure.secretsUnavailable(reason: "x", resettable: true).isFatal)
        XCTAssertFalse(CoreFailure.runtimeStopped.isFatal)
    }

    func testTheKeyOfADatabaseIsRefusedOrUsedAccordingToTheBuild() throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("node.db").path
        let seed = Data(repeating: 1, count: 32)
        let key = try generateDatabaseKey()
        if CoreBuildInfo.sqlcipher {
            _ = try CrisisNode.open(seed: seed, databasePath: path, databaseKey: key, settings: defaultSettings(), nowMs: 1)
            do {
                _ = try CrisisNode.open(seed: seed, databasePath: path, databaseKey: try generateDatabaseKey(), settings: defaultSettings(), nowMs: 2)
                XCTFail("another key must not open the database")
            } catch {
                XCTAssertEqual(CoreFailure(error), .databaseUnreadable)
            }
        } else {
            do {
                _ = try CrisisNode.open(seed: seed, databasePath: path, databaseKey: key, settings: defaultSettings(), nowMs: 1)
                XCTFail("a key must be refused by a build without SQLCipher")
            } catch {
                XCTAssertEqual(CoreFailure(error), .encryptionUnavailable)
            }
        }
    }
}
