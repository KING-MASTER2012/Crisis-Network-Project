import Foundation
import XCTest
@testable import CrisisRuntime

/// A clock the tests move by hand.
final class ManualClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64 = 1_000

    var now: Int64 {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func advance(_ ms: Int64) {
        lock.lock()
        value += ms
        lock.unlock()
    }
}

final class FakeScanner: BleScanner {
    var onScanEvent: ((ScanEvent) -> Void)?
    var startCount = 0
    var stopCount = 0
    func start() { startCount += 1 }
    func stop() { stopCount += 1 }
}

final class FakeAdvertiser: BleAdvertiser {
    var onAdvertiseEvent: ((AdvertiseEvent) -> Void)?
    var startCount = 0
    var stopCount = 0
    func start() { startCount += 1 }
    func stop() { stopCount += 1 }
}

final class FakeServer: GattServerPort {
    var onIncoming: ((GattLink) -> Void)?
    var onServerEvent: ((ServerEvent) -> Void)?
    var openCount = 0
    var closeCount = 0
    var hello: UInt64?
    func open() { openCount += 1 }
    func close() { closeCount += 1 }
    func setHello(_ id: UInt64) { hello = id }
}

final class FakeConnector: GattConnector {
    var hello: UInt64?
    var connected: [String] = []
    var links: [String: FakeLink] = [:]
    /// Addresses that cannot be connected to at all.
    var unknown: Set<String> = []

    func setHello(_ id: UInt64) { hello = id }

    func connect(address: String) -> GattLink? {
        connected.append(address)
        if unknown.contains(address) { return nil }
        let link = FakeLink(role: .client, address: address)
        links[address] = link
        return link
    }
}

final class FakeLink: GattLink {
    let role: GattRole
    let address: String
    var onEvent: ((GattLinkEvent) -> Void)?
    var sent: [Data] = []
    var sendResult = SendResult.accepted
    var closeCount = 0
    var polledPayload: Int?

    init(role: GattRole, address: String) {
        self.role = role
        self.address = address
    }

    func send(_ bytes: Data) -> SendResult {
        if sendResult == .accepted { sent.append(bytes) }
        return sendResult
    }

    func pollMtu() {
        if let polledPayload { onEvent?(.mtuChanged(payloadMtu: polledPayload)) }
    }

    func close() { closeCount += 1 }
}

/// A transport with fake radios, a manual clock and a record of what it told the core. Everything that touches the
/// transport's state runs on its queue, like the real ports do.
final class BleRig {
    let queue = DispatchQueue(label: "test.ble")
    let clock = ManualClock()
    let scanner = FakeScanner()
    let advertiser = FakeAdvertiser()
    let server = FakeServer()
    let connector = FakeConnector()
    let transport: BleTransport
    private var recorded: [TransportEvent] = []

    init(role: BleRole = .both, hello: UInt64 = 0x1111, configure: (inout BleConfig) -> Void = { _ in }) {
        var config = BleConfig()
        config.role = role
        config.tickMs = 3_600_000 // the tests call housekeeping themselves
        configure(&config)
        let clock = self.clock
        transport = BleTransport(
            config: config,
            queue: queue,
            scanner: scanner,
            advertiser: advertiser,
            server: server,
            connector: connector,
            clockMs: { clock.now },
            helloSource: { hello }
        )
        queue.sync { transport.eventObserver = { [unowned self] event in self.recorded.append(event) } }
    }

    deinit { transport.stop() }

    func start() { try! transport.start() }

    func run(_ block: () -> Void) { queue.sync(execute: block) }

    var events: [TransportEvent] { queue.sync { recorded } }

    func sight(_ address: String, rssi: Int = -50) {
        run { scanner.onScanEvent?(.sighting(address: address, rssi: rssi)) }
    }

    func emit(_ link: FakeLink, _ event: GattLinkEvent) {
        run { link.onEvent?(event) }
    }

    func incoming(_ address: String, payload: Int = 185) -> FakeLink {
        let link = FakeLink(role: .server, address: address)
        run {
            server.onIncoming?(link)
            link.onEvent?(.ready(payloadMtu: payload))
        }
        return link
    }

    /// Sights a device, lets the transport connect to it and makes the link ready.
    @discardableResult
    func connect(_ address: String, payload: Int = 185) -> FakeLink {
        sight(address)
        let link = connector.links[address]!
        emit(link, .ready(payloadMtu: payload))
        return link
    }

    func advance(_ ms: Int64) {
        clock.advance(ms)
        run { transport.housekeeping() }
    }
}
