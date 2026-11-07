import Foundation

/// The two secrets a node needs: the identity seed and the key of the database.
///
/// Swift's `Data` is a value that the system may copy, so, unlike on Android, these bytes cannot be wiped after
/// use. They live in memory only for the moment of opening the node; they are never logged or written outside the
/// Keychain.
public struct NodeSecrets: Sendable {
    public let seed: Data
    public let databaseKey: Data
}

/// Where the vault keeps secrets: the Keychain on a device, memory in tests.
public protocol SecretStore: Sendable {
    /// The stored bytes, or `nil` when nothing is stored under `account`. Throws when the store cannot be read.
    func read(account: String) throws -> Data?

    func write(_ data: Data, account: String) throws

    func delete(account: String) throws

    /// Deletes everything this store holds (used for a reset and to discard leftovers of an earlier install).
    func deleteAll() throws
}

public enum VaultResult: Sendable {
    case ready(NodeSecrets)

    /// The secrets cannot be provided. `resettable` is `true` when stored items exist but cannot be read; nothing
    /// was overwritten or deleted.
    case unavailable(reason: String, resettable: Bool)
}

/// Stores the identity seed and the database key in a ``SecretStore``.
///
/// * A missing item is created with fresh random bytes.
/// * An item that exists but cannot be read is **never overwritten**: replacing a lost identity silently would be
///   data loss the user did not agree to. The result is ``VaultResult/unavailable(reason:resettable:)``; only
///   ``resetIdentity()`` (an explicit user decision) clears it.
public final class SecretVault: @unchecked Sendable {
    static let seedAccount = "seed"
    static let databaseKeyAccount = "database-key"

    private let store: SecretStore
    private let newSeed: @Sendable () throws -> Data
    private let newDatabaseKey: @Sendable () throws -> Data
    private let lock = NSLock()

    public init(
        store: SecretStore,
        newSeed: @escaping @Sendable () throws -> Data,
        newDatabaseKey: @escaping @Sendable () throws -> Data
    ) {
        self.store = store
        self.newSeed = newSeed
        self.newDatabaseKey = newDatabaseKey
    }

    public func loadOrCreate() -> VaultResult {
        lock.lock()
        defer { lock.unlock() }
        do {
            let seed = try obtain(Self.seedAccount, create: newSeed)
            let key = try obtain(Self.databaseKeyAccount, create: newDatabaseKey)
            return .ready(NodeSecrets(seed: seed, databaseKey: key))
        } catch let failure as VaultFailure {
            return .unavailable(reason: failure.reason, resettable: failure.resettable)
        } catch {
            return .unavailable(reason: "\(error)", resettable: false)
        }
    }

    /// Deletes the stored secrets. The next ``loadOrCreate()`` makes a new identity.
    public func resetIdentity() {
        lock.lock()
        defer { lock.unlock() }
        try? store.deleteAll()
    }

    private struct VaultFailure: Error {
        let reason: String
        let resettable: Bool
    }

    private func obtain(_ account: String, create: () throws -> Data) throws -> Data {
        let stored: Data?
        do {
            stored = try store.read(account: account)
        } catch {
            throw VaultFailure(reason: "the stored \(account) cannot be read: \(error)", resettable: true)
        }
        if let stored {
            if stored.isEmpty { throw VaultFailure(reason: "the stored \(account) is empty", resettable: true) }
            return stored
        }
        do {
            let fresh = try create()
            try store.write(fresh, account: account)
            return fresh
        } catch {
            throw VaultFailure(reason: "could not create \(account): \(error)", resettable: false)
        }
    }
}
