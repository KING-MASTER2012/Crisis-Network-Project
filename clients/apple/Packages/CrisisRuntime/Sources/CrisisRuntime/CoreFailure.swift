import CrisisCore
import Foundation

/// What can go wrong in the runtime, in terms the application can react to. The `detail` texts are for logs,
/// never for users. It is also the error that every call of the runtime throws.
public enum CoreFailure: Error, Sendable, Equatable {
    /// The database file is not a database, or it was written with another key (or none).
    case databaseUnreadable

    /// The database was written by a newer core. Never downgraded.
    case schemaTooNew(found: UInt32, supported: UInt32)

    /// A key was given but this build of the core has no SQLCipher. Data is never stored plain instead.
    case encryptionUnavailable

    case unknownContact
    case invalidContactCard
    case invalidArgument(detail: String)

    /// Bindings and native library (or app and core) disagree about the interface version.
    case versionMismatch(expected: UInt32, actual: UInt32)

    /// Storage, crypto, protocol or mesh error that the caller may survive.
    case other(detail: String)

    /// The core reports it is in an unknown state (an earlier panic). Fatal.
    case internalError(detail: String)

    /// Anything that is not a core error: a bug in the runtime. Fatal.
    case unexpected(description: String)

    /// The identity seed or database key cannot be provided. `resettable` is `true` when stored secrets exist but
    /// cannot be read: the only way forward is an explicit, user-confirmed reset, which loses the identity.
    case secretsUnavailable(reason: String, resettable: Bool)

    /// The runtime was stopped (or failed) and does not accept calls.
    case runtimeStopped

    /// `true` when the node cannot continue and the runtime stops itself.
    public var isFatal: Bool {
        switch self {
        case .internalError, .unexpected: return true
        default: return false
        }
    }
}

extension CoreFailure {
    /// Maps any error of a core call to a failure.
    ///
    /// This is the only place that spells the cases of the bindings' `CoreError`. If the compiler says that
    /// `CoreError` has no member `WrongKeyOrNotADatabase`, the generator spells them in lower camel case
    /// (`wrongKeyOrNotADatabase`): change them here, nowhere else.
    public init(_ error: Error) {
        if let failure = error as? CoreFailure {
            self = failure
            return
        }
        guard let core = error as? CoreError else {
            self = .unexpected(description: String(describing: error))
            return
        }
        switch core {
        case .WrongKeyOrNotADatabase: self = .databaseUnreadable
        case .EncryptionUnavailable: self = .encryptionUnavailable
        case let .SchemaTooNew(found, supported): self = .schemaTooNew(found: found, supported: supported)
        case .UnknownContact: self = .unknownContact
        case .InvalidContactCard: self = .invalidContactCard
        case let .InvalidArgument(detail): self = .invalidArgument(detail: detail)
        case let .InvalidConfig(detail): self = .invalidArgument(detail: detail)
        case let .VersionMismatch(expected, actual): self = .versionMismatch(expected: expected, actual: actual)
        case let .Internal(detail): self = .internalError(detail: detail)
        case let .Storage(detail): self = .other(detail: detail)
        case let .Crypto(detail): self = .other(detail: detail)
        case let .Protocol(detail): self = .other(detail: detail)
        case let .Mesh(detail): self = .other(detail: detail)
        }
    }
}
