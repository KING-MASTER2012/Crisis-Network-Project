#if canImport(CoreBluetooth)
import CoreBluetooth
import Foundation

/// The peripheral side over CoreBluetooth: hosts the GATT service, advertises it and hands out one
/// ``CoreBluetoothServerLink`` per central that subscribed to TX.
///
/// What Apple does not offer, and what follows from it:
///
/// * **No manufacturer data in the advertisement**, only the service UUID (and a name, which is left out on
///   purpose: it would identify the owner). So an iPhone advertises no token and no "can initiate" flag.
/// * **No connect and disconnect callbacks for centrals.** A central exists for us from its subscription to TX until
///   it unsubscribes, which CoreBluetooth reports when the connection ends.
/// * **No way to drop a central.** ``CoreBluetoothServerLink/close()`` silences the link (nothing more is sent, what
///   arrives is ignored); the central stays connected until it leaves. When the core asks for a disconnect or a
///   duplicate is closed, the other side's own rules end the connection (see ``BleTransport``).
/// * **In the background** the service UUID moves to a special area that only iOS scanners can see: an Android
///   device does not find an iPhone that advertises in the background. The iPhone therefore connects to Android
///   neighbours itself while it is in the background (see ``TieBreak``), and as a peripheral it only serves other
///   iPhones.
/// * **State restoration (iOS):** with a restore identifier the system relaunches the app for a central that wants
///   to talk to it. The restored service and advertisement are reset (removed, then added again): the links are
///   rebuilt by the centrals' next connection.
final class CoreBluetoothPeripheral: NSObject, BleAdvertiser, GattServerPort, CBPeripheralManagerDelegate, @unchecked Sendable {
    var onAdvertiseEvent: ((AdvertiseEvent) -> Void)?
    var onServerEvent: ((ServerEvent) -> Void)?
    var onIncoming: ((GattLink) -> Void)?

    fileprivate let queue: DispatchQueue
    fileprivate private(set) var manager: CBPeripheralManager?
    fileprivate private(set) var txCharacteristic: CBMutableCharacteristic?
    private var wantServer = false
    private var wantAdvertise = false
    private var serviceAdded = false
    private var advertising = false
    private var helloId: UInt64?
    private var links: [UUID: CoreBluetoothServerLink] = [:]
    private let restoreIdentifier: String?
    private var restoredServices = 0

    init(queue: DispatchQueue, restoreIdentifier: String? = nil) {
        self.queue = queue
        self.restoreIdentifier = restoreIdentifier
        super.init()
    }

    // MARK: GattServerPort

    func open() {
        wantServer = true
        ensureManager()
        addServiceIfPossible()
    }

    func close() {
        wantServer = false
        manager?.removeAllServices()
        serviceAdded = false
        txCharacteristic = nil
        links.removeAll()
    }

    func setHello(_ id: UInt64) {
        helloId = id
    }

    // MARK: BleAdvertiser

    func start() {
        wantAdvertise = true
        ensureManager()
        advertiseIfPossible()
    }

    func stop() {
        wantAdvertise = false
        if advertising { manager?.stopAdvertising() }
        advertising = false
    }

    // MARK: Internals

    private func ensureManager() {
        guard manager == nil else { return }
        var options: [String: Any] = [:]
        #if os(iOS)
        if let restoreIdentifier { options[CBPeripheralManagerOptionRestoreIdentifierKey] = restoreIdentifier }
        #endif
        manager = CBPeripheralManager(delegate: self, queue: queue, options: options.isEmpty ? nil : options)
    }

    private func addServiceIfPossible() {
        guard wantServer, !serviceAdded, let manager, manager.state == .poweredOn else { return }
        let rx = CBMutableCharacteristic(
            type: BleProfile.rxUUID.cbUUID,
            properties: [.writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        let tx = CBMutableCharacteristic(
            type: BleProfile.txUUID.cbUUID,
            properties: [.notify],
            value: nil,
            permissions: [.readable]
        )
        // A nil value makes the characteristic dynamic: reads and writes reach the delegate.
        let hello = CBMutableCharacteristic(
            type: BleProfile.helloUUID.cbUUID,
            properties: [.read, .write],
            value: nil,
            permissions: [.readable, .writeable]
        )
        let service = CBMutableService(type: BleProfile.serviceUUID.cbUUID, primary: true)
        service.characteristics = [rx, tx, hello]
        txCharacteristic = tx
        manager.add(service)
    }

    private func advertiseIfPossible() {
        guard wantAdvertise, !advertising, let manager, manager.state == .poweredOn else { return }
        // Wait for the service, so that a central that connects at once finds it.
        guard serviceAdded || !wantServer else { return }
        manager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [BleProfile.serviceUUID.cbUUID]])
    }

    fileprivate func forget(_ link: CoreBluetoothServerLink) {
        if links[link.centralId] === link { links[link.centralId] = nil }
    }

    // MARK: CBPeripheralManagerDelegate

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        let state = BleRadioState(peripheral.state)
        onAdvertiseEvent?(.radioState(state))
        if state == .poweredOn {
            if restoredServices > 0 {
                // What the system kept is of no use: the characteristics are not ours any more.
                peripheral.stopAdvertising()
                peripheral.removeAllServices()
                onAdvertiseEvent?(.restored(count: restoredServices))
                restoredServices = 0
                serviceAdded = false
                advertising = false
            }
            addServiceIfPossible()
        } else {
            // Services and advertising are gone, and so are the centrals.
            serviceAdded = false
            advertising = false
            let lost = Array(links.values)
            links.removeAll()
            for link in lost { link.finish(.remote) }
        }
    }

    #if os(iOS)
    /// The system relaunched the app and hands back what it kept. Called before the state update.
    func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        let services = dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService] ?? []
        let advertising = dict[CBPeripheralManagerRestoredStateAdvertisementDataKey] != nil
        restoredServices = max(services.count, advertising ? 1 : 0)
    }
    #endif

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error {
            onServerEvent?(.failed(code: (error as NSError).code))
            return
        }
        serviceAdded = true
        onServerEvent?(.ready)
        advertiseIfPossible()
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error {
            advertising = false
            onAdvertiseEvent?(.failed(code: (error as NSError).code))
        } else {
            advertising = true
            onAdvertiseEvent?(.started)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == BleProfile.txUUID.cbUUID, links[central.identifier] == nil else { return }
        let link = CoreBluetoothServerLink(owner: self, central: central, queue: queue)
        links[central.identifier] = link
        onIncoming?(link)
        link.becomeReady()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == BleProfile.txUUID.cbUUID, let link = links.removeValue(forKey: central.identifier) else { return }
        link.finish(.remote)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard request.characteristic.uuid == BleProfile.helloUUID.cbUUID, let id = helloId else {
            peripheral.respond(to: request, withResult: .requestNotSupported)
            return
        }
        let value = HelloValue.encode(id)
        guard request.offset <= value.count else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = value.subdata(in: request.offset..<value.count)
        peripheral.respond(to: request, withResult: .success)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        var result = CBATTError.Code.success
        for request in requests {
            let link = links[request.central.identifier]
            let value = request.value ?? Data()
            if request.characteristic.uuid == BleProfile.rxUUID.cbUUID {
                link?.receive(value)
            } else if request.characteristic.uuid == BleProfile.helloUUID.cbUUID, let id = HelloValue.decode(value) {
                link?.receiveHello(id)
            } else {
                result = .requestNotSupported
            }
        }
        // One response answers the whole batch; it is ignored for writes without response.
        if let first = requests.first { peripheral.respond(to: first, withResult: result) }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        for link in Array(links.values) { link.platformReady() }
    }
}

/// A central that subscribed to TX: we notify it and receive its writes.
///
/// The link is ready the moment the central subscribed (profile: "the peripheral considers the link ready when the
/// central subscribed"); the central's HELLO write arrives afterwards and is reported as ``GattLinkEvent/peerHello(_:)``.
final class CoreBluetoothServerLink: NSObject, GattLink, @unchecked Sendable {
    let role = GattRole.server
    let address: String
    var onEvent: ((GattLinkEvent) -> Void)?

    fileprivate let centralId: UUID
    private weak var owner: CoreBluetoothPeripheral?
    private let central: CBCentral
    private let queue: DispatchQueue
    private var closed = false
    private var payloadMtu = 0

    fileprivate init(owner: CoreBluetoothPeripheral, central: CBCentral, queue: DispatchQueue) {
        self.owner = owner
        self.central = central
        self.queue = queue
        centralId = central.identifier
        address = central.identifier.uuidString
        super.init()
    }

    // MARK: GattLink

    func send(_ bytes: Data) -> SendResult {
        guard !closed, let owner, let manager = owner.manager, let tx = owner.txCharacteristic else { return .failed }
        guard bytes.count <= central.maximumUpdateValueLength else { return .failed }
        guard manager.updateValue(bytes, for: tx, onSubscribedCentrals: [central]) else {
            // The platform's queue is full; `peripheralManagerIsReady(toUpdateSubscribers:)` says when to go on.
            return .busy
        }
        queue.async { [weak self] in
            guard let self, !self.closed else { return }
            self.onEvent?(.sendCompleted(success: true))
        }
        return .accepted
    }

    func pollMtu() {
        guard !closed else { return }
        let current = min(central.maximumUpdateValueLength, BleProfile.maxPayload)
        guard current > payloadMtu else { return }
        payloadMtu = current
        onEvent?(.mtuChanged(payloadMtu: current))
    }

    /// A peripheral cannot disconnect a central. The link goes quiet; the central stays until it leaves.
    func close() {
        guard !closed else { return }
        closed = true
        onEvent = nil
        owner?.forget(self)
    }

    // MARK: Called by the peripheral

    fileprivate func becomeReady() {
        guard !closed else { return }
        payloadMtu = min(central.maximumUpdateValueLength, BleProfile.maxPayload)
        onEvent?(.ready(payloadMtu: payloadMtu))
    }

    fileprivate func receive(_ bytes: Data) {
        guard !closed, !bytes.isEmpty else { return }
        onEvent?(.received(bytes))
    }

    fileprivate func receiveHello(_ id: UInt64) {
        guard !closed else { return }
        onEvent?(.peerHello(id))
    }

    fileprivate func platformReady() {
        guard !closed else { return }
        onEvent?(.readyToSend)
    }

    /// Ends the link and tells the transport, once.
    fileprivate func finish(_ reason: CloseReason) {
        guard !closed else { return }
        closed = true
        let report = onEvent
        onEvent = nil
        report?(.closed(reason))
    }
}
#endif
