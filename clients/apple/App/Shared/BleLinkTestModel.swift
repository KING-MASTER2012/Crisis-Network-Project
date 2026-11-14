import CrisisCore
import CrisisRuntime
import Foundation
import SwiftUI
#if os(iOS)
import UIKit
#endif

/// The node of the app and the measuring half of the Bluetooth test bench (phases I2 and I3). It opens the node with a
/// ``BleTransport``, sends test announcements and counts what arrives. It is a development tool, not product UI (the
/// real screens arrive in I4 and later), but the part that keeps the node running is the real thing:
///
/// * **One instance for the app** (`shared`), started at launch when it was running before (`resumeIfEnabled()`).
///   That is what state restoration needs: when the system relaunches the app in the background for a Bluetooth
///   event, the transport must be created again at once, with the same identifiers, before any UI exists.
/// * **Foreground and background** are passed to the transport (`appForegroundChanged`): in the background the scan is
///   not paused and an iPhone connects to every neighbour itself. Going to the background also asks the system for a
///   little extra time to finish what is in flight (iOS).
/// * **Power:** the user's profile, lowered to the saver while Low Power Mode is on. On the Mac an activity
///   assertion keeps App Nap from throttling the node's timers.
///
/// The messages are public announcements, because they need no contact exchange (the QR flow is I5).
@MainActor
final class BleLinkTestModel: ObservableObject {
    static let shared = BleLinkTestModel()

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

    private enum Keys {
        static let role = "ble.role"
        static let profile = "ble.profile"
        static let autoStart = "ble.autoStart"
    }

    private let defaults = UserDefaults.standard

    @Published var role: BleRole {
        didSet { defaults.set(role.rawValue, forKey: Keys.role) }
    }

    @Published var profile: BlePowerProfile {
        didSet {
            defaults.set(profile.rawValue, forKey: Keys.profile)
            applyPower()
        }
    }

    /// Start the node at every launch (and so after a relaunch by the system). Set by Start and Stop.
    @Published private(set) var autoStart: Bool

    @Published private(set) var running = false
    @Published private(set) var busy = false
    @Published private(set) var errorText: String?
    @Published private(set) var stats: BleStats?
    @Published private(set) var links: [BleLinkInfo] = []
    @Published private(set) var counters = Counters()
    @Published private(set) var log: [String] = []
    @Published private(set) var nodeId = ""
    @Published private(set) var battery: BatteryReport?
    @Published private(set) var lowPowerMode = ProcessInfo.processInfo.isLowPowerModeEnabled

    /// Counts what the node's data went through (a message arrived, was published or delivered, a neighbour came or
    /// went). Screens that show stored data (the contacts, later the chats) read it again when it changes.
    @Published private(set) var dataVersion = 0

    /// The public identity of the running node (the safety number and the node id), `nil` while it is stopped.
    private(set) var runningIdentity: (safetyNumber: String, nodeIdHex: String)?

    private var runtime: NodeRuntime?

    /// The running node, `nil` while it is stopped. The database can only be opened once, so the screens that read or
    /// change stored data (contacts, chats) go through this one instead of opening the node themselves.
    var activeRuntime: NodeRuntime? { running ? runtime : nil }
    private var transport: BleTransport?
    private var tasks: [Task<Void, Never>] = []
    private var foreground = true
    private let batteryProbe = BatteryProbe()
    private var powerObserver: NSObjectProtocol?
    #if os(iOS)
    private var backgroundTask = UIBackgroundTaskIdentifier.invalid
    #endif
    #if os(macOS)
    private var activity: NSObjectProtocol?
    #endif

    private static let maxLogLines = 150

    private init() {
        let stored = UserDefaults.standard
        role = BleRole(rawValue: stored.string(forKey: Keys.role) ?? "") ?? .both
        profile = BlePowerProfile(rawValue: stored.string(forKey: Keys.profile) ?? "") ?? .balanced
        autoStart = stored.bool(forKey: Keys.autoStart)
        powerObserver = NotificationCenter.default.addObserver(
            forName: .NSProcessInfoPowerStateDidChange, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.applyPower() }
        }
    }

    // MARK: Start and stop

    /// Called at launch, also when the system relaunches the app in the background for Bluetooth.
    func resumeIfEnabled() {
        guard autoStart, !running, !busy else { return }
        #if os(iOS)
        foreground = UIApplication.shared.applicationState != .background
        #endif
        Task { await start() }
    }

    func start() async {
        guard !running, !busy else { return }
        busy = true
        errorText = nil
        defer { busy = false }

        var config = BleConfig()
        config.role = role
        config.profile = effectiveProfile
        if let info = try? CoreRuntime.verify() { config.minLinkMtu = Int(info.minLinkMtu) }
        let transport = BleTransport.coreBluetooth(config: config)
        transport.setAppForeground(foreground)

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
            runningIdentity = (runtime.safetyNumber, runtime.nodeIdHex)
            running = true
            autoStart = true
            defaults.set(true, forKey: Keys.autoStart)
            keepAwake(true)
            batteryProbe.begin()
            appendLog("started as \(role.rawValue), \(effectiveProfile.rawValue), \(foreground ? "foreground" : "background"), node \(nodeId)")
            observe(runtime: runtime, transport: transport)
        }
    }

    func stop() async {
        guard running, let runtime else { return }
        autoStart = false
        defaults.set(false, forKey: Keys.autoStart)
        for task in tasks { task.cancel() }
        tasks.removeAll()
        await runtime.stop()
        self.runtime = nil
        transport = nil
        running = false
        runningIdentity = nil
        links = []
        stats = nil
        battery = nil
        keepAwake(false)
        batteryProbe.end()
        endBackgroundTask()
        appendLog("stopped")
    }

    // MARK: Foreground, background, power

    /// The profile in force: the user's, or the saver while Low Power Mode is on.
    var effectiveProfile: BlePowerProfile {
        lowPowerMode ? .saver : profile
    }

    func appForegroundChanged(_ isForeground: Bool) {
        foreground = isForeground
        transport?.setAppForeground(isForeground)
        if isForeground {
            endBackgroundTask()
        } else {
            beginBackgroundTask()
        }
        if running { appendLog(isForeground ? "app in the foreground" : "app in the background") }
    }

    private func applyPower() {
        lowPowerMode = ProcessInfo.processInfo.isLowPowerModeEnabled
        transport?.setPowerProfile(effectiveProfile)
        if running { appendLog("power profile \(effectiveProfile.rawValue)\(lowPowerMode ? " (Low Power Mode)" : "")") }
    }

    /// While the screen is on and the node runs: no sleep on iOS (a test bench), no App Nap on the Mac.
    private func keepAwake(_ on: Bool) {
        #if os(iOS)
        UIApplication.shared.isIdleTimerDisabled = on
        #endif
        #if os(macOS)
        if on, activity == nil {
            activity = ProcessInfo.processInfo.beginActivity(options: [.userInitiatedAllowingIdleSystemSleep], reason: "Crisis Network mesh")
        } else if !on, let current = activity {
            ProcessInfo.processInfo.endActivity(current)
            activity = nil
        }
        #endif
    }

    /// iOS gives an app that goes to the background a few seconds to finish its work. Packets that are in flight get
    /// them; after 25 s the node goes on only through Bluetooth wake-ups.
    private func beginBackgroundTask() {
        #if os(iOS)
        guard running, backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "crisis-mesh") { [weak self] in
            Task { @MainActor in self?.endBackgroundTask() }
        }
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 25_000_000_000)
            self?.endBackgroundTask()
        }
        #endif
    }

    private func endBackgroundTask() {
        #if os(iOS)
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
        #endif
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
        batteryProbe.begin()
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
        battery = batteryProbe.report()
    }

    private func handle(_ notification: CoreNotification) {
        dataVersion &+= 1
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
