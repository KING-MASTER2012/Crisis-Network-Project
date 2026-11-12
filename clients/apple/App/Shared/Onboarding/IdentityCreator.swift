import CrisisRuntime
import Foundation

/// A transport that does nothing: opens the node for what needs only the node (the identity) and no radio.
final class NullTransport: Transport, @unchecked Sendable {
    let events = AsyncStream<TransportEvent> { $0.finish() }

    func start() throws {}
    func stop() {}
    func send(peer: UInt64, bytes: Data) {}
    func disconnect(peer: UInt64) {}
}

enum IdentityResult: Sendable {
    /// The identity exists. Both values are public.
    case created(safetyNumber: String, nodeIdHex: String)
    case failed(CoreFailure)
}

/// Creates the identity of this device (phase I4): the first time the node is opened, the core's key pair is made from a
/// fresh random seed, which the vault keeps in the Keychain; it is the same step that every later start performs.
/// Nothing is sent anywhere and no account exists.
///
/// The node is opened without a radio and closed again at once; the mesh itself is started by the app's node. While
/// the mesh is running, the identity of the running node is returned instead (the database must not be opened twice).
enum IdentityCreator {
    @MainActor
    static func create(mesh: BleLinkTestModel) async -> IdentityResult {
        if mesh.running || mesh.busy {
            if let identity = mesh.runningIdentity {
                return .created(safetyNumber: identity.safetyNumber, nodeIdHex: identity.nodeIdHex)
            }
            return .failed(.runtimeStopped)
        }
        let root: URL
        do {
            root = try StorageLayout.defaultRoot()
        } catch {
            return .failed(.other(detail: "\(error)"))
        }
        let opened = await NodeBootstrap.open(rootDirectory: root, transport: NullTransport(), encryption: .forBuild, secrets: KeychainSecretStore())
        switch opened {
        case let .failed(failure):
            return .failed(failure)
        case let .ready(runtime):
            let result = IdentityResult.created(safetyNumber: runtime.safetyNumber, nodeIdHex: runtime.nodeIdHex)
            await runtime.stop()
            return result
        }
    }

    /// Deletes the identity and everything stored with it. Only after the user confirmed: it cannot be recovered.
    static func reset() async {
        guard let root = try? StorageLayout.defaultRoot() else { return }
        await NodeBootstrap.resetIdentity(rootDirectory: root, secrets: KeychainSecretStore())
    }
}
