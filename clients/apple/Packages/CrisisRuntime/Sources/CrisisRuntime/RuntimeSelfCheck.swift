import CrisisCore
import Foundation

private struct SelfCheckFailure: Error, CustomStringConvertible {
    let description: String
}

private func expect(_ condition: Bool, _ message: @autoclosure () -> String) throws {
    if !condition { throw SelfCheckFailure(description: message()) }
}

/// The first notification of `stream` that satisfies `predicate`, or `nil` after `timeoutMs` (or when the stream ends).
func firstNotification(
    in stream: AsyncStream<CoreNotification>,
    timeoutMs: Int = 5_000,
    where predicate: @escaping @Sendable (CoreNotification) -> Bool
) async -> CoreNotification? {
    await withTaskGroup(of: CoreNotification?.self) { group in
        group.addTask {
            for await notification in stream where predicate(notification) { return notification }
            return nil
        }
        group.addTask {
            try? await Task.sleep(nanoseconds: UInt64(timeoutMs) * 1_000_000)
            return nil
        }
        let first = await group.next()
        group.cancelAll()
        return first ?? nil
    }
}

/// A self-test of the runtime on the device it runs on: the clock, the Keychain, the bootstrap with its handling of
/// leftovers of an earlier install, and two runtimes talking over an in-memory link. It needs no Bluetooth.
///
/// Phase I1's exit test: the same code runs in the iOS app's tests on the simulator and in the app on a phone.
public enum RuntimeSelfCheck {
    public static func run() async -> SelfCheckReport {
        var steps: [SelfCheckStep] = []

        func record(_ title: String, _ body: () async throws -> String) async {
            do {
                let detail = try await body()
                steps.append(SelfCheckStep(id: steps.count, title: title, passed: true, detail: detail))
            } catch {
                steps.append(SelfCheckStep(id: steps.count, title: title, passed: false, detail: "\(error)"))
            }
        }

        await record("Clock") { try checkClock() }
        await record("Keychain") { try checkKeychain() }
        await record("Bootstrap and reinstall") { try await checkBootstrap() }
        await record("Two runtimes talk") { try await checkTwoRuntimes() }

        return SelfCheckReport(steps: steps)
    }

    private static func temporaryDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("crisis-runtime-check-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private static func checkClock() throws -> String {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = FileHighWaterStore(url: directory.appendingPathComponent("clock.mark"))
        let clock = MonotonicWallClock(store: store)
        var last = clock.nowMs()
        for _ in 0..<1_000 {
            let now = clock.nowMs()
            try expect(now >= last, "the clock went backwards")
            last = now
        }
        try expect(last >= MonotonicWallClock.plausibleMinMs, "the clock is before 2020: \(last)")
        clock.flush()
        try expect((store.read() ?? 0) >= last, "the high-water mark was not stored")
        let restarted = MonotonicWallClock(store: store)
        try expect(restarted.nowMs() >= last, "a restarted clock is behind the old one")
        return "monotonic, stored and restored; now is \(last) ms"
    }

    private static func checkKeychain() throws -> String {
        let store = KeychainSecretStore(service: "org.crisisnetwork.selfcheck.\(UUID().uuidString)")
        defer { try? store.deleteAll() }
        try expect(try store.read(account: "a") == nil, "an empty Keychain returned something")
        try store.write(Data([1, 2, 3]), account: "a")
        try expect(try store.read(account: "a") == Data([1, 2, 3]), "a written item was not read back")
        try store.write(Data([4, 5]), account: "a")
        try expect(try store.read(account: "a") == Data([4, 5]), "an item was not replaced")
        try store.delete(account: "a")
        try expect(try store.read(account: "a") == nil, "a deleted item is still there")
        return "items are written, replaced, read and deleted"
    }

    private static func checkBootstrap() async throws -> String {
        let store = KeychainSecretStore(service: "org.crisisnetwork.selfcheck.\(UUID().uuidString)")
        let root = try temporaryDirectory().appendingPathComponent("node", isDirectory: true)
        defer {
            try? store.deleteAll()
            try? FileManager.default.removeItem(at: root.deletingLastPathComponent())
        }

        func open() async throws -> NodeRuntime {
            let transport = LoopbackNetwork().endpoint()
            switch await NodeBootstrap.open(rootDirectory: root, transport: transport, encryption: .ifAvailable, secrets: store) {
            case let .ready(runtime): return runtime
            case let .failed(failure): throw failure
            }
        }

        let first = try await open()
        let identity = first.nodeId
        await first.stop()

        let second = try await open()
        let sameIdentity = second.nodeId == identity
        await second.stop()
        try expect(sameIdentity, "the identity changed after a restart")

        // A reinstall: the app's files are gone, the Keychain items are not. A deleted app must not keep its identity.
        try FileManager.default.removeItem(at: root)
        let third = try await open()
        let newIdentity = third.nodeId != identity
        await third.stop()
        try expect(newIdentity, "the Keychain items of an earlier install were reused")
        return "the identity survives a restart and not a reinstall"
    }

    private static func checkTwoRuntimes() async throws -> String {
        let clock = MonotonicWallClock(store: MemoryHighWaterStore())
        let network = LoopbackNetwork()
        let transportA = network.endpoint()
        let transportB = network.endpoint()
        let now = UInt64(clock.nowMs())
        let a = NodeRuntime(
            node: try CrisisNode.inMemory(seed: Data(repeating: 1, count: 32), settings: defaultSettings(), nowMs: now),
            transport: transportA,
            clock: clock
        )
        let b = NodeRuntime(
            node: try CrisisNode.inMemory(seed: Data(repeating: 2, count: 32), settings: defaultSettings(), nowMs: now),
            transport: transportB,
            clock: clock
        )
        try await a.start()
        try await b.start()
        let atA = await a.notifications()
        let atB = await b.notifications()
        network.connect(transportA, transportB)

        let hello = Data("hello mesh".utf8)
        _ = try await a.publishBroadcast(payload: hello)
        let broadcast = await firstNotification(in: atB) { notification in
            if case let .messageReceived(_, _, kind, body) = notification { return kind == .broadcast && body == hello }
            return false
        }

        _ = try await a.addContact(card: b.contactCard)
        _ = try await b.addContact(card: a.contactCard)
        let secret = Data("hello you".utf8)
        let outcome = try await a.publishDirect(to: b.nodeId, plaintext: secret)
        let direct = await firstNotification(in: atB) { notification in
            if case let .messageReceived(_, _, kind, body) = notification { return kind == .direct && body == secret }
            return false
        }
        let messageId = outcome.messageId
        let acknowledged = await firstNotification(in: atA) { notification in
            if case let .delivered(id) = notification { return id == messageId }
            return false
        }

        await a.stop()
        await b.stop()
        try expect(broadcast != nil, "the broadcast did not reach the other runtime")
        try expect(direct != nil, "the private message did not reach the other runtime")
        try expect(acknowledged != nil, "the acknowledgement did not come back")
        return "broadcast, private message and acknowledgement between two runtimes"
    }
}
