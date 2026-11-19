import Foundation
import Security

/// A failed Keychain call, with the system's description of the status.
public struct KeychainError: Error, CustomStringConvertible, Sendable {
    public let status: OSStatus

    public var description: String {
        let text = SecCopyErrorMessageString(status, nil) as String? ?? "unknown error"
        return "Keychain error \(status): \(text)"
    }
}

/// Keeps secrets in the Keychain as generic passwords of one service.
///
/// * `AfterFirstUnlockThisDeviceOnly`: readable while the phone is locked (a message arriving over Bluetooth in
///   the background must be stored; the default "when unlocked" would make that fail) and never copied to another
///   device or to a backup. A new phone means a new identity, as on Android.
/// * Not synchronised through iCloud.
/// * Unlike Android's Keystore, Keychain items **survive the deletion of the app**; ``NodeBootstrap`` discards
///   such leftovers.
public struct KeychainSecretStore: SecretStore {
    public let service: String
    public let useDataProtectionKeychain: Bool

    /// iOS always uses the data protection keychain. On macOS it needs the restricted `keychain-access-groups`
    /// entitlement (a provisioning profile), which an unsigned test run, a local ad-hoc build and a Developer ID build
    /// outside the App Store do not have, so it is off there. Decided in phase I8 to stay off for the Mac app: the items
    /// live in the login keychain, tied to the code signature (a Developer ID build keeps them across updates), and
    /// switching later needs a migration, because an item written to one keychain is not found in the other. Revisit
    /// with the TestFlight build (I9).
    public static var defaultUsesDataProtection: Bool {
        #if os(macOS)
        return false
        #else
        return true
        #endif
    }

    public init(service: String = "org.crisisnetwork.vault.v1", useDataProtectionKeychain: Bool = KeychainSecretStore.defaultUsesDataProtection) {
        self.service = service
        self.useDataProtectionKeychain = useDataProtectionKeychain
    }

    public func read(account: String) throws -> Data? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess: return result as? Data
        case errSecItemNotFound: return nil
        default: throw KeychainError(status: status)
        }
    }

    public func write(_ data: Data, account: String) throws {
        var add = baseQuery(account: account)
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        add[kSecAttrSynchronizable as String] = false
        var status = SecItemAdd(add as CFDictionary, nil)
        if status == errSecDuplicateItem {
            let update: [String: Any] = [
                kSecValueData as String: data,
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ]
            status = SecItemUpdate(baseQuery(account: account) as CFDictionary, update as CFDictionary)
        }
        guard status == errSecSuccess else { throw KeychainError(status: status) }
    }

    public func delete(account: String) throws {
        let status = SecItemDelete(baseQuery(account: account) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeychainError(status: status) }
    }

    public func deleteAll() throws {
        let status = SecItemDelete(baseQuery(account: nil) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeychainError(status: status) }
    }

    private func baseQuery(account: String?) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        if let account { query[kSecAttrAccount as String] = account }
        #if os(macOS)
        if useDataProtectionKeychain { query[kSecUseDataProtectionKeychain as String] = true }
        #endif
        return query
    }
}
