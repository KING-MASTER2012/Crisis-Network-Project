import Foundation

/// Where the node keeps its files and how they are protected.
///
/// Everything lives under one directory, `Application Support/Crisis`: the database (with SQLite's `-wal` and
/// `-shm` files), the clock's high-water mark and the install marker.
///
/// * **Excluded from backups** (`isExcludedFromBackup`): the database key is bound to this device, so a restored
///   database would be unreadable, and an identity must not be cloned onto a second phone.
/// * **`completeUntilFirstUserAuthentication`** on iOS: the files are readable after the first unlock, also while
///   the phone is locked, which a node that receives messages over Bluetooth in the background needs. The
///   stricter default `complete` class would make every write fail on a locked phone.
public enum StorageLayout {
    /// `Application Support/Crisis` (inside the app's container on macOS).
    public static func defaultRoot() throws -> URL {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        return base.appendingPathComponent("Crisis", isDirectory: true)
    }

    /// Creates the directory and applies the protection and backup rules to it.
    public static func prepare(root: URL) throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var directory = root
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        protect(root)
    }

    /// Applies the protection class to the directory and to what is in it now (best effort: SQLite creates its
    /// side files later, which then inherit the class of the directory). Does nothing on macOS.
    public static func protect(_ root: URL) {
        #if os(iOS)
        let attributes: [FileAttributeKey: Any] = [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        let manager = FileManager.default
        try? manager.setAttributes(attributes, ofItemAtPath: root.path)
        let contents = (try? manager.contentsOfDirectory(atPath: root.path)) ?? []
        for name in contents {
            try? manager.setAttributes(attributes, ofItemAtPath: root.appendingPathComponent(name).path)
        }
        #endif
    }

    static func databaseURL(in root: URL) -> URL { root.appendingPathComponent("node.db") }
    static func clockMarkURL(in root: URL) -> URL { root.appendingPathComponent("clock.mark") }
    static func installMarkerURL(in root: URL) -> URL { root.appendingPathComponent("installed-v1") }
}
