import XCTest
@testable import CrisisRuntime

private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var value: UInt8 = 0

    func next() -> Data {
        lock.lock()
        defer { lock.unlock() }
        value += 1
        return Data(repeating: value, count: 32)
    }
}

final class SecretVaultTests: XCTestCase {
    private func vault(_ store: MemorySecretStore, counter: Counter = Counter()) -> SecretVault {
        SecretVault(store: store, newSeed: { counter.next() }, newDatabaseKey: { counter.next() })
    }

    private func secrets(_ result: VaultResult, file: StaticString = #filePath, line: UInt = #line) throws -> NodeSecrets {
        guard case let .ready(secrets) = result else {
            XCTFail("the vault is not ready: \(result)", file: file, line: line)
            throw TestError(message: "not ready")
        }
        return secrets
    }

    func testMissingSecretsAreCreatedAndThenReused() throws {
        let store = MemorySecretStore()
        let vault = vault(store)
        let first = try secrets(vault.loadOrCreate())
        let second = try secrets(vault.loadOrCreate())
        XCTAssertEqual(first.seed, second.seed)
        XCTAssertEqual(first.databaseKey, second.databaseKey)
        XCTAssertNotEqual(first.seed, first.databaseKey)
        XCTAssertEqual(store.writeCount, 2)
    }

    func testStoredSecretsAreUsedAsTheyAre() throws {
        let store = MemorySecretStore()
        store.plant(Data(repeating: 7, count: 32), account: SecretVault.seedAccount)
        store.plant(Data(repeating: 8, count: 32), account: SecretVault.databaseKeyAccount)
        let loaded = try secrets(vault(store).loadOrCreate())
        XCTAssertEqual(loaded.seed, Data(repeating: 7, count: 32))
        XCTAssertEqual(loaded.databaseKey, Data(repeating: 8, count: 32))
        XCTAssertEqual(store.writeCount, 0)
    }

    func testSecretsThatCannotBeReadAreNeverReplaced() {
        let store = MemorySecretStore()
        store.plant(Data(repeating: 7, count: 32), account: SecretVault.seedAccount)
        store.failReads(true)
        guard case let .unavailable(_, resettable) = vault(store).loadOrCreate() else {
            return XCTFail("an unreadable seed must make the vault unavailable")
        }
        XCTAssertTrue(resettable)
        XCTAssertEqual(store.writeCount, 0, "nothing may be overwritten")
    }

    func testAnEmptySecretIsNotAnIdentity() {
        let store = MemorySecretStore()
        store.plant(Data(), account: SecretVault.seedAccount)
        guard case let .unavailable(_, resettable) = vault(store).loadOrCreate() else {
            return XCTFail("an empty seed must make the vault unavailable")
        }
        XCTAssertTrue(resettable)
        XCTAssertEqual(store.writeCount, 0)
    }

    func testACreationThatFailsIsNotResettable() {
        let store = MemorySecretStore()
        store.failWrites(true)
        guard case let .unavailable(_, resettable) = vault(store).loadOrCreate() else {
            return XCTFail("a failing store must make the vault unavailable")
        }
        XCTAssertFalse(resettable)
    }

    func testResettingStartsOver() throws {
        let store = MemorySecretStore()
        let vault = vault(store)
        let before = try secrets(vault.loadOrCreate())
        vault.resetIdentity()
        XCTAssertTrue(store.accounts.isEmpty)
        let after = try secrets(vault.loadOrCreate())
        XCTAssertNotEqual(before.seed, after.seed)
    }
}
