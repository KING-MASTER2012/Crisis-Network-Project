import CrisisCore
import XCTest
@testable import CrisisRuntime

final class NodeBootstrapTests: XCTestCase {
    private var root: URL!
    private var store: MemorySecretStore!

    override func setUpWithError() throws {
        root = try temporaryDirectory("crisis-bootstrap").appendingPathComponent("node", isDirectory: true)
        store = MemorySecretStore()
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root.deletingLastPathComponent())
    }

    private func open(
        _ policy: EncryptionPolicy = .disabled,
        secrets: SecretStore? = nil
    ) async -> OpenResult {
        await NodeBootstrap.open(
            rootDirectory: root,
            transport: LoopbackNetwork().endpoint(),
            encryption: policy,
            secrets: secrets ?? store
        )
    }

    func testTheFirstOpenCreatesTheNodeAndTheSecondKeepsTheIdentity() async throws {
        let first = try expectReady(await open())
        let identity = first.nodeId
        await first.stop()
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("node.db").path))

        let second = try expectReady(await open())
        XCTAssertEqual(second.nodeId, identity)
        await second.stop()
    }

    func testTheNodeDirectoryIsExcludedFromBackups() async throws {
        let runtime = try expectReady(await open())
        let values = try root.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(values.isExcludedFromBackup, true)
        await runtime.stop()
    }

    func testLeftoverSecretsOfAnEarlierInstallAreDiscarded() async throws {
        // The Keychain survived a deletion of the app; its files did not.
        let staleSeed = Data(repeating: 9, count: 32)
        store.plant(staleSeed, account: SecretVault.seedAccount)
        store.plant(Data(repeating: 10, count: 32), account: SecretVault.databaseKeyAccount)
        let staleIdentity = try CrisisNode.inMemory(seed: staleSeed, settings: defaultSettings(), nowMs: 1).nodeId()

        let runtime = try expectReady(await open())
        XCTAssertNotEqual(runtime.nodeId, staleIdentity, "an earlier install's identity must not come back")
        await runtime.stop()
    }

    func testSecretsNextToADatabaseAreKeptEvenWithoutTheMarker() async throws {
        let first = try expectReady(await open())
        let identity = first.nodeId
        await first.stop()
        try FileManager.default.removeItem(at: root.appendingPathComponent("installed-v1"))

        let second = try expectReady(await open())
        XCTAssertEqual(second.nodeId, identity)
        await second.stop()
    }

    func testUnreadableSecretsAreNotOverwrittenAndCanBeReset() async throws {
        let first = try expectReady(await open())
        let identity = first.nodeId
        await first.stop()

        store.failReads(true)
        let writesBefore = store.writeCount
        guard case let .failed(failure) = await open() else { return XCTFail("unreadable secrets must fail the open") }
        guard case let .secretsUnavailable(_, resettable) = failure else { return XCTFail("unexpected failure \(failure)") }
        XCTAssertTrue(resettable)
        XCTAssertEqual(store.writeCount, writesBefore, "nothing may be overwritten")

        store.failReads(false)
        await NodeBootstrap.resetIdentity(rootDirectory: root, secrets: store)
        let fresh = try expectReady(await open())
        XCTAssertNotEqual(fresh.nodeId, identity)
        await fresh.stop()
    }

    func testEncryptionPoliciesFollowTheBuild() async throws {
        if CoreBuildInfo.sqlcipher {
            let runtime = try expectReady(await open(.required))
            await runtime.stop()

            // Another key (a store with other secrets) cannot read the database.
            guard case let .failed(failure) = await open(.required, secrets: MemorySecretStore()) else {
                return XCTFail("a database must not open with another key")
            }
            XCTAssertEqual(failure, .databaseUnreadable)
        } else {
            guard case let .failed(failure) = await open(.required) else {
                return XCTFail("a build without SQLCipher must refuse to open with a key")
            }
            XCTAssertEqual(failure, .encryptionUnavailable)

            let runtime = try expectReady(await open(.ifAvailable))
            await runtime.stop()
        }
    }
}
