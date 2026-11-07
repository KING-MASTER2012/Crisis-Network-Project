import CrisisCore
import Foundation

/// Whether the database is encrypted. A key is never ignored silently: plaintext is always a deliberate choice.
public enum EncryptionPolicy: Sendable {
    /// Encrypted or nothing: a core without SQLCipher makes opening fail with ``CoreFailure/encryptionUnavailable``.
    case required

    /// Encrypted when the core has SQLCipher, plaintext when it has not. For development builds only.
    case ifAvailable

    /// Never encrypted. Tests.
    case disabled

    /// Release builds require encryption; debug builds use it when the core has it (`build-apple.sh --sqlcipher`),
    /// so that day-to-day development does not need OpenSSL.
    ///
    /// A development device that switches between a build with and a build without SQLCipher cannot read the
    /// other build's database (``CoreFailure/databaseUnreadable``): delete the app, or reset the identity.
    public static var forBuild: EncryptionPolicy {
        #if DEBUG
        return .ifAvailable
        #else
        return .required
        #endif
    }
}

/// Result of ``NodeBootstrap/open(rootDirectory:transport:encryption:secrets:settings:clock:)``.
public enum OpenResult: Sendable {
    /// The runtime exists but is not started yet: call ``NodeRuntime/start()``.
    case ready(NodeRuntime)

    case failed(CoreFailure)
}

/// Brings a node up: checks the interface version, prepares the storage directory, takes the identity seed and
/// the database key from the vault, opens the database and returns a ``NodeRuntime``.
public enum NodeBootstrap {
    /// Opens (or creates) the node. The blocking work (Keychain, SQLite) runs off the caller's executor.
    ///
    /// - Parameters:
    ///   - rootDirectory: the node's directory, normally ``StorageLayout/defaultRoot()``.
    ///   - secrets: where the seed and the database key are kept, normally a ``KeychainSecretStore``.
    public static func open(
        rootDirectory: URL,
        transport: any Transport,
        encryption: EncryptionPolicy,
        secrets: SecretStore,
        settings: NodeSettings? = nil,
        clock: CoreClock? = nil
    ) async -> OpenResult {
        await Task.detached(priority: .userInitiated) {
            openBlocking(
                rootDirectory: rootDirectory,
                transport: transport,
                encryption: encryption,
                secretStore: secrets,
                settings: settings,
                clock: clock
            )
        }.value
    }

    /// Deletes the stored identity (seed and database key) and the node's directory. The user loses the identity
    /// and all messages. Call it only after an explicit confirmation, for example when the open result is
    /// ``CoreFailure/secretsUnavailable(reason:resettable:)`` with `resettable`.
    public static func resetIdentity(rootDirectory: URL, secrets: SecretStore) async {
        await Task.detached(priority: .userInitiated) {
            try? FileManager.default.removeItem(at: rootDirectory)
            try? secrets.deleteAll()
        }.value
    }

    private static func openBlocking(
        rootDirectory root: URL,
        transport: any Transport,
        encryption: EncryptionPolicy,
        secretStore: SecretStore,
        settings: NodeSettings?,
        clock: CoreClock?
    ) -> OpenResult {
        do {
            try requireApiVersion(expected: expectedCoreApiVersion)
        } catch {
            return .failed(CoreFailure(error))
        }
        do {
            try StorageLayout.prepare(root: root)
        } catch {
            return .failed(.other(detail: "cannot prepare the storage directory: \(error)"))
        }

        let manager = FileManager.default
        let marker = StorageLayout.installMarkerURL(in: root)
        let database = StorageLayout.databaseURL(in: root)

        // Keychain items outlive the app on iOS (and on macOS), the app's files do not. Secrets with neither the
        // install marker nor a database next to them belong to an earlier install: a deleted app must start with
        // a new identity, as it does on Android.
        if !manager.fileExists(atPath: marker.path), !manager.fileExists(atPath: database.path) {
            try? secretStore.deleteAll()
        }

        let vault = SecretVault(
            store: secretStore,
            newSeed: { try generateSeed() },
            newDatabaseKey: { try generateDatabaseKey() }
        )
        let secrets: NodeSecrets
        switch vault.loadOrCreate() {
        case let .ready(loaded):
            secrets = loaded
        case let .unavailable(reason, resettable):
            return .failed(.secretsUnavailable(reason: reason, resettable: resettable))
        }
        try? AtomicFiles.write(Data("1".utf8), to: marker)

        let effectiveClock = clock ?? MonotonicWallClock(store: FileHighWaterStore(url: StorageLayout.clockMarkURL(in: root)))
        do {
            let node = try openNode(
                secrets: secrets,
                databasePath: database.path,
                settings: settings ?? defaultSettings(),
                nowMs: UInt64(max(effectiveClock.nowMs(), 0)),
                policy: encryption
            )
            StorageLayout.protect(root)
            return .ready(NodeRuntime(node: node, transport: transport, clock: effectiveClock))
        } catch {
            return .failed(CoreFailure(error))
        }
    }

    private static func openNode(
        secrets: NodeSecrets,
        databasePath: String,
        settings: NodeSettings,
        nowMs: UInt64,
        policy: EncryptionPolicy
    ) throws -> CrisisNode {
        func open(key: Data?) throws -> CrisisNode {
            try CrisisNode.open(seed: secrets.seed, databasePath: databasePath, databaseKey: key, settings: settings, nowMs: nowMs)
        }
        switch policy {
        case .disabled:
            return try open(key: nil)
        case .required:
            return try open(key: secrets.databaseKey)
        case .ifAvailable:
            do {
                return try open(key: secrets.databaseKey)
            } catch {
                guard CoreFailure(error) == .encryptionUnavailable else { throw error }
                return try open(key: nil)
            }
        }
    }
}
