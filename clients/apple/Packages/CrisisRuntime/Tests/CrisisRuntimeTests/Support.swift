import CrisisCore
import Foundation
import XCTest
@testable import CrisisRuntime

struct TestError: Error {
    let message: String
}

/// A secret store in memory, with switches to make it misbehave.
final class MemorySecretStore: SecretStore, @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String: Data] = [:]
    private var readsFail = false
    private var writesFail = false
    private(set) var writeCount = 0

    func failReads(_ fail: Bool) { lock.lock(); readsFail = fail; lock.unlock() }
    func failWrites(_ fail: Bool) { lock.lock(); writesFail = fail; lock.unlock() }

    var accounts: Set<String> {
        lock.lock()
        defer { lock.unlock() }
        return Set(items.keys)
    }

    func read(account: String) throws -> Data? {
        lock.lock()
        defer { lock.unlock() }
        if readsFail { throw TestError(message: "reads fail") }
        return items[account]
    }

    func write(_ data: Data, account: String) throws {
        lock.lock()
        defer { lock.unlock() }
        if writesFail { throw TestError(message: "writes fail") }
        writeCount += 1
        items[account] = data
    }

    func delete(account: String) throws {
        lock.lock()
        items[account] = nil
        lock.unlock()
    }

    func deleteAll() throws {
        lock.lock()
        items.removeAll()
        lock.unlock()
    }

    /// Plants an item without counting it as a write of the code under test.
    func plant(_ data: Data, account: String) {
        lock.lock()
        items[account] = data
        lock.unlock()
    }
}

/// A transport that cannot start.
final class FailingTransport: Transport, @unchecked Sendable {
    let events = AsyncStream<TransportEvent> { _ in }
    func start() throws { throw TestError(message: "no radio") }
    func stop() {}
    func send(peer: UInt64, bytes: Data) {}
    func disconnect(peer: UInt64) {}
}

/// Polls until `condition` holds, for at most `timeoutMs`.
func eventually(timeoutMs: Int = 5_000, _ condition: () async -> Bool) async -> Bool {
    let deadline = Date().addingTimeInterval(Double(timeoutMs) / 1000)
    while Date() < deadline {
        if await condition() { return true }
        try? await Task.sleep(nanoseconds: 20_000_000)
    }
    return await condition()
}

func temporaryDirectory(_ name: String = "crisis-runtime-test") throws -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(name)-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}

func makeRuntime(seedByte: UInt8, transport: any Transport) throws -> NodeRuntime {
    let clock = MonotonicWallClock(store: MemoryHighWaterStore())
    let node = try CrisisNode.inMemory(
        seed: Data(repeating: seedByte, count: 32),
        settings: defaultSettings(),
        nowMs: UInt64(clock.nowMs())
    )
    return NodeRuntime(node: node, transport: transport, clock: clock)
}

func expectReady(_ result: OpenResult, file: StaticString = #filePath, line: UInt = #line) throws -> NodeRuntime {
    switch result {
    case let .ready(runtime): return runtime
    case let .failed(failure):
        XCTFail("the node did not open: \(failure)", file: file, line: line)
        throw failure
    }
}
