#if canImport(CoreBluetooth)
import CoreBluetooth
import Foundation

extension BleRadioState {
    init(_ state: CBManagerState) {
        switch state {
        case .poweredOn: self = .poweredOn
        case .poweredOff: self = .poweredOff
        case .unauthorized: self = .unauthorized
        case .unsupported: self = .unsupported
        case .resetting: self = .resetting
        case .unknown: self = .unknown
        @unknown default: self = .unknown
        }
    }
}

extension UUID {
    var cbUUID: CBUUID { CBUUID(nsuuid: self) }
}

/// The central side over CoreBluetooth: scans for the service and opens ``CoreBluetoothClientLink``s.
///
/// Everything runs on the transport's queue (the `CBCentralManager` is created with it), so the delegate callbacks
/// arrive where the transport wants them. The manager is created in ``start()``, not before: creating it is what
/// makes the system ask for the Bluetooth permission.
///
/// Apple shows no radio address; a peripheral is known by `CBPeripheral.identifier`, a UUID that is stable on this
/// device only. The scan reports every advertisement (`AllowDuplicates`), because a peripheral that was not
/// connected to is otherwise reported once per scan, and the transport has to see it again when a connection ends.
/// That is cheap in the foreground, which is all phase I2 does; I3 revisits it for the background.
final class CoreBluetoothCentral: NSObject, BleScanner, GattConnector, CBCentralManagerDelegate, @unchecked Sendable {
    var onScanEvent: ((ScanEvent) -> Void)?

    fileprivate let queue: DispatchQueue
    private var manager: CBCentralManager?
    private var wantScan = false
    private var scanning = false
    private var helloId: UInt64?
    private var seen: [String: CBPeripheral] = [:]
    private var links: [UUID: CoreBluetoothClientLink] = [:]
    private var closing: [UUID: CBPeripheral] = [:]

    private static let maxRememberedPeripherals = 64

    init(queue: DispatchQueue) {
        self.queue = queue
        super.init()
    }

    // MARK: BleScanner

    func start() {
        wantScan = true
        if manager == nil {
            manager = CBCentralManager(delegate: self, queue: queue, options: [CBCentralManagerOptionShowPowerAlertKey: true])
        } else {
            startScanIfPossible()
        }
    }

    func stop() {
        wantScan = false
        if scanning { manager?.stopScan() }
        scanning = false
        for link in Array(links.values) { link.close() }
        links.removeAll()
    }

    // MARK: GattConnector

    func setHello(_ id: UInt64) {
        helloId = id
    }

    func connect(address: String) -> GattLink? {
        guard let manager, manager.state == .poweredOn, let peripheral = seen[address], links[peripheral.identifier] == nil else {
            return nil
        }
        let link = CoreBluetoothClientLink(central: self, peripheral: peripheral, ownHello: helloId, queue: queue)
        links[peripheral.identifier] = link
        peripheral.delegate = link
        manager.connect(peripheral, options: nil)
        return link
    }

    /// Called by a link that is closed from our side.
    fileprivate func cancel(_ link: CoreBluetoothClientLink) {
        let peripheral = link.peripheral
        links[peripheral.identifier] = nil
        closing[peripheral.identifier] = peripheral
        manager?.cancelPeripheralConnection(peripheral)
    }

    private func startScanIfPossible() {
        guard wantScan, !scanning, let manager, manager.state == .poweredOn else { return }
        manager.scanForPeripherals(
            withServices: [BleProfile.serviceUUID.cbUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
        scanning = true
        onScanEvent?(.started)
    }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let state = BleRadioState(central.state)
        onScanEvent?(.radioState(state))
        if state == .poweredOn {
            startScanIfPossible()
        } else {
            // Bluetooth went away: the system drops every connection without telling each peripheral.
            scanning = false
            let lost = Array(links.values)
            links.removeAll()
            closing.removeAll()
            for link in lost { link.finish(.remote) }
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let address = peripheral.identifier.uuidString
        if seen[address] == nil, seen.count >= Self.maxRememberedPeripherals {
            // Addresses rotate: forget the ones that are not in use.
            seen = seen.filter { entry in links[entry.value.identifier] != nil || closing[entry.value.identifier] != nil }
        }
        seen[address] = peripheral
        onScanEvent?(.sighting(address: address, rssi: RSSI.intValue))
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        links[peripheral.identifier]?.didConnect()
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        closing[peripheral.identifier] = nil
        guard let link = links.removeValue(forKey: peripheral.identifier) else { return }
        link.finish(.connectFailed)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        closing[peripheral.identifier] = nil
        guard let link = links.removeValue(forKey: peripheral.identifier) else { return }
        link.didDisconnect()
    }
}

/// One outgoing GATT connection. It runs the setup in the order of the profile, one GATT operation at a time:
///
/// 1. connect;
/// 2. discover the service and its characteristics: RX (write without response), TX (notify), and HELLO if there is
///    one (an older neighbour has none, which is fine);
/// 3. enable the notifications on TX (CoreBluetooth writes the CCCD);
/// 4. profile 1.1: read the neighbour's HELLO, write ours (best effort, a failure only skips it).
///
/// Only after step 4 is ``GattLinkEvent/ready(payloadMtu:)`` reported. The ATT MTU is exchanged by the system when
/// the connection is made; Apple has no call to ask for a larger one, only
/// `maximumWriteValueLength(for: .withoutResponse)`, which is `ATT MTU - 3`.
///
/// Notifications that arrive during step 4 (the neighbour starts sending as soon as we subscribed) are kept and
/// delivered right after ``GattLinkEvent/ready(payloadMtu:)``.
final class CoreBluetoothClientLink: NSObject, GattLink, CBPeripheralDelegate, @unchecked Sendable {
    let role = GattRole.client
    let address: String
    var onEvent: ((GattLinkEvent) -> Void)?

    fileprivate let peripheral: CBPeripheral
    private weak var central: CoreBluetoothCentral?
    private let queue: DispatchQueue
    private let ownHello: UInt64?

    private enum Step { case connecting, discovering, characteristics, subscribing, hello, ready, closed }

    private var step = Step.connecting
    private var rx: CBCharacteristic?
    private var tx: CBCharacteristic?
    private var hello: CBCharacteristic?
    private var payloadMtu = 0
    private var pending: [Data] = []

    fileprivate init(central: CoreBluetoothCentral, peripheral: CBPeripheral, ownHello: UInt64?, queue: DispatchQueue) {
        self.central = central
        self.peripheral = peripheral
        self.ownHello = ownHello
        self.queue = queue
        address = peripheral.identifier.uuidString
        super.init()
    }

    // MARK: GattLink

    func send(_ bytes: Data) -> SendResult {
        guard step == .ready, let rx, peripheral.state == .connected else { return .failed }
        guard bytes.count <= payloadMtu else { return .failed }
        guard peripheral.canSendWriteWithoutResponse else { return .busy }
        peripheral.writeValue(bytes, for: rx, type: .withoutResponse)
        // The platform gives no completion for a write without response: it is done when it is queued. Posted, not
        // called, because the transport has not yet recorded that a send is in flight.
        queue.async { [weak self] in
            guard let self, self.step == .ready else { return }
            self.onEvent?(.sendCompleted(success: true))
        }
        return .accepted
    }

    func pollMtu() {
        guard step == .ready else { return }
        let current = currentPayload()
        guard current > payloadMtu else { return }
        payloadMtu = current
        onEvent?(.mtuChanged(payloadMtu: current))
    }

    func close() {
        guard step != .closed else { return }
        step = .closed
        onEvent = nil
        pending.removeAll()
        central?.cancel(self)
    }

    // MARK: Called by the central

    fileprivate func didConnect() {
        guard step == .connecting else { return }
        step = .discovering
        peripheral.discoverServices([BleProfile.serviceUUID.cbUUID])
    }

    fileprivate func didDisconnect() {
        finish(step == .ready ? .remote : .connectFailed)
    }

    /// Ends the link and tells the transport, once.
    fileprivate func finish(_ reason: CloseReason) {
        guard step != .closed else { return }
        step = .closed
        pending.removeAll()
        let report = onEvent
        onEvent = nil
        report?(.closed(reason))
    }

    // MARK: Setup

    private func setupFailed() {
        guard step != .closed else { return }
        central?.cancel(self)
        finish(.setupFailed)
    }

    private func currentPayload() -> Int {
        min(peripheral.maximumWriteValueLength(for: .withoutResponse), BleProfile.maxPayload)
    }

    private func becomeReady() {
        step = .ready
        payloadMtu = currentPayload()
        onEvent?(.ready(payloadMtu: payloadMtu))
        let early = pending
        pending.removeAll()
        for packet in early { onEvent?(.received(packet)) }
    }

    private func startHello() {
        guard let hello, ownHello != nil else {
            becomeReady()
            return
        }
        step = .hello
        peripheral.readValue(for: hello)
    }

    // MARK: CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard step == .discovering else { return }
        let service = peripheral.services?.first { $0.uuid == BleProfile.serviceUUID.cbUUID }
        guard error == nil, let service else {
            setupFailed()
            return
        }
        step = .characteristics
        peripheral.discoverCharacteristics(
            [BleProfile.rxUUID.cbUUID, BleProfile.txUUID.cbUUID, BleProfile.helloUUID.cbUUID],
            for: service
        )
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard step == .characteristics else { return }
        let found = service.characteristics ?? []
        let rxCharacteristic = found.first { $0.uuid == BleProfile.rxUUID.cbUUID }
        let txCharacteristic = found.first { $0.uuid == BleProfile.txUUID.cbUUID }
        guard error == nil,
              let rxCharacteristic, rxCharacteristic.properties.contains(.writeWithoutResponse),
              let txCharacteristic, txCharacteristic.properties.contains(.notify)
        else {
            setupFailed()
            return
        }
        rx = rxCharacteristic
        tx = txCharacteristic
        hello = found.first { $0.uuid == BleProfile.helloUUID.cbUUID }
        step = .subscribing
        peripheral.setNotifyValue(true, for: txCharacteristic)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard step == .subscribing, characteristic.uuid == BleProfile.txUUID.cbUUID else { return }
        guard error == nil, characteristic.isNotifying else {
            setupFailed()
            return
        }
        startHello()
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if characteristic.uuid == BleProfile.txUUID.cbUUID {
            guard error == nil, let value = characteristic.value else { return }
            switch step {
            case .ready: onEvent?(.received(value))
            case .hello: pending.append(value)
            default: break
            }
            return
        }
        guard characteristic.uuid == BleProfile.helloUUID.cbUUID, step == .hello else { return }
        if error == nil, let id = HelloValue.decode(characteristic.value) {
            onEvent?(.peerHello(id))
        }
        // Whatever the outcome of the read, offer our own identifier; a failure only skips the exchange.
        guard step == .hello, let own = ownHello, let hello else {
            if step == .hello { becomeReady() }
            return
        }
        peripheral.writeValue(HelloValue.encode(own), for: hello, type: .withResponse)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        // The hello is best effort: whatever the outcome, the link is usable now.
        guard step == .hello, characteristic.uuid == BleProfile.helloUUID.cbUUID else { return }
        becomeReady()
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        guard step == .ready else { return }
        onEvent?(.readyToSend)
    }
}
#endif
