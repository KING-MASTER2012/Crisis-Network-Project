import Foundation

/// Small file helper shared by the clock store and the install marker.
enum AtomicFiles {
    /// Writes `data` to a temporary sibling and moves it over `url`, so that a crash never leaves half a file.
    static func write(_ data: Data, to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
    }
}
