import Foundation

/// Remembers the latest time the clock has handed out, across process restarts.
public protocol HighWaterStore: Sendable {
    /// The stored value, or `nil` when there is none or it cannot be read.
    func read() -> Int64?

    /// Stores `valueMs`. May throw; the clock ignores failures.
    func write(_ valueMs: Int64) throws
}

/// A ``HighWaterStore`` backed by one small file (keep it in the node's own, backup-excluded directory).
public struct FileHighWaterStore: HighWaterStore {
    private let url: URL

    public init(url: URL) {
        self.url = url
    }

    public func read() -> Int64? {
        guard let data = try? Data(contentsOf: url), let text = String(data: data, encoding: .utf8) else { return nil }
        return Int64(text.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    public func write(_ valueMs: Int64) throws {
        try AtomicFiles.write(Data(String(valueMs).utf8), to: url)
    }
}

/// A ``HighWaterStore`` that lives in memory: for tests and previews.
public final class MemoryHighWaterStore: HighWaterStore, @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64?

    public init(_ value: Int64? = nil) {
        self.value = value
    }

    public func read() -> Int64? {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    public func write(_ valueMs: Int64) throws {
        lock.lock()
        defer { lock.unlock() }
        value = valueMs
    }
}
