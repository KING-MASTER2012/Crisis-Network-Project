import CrisisCore
import CrisisRuntime
import Foundation
import SwiftUI
#if os(iOS)
import UIKit
#endif

/// The measuring half of the Bluetooth test bench (phase I2), the counterpart of the Android `BleLinkTestController`:
/// it opens the node with a ``BleTransport``, sends test announcements through it and counts what arrives. It is a
/// development tool, not product UI (the real screens arrive in I4 and later).
///
/// The messages are public announcements, because they need no contact exchange (the QR flow is I5).
@MainActor
final class BleLinkTestModel: ObservableObject {
    /// Ten messages stay below the core's per-sender burst limit of 20 (refill 2 per second).
    static let burstCount = 10
    static let burstPayloadBytes = 1_000

    struct Counters: Equatable {
        var messages = 0
        var bytes = 0
        var firstSeconds: Double?
        var lastSeconds: Double?

        /// Application payload bytes per second between the first and the last received message.
        var bytesPerSecond: Double? {
            guard let first = firstSeconds, let last = lastSeconds, messages >= 2, last > first else { return nil }
            return Double(bytes) / (last - first)
        }
    }

    @Published var role: BleRole = .both
    @Published private(set) var running = false
    @Published private(set) var busy = false
    @Published private(set) var errorText: String?
    @Published private(set) var stats: BleStats?
    @Published private(set) var links: [BleLinkInfo] = []
    @Published private(set) var counters = Counters()
    @Published private(set) var log: [String] = []
    @Published private(set) var nodeId = ""

    private var runtime: NodeRuntime?
    private var transport: BleTransport?
    private var tasks: [Task<Void, Never>] = []

    private static let maxLogLines = 150

    // MARK: Start and stop

    func start() async {
        guard !running, !busy else { return }
        busy = true
        errorText = nil
        defer { busy = false }

        var config = BleConfig()
        config.role = role
        if let info = try? CoreRuntime.verify() { config.minLinkMtu = Int(info.minLinkMtu) }
        let transport = BleTransport.coreBluetooth(config: config)

        let root: URL
        do {
            root = try StorageLayout.defaultRoot()
        } catch {
            errorText = "No storage directory: \(error)"
            return
        }
        switch await NodeBootstrap.open(rootDirectory: root, transport: transport, encryption: .forBuild, secrets: KeychainSecretStore()) {
        case let .failed(failure):
            errorText = "The node did not open: \(failure)"
        case let .ready(runtime):
            do {
                try await runtime.start()
            } catch {
                errorText = "The node did not start: \(error)"
                await runtime.stop()
                return
            }
            self.runtime = runtime
            self.transport = transport
            nodeId = String(runtime.nodeIdHex.prefix(16))
            running = true
            #if os(iOS)
            // Bluetooth runs in the foreground only in this phase: keep the screen on while testing.
            UIApplication.shared.isIdleTimerDisabled = true
            #endif
            appendLog("started as \(role.rawValue), node \(nodeId)")
            observe(runtime: runtime, transport: transport)
        }
    }

    func stop() async {
        guard running, let runtime else { return }
        for task in tasks { task.cancel() }
        tasks.removeAll()
        await runtime.stop()
        self.runtime = nil
        transport = nil
        running = false
        links = []
        stats = nil
        #if os(iOS)
        UIApplication.shared.isIdleTimerDisabled = false
        #endif
        appendLog("stopped")
    }

    // MARK: Test traffic

    func sendAnnouncement() {
        guard let runtime else { return }
        let text = "hello from \(role.rawValue) \(Int(Date().timeIntervalSince1970) % 100_000)"
        Task {
            do {
                _ = try await runtime.publishBroadcast(payload: Data(text.utf8))
                appendLog("sent: \(text)")
            } catch {
                appendLog("send failed: \(error)")
            }
        }
    }

    /// Ten announcements of 1,000 bytes, to measure the link: the other device's counters show how long the batch
    /// took to arrive (read them there).
    func sendBurst() {
        guard let runtime else { return }
        Task {
            let started = ProcessInfo.processInfo.systemUptime
            do {
                for index in 1...Self.burstCount {
                    let header = "burst \(Int(ProcessInfo.processInfo.systemUptime * 1000)) \(index)/\(Self.burstCount) "
                    var payload = Data(header.utf8)
                    payload.append(Data(repeating: 0x78, count: max(Self.burstPayloadBytes - payload.count, 0)))
                    _ = try await runtime.publishBroadcast(payload: payload)
                }
                let millis = Int((ProcessInfo.processInfo.systemUptime - started) * 1000)
                appendLog("burst published: \(Self.burstCount) x \(Self.burstPayloadBytes) B in \(millis) ms (delivery is measured on the other device)")
            } catch {
                appendLog("burst failed: \(error)")
            }
        }
    }

    func resetCounters() {
        transport?.resetLinkMetrics()
        counters = Counters()
        refresh()
    }

    // MARK: Observing

    private func observe(runtime: NodeRuntime, transport: BleTransport) {
        tasks.append(Task { [weak self] in
            let stream = await runtime.notifications()
            for await notification in stream { self?.handle(notification) }
        })
        tasks.append(Task { [weak self] in
            let stream = await runtime.failures()
            for await failure in stream { self?.appendLog("failure: \(failure)") }
        })
        tasks.append(Task { [weak self] in
            for await diagnostic in transport.diagnostics { self?.appendLog("ble: \(diagnostic)") }
        })
        tasks.append(Task { [weak self] in
            while !Task.isCancelled {
                self?.refresh()
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        })
    }

    private func refresh() {
        guard let transport else { return }
        links = transport.linkInfos()
        stats = transport.stats()
    }

    private func handle(_ notification: CoreNotification) {
        guard case let .messageReceived(_, _, _, body) = notification else { return }
        let now = ProcessInfo.processInfo.systemUptime
        counters.messages += 1
        counters.bytes += body.count
        if counters.firstSeconds == nil { counters.firstSeconds = now }
        counters.lastSeconds = now
        let preview = String(decoding: body.prefix(40), as: UTF8.self)
        appendLog("received \(body.count) B: \(preview)")
    }

    private func appendLog(_ line: String) {
        log.append(line)
        if log.count > Self.maxLogLines { log.removeFirst(log.count - Self.maxLogLines) }
    }
}
