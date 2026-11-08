import Foundation

/*
 * The ports of the BLE transport. Everything that touches CoreBluetooth sits behind these protocols, so that
 * ``BleTransport`` (the state machine) can be tested on the Mac with fakes. The implementations over CoreBluetooth
 * live in `CoreBluetoothCentral.swift` and `CoreBluetoothPeripheral.swift`.
 *
 * Contract for implementations:
 *
 * * Every callback (`onScanEvent`, `onIncoming`, `GattLink.onEvent`, ...) is called **on the transport's queue**, the
 *   serial queue the transport and the ports are created with, and never re-entrantly from inside a call of the
 *   transport into the port (a completion that is ready at once is posted with `queue.async`).
 * * The transport calls the ports on that same queue; methods return quickly.
 * * `stop()`, `close()` and `GattLink.close()` are idempotent and may be called on something never started.
 */

/// The state of a Bluetooth manager, as far as the transport cares.
public enum BleRadioState: Sendable, Equatable {
    case unknown
    case resetting
    case unsupported
    case unauthorized
    case poweredOff
    case poweredOn
}

enum ScanEvent: Equatable {
    case started

    /// A device advertising the service was seen. May repeat for the same device. The address is the identifier
    /// CoreBluetooth gave the peripheral (a UUID that is stable on this device only; Apple never shows the radio
    /// address). `advertisement` is the token of a profile 1.1 neighbour, `nil` when it advertises none.
    case sighting(address: String, rssi: Int, advertisement: BleAdvertisement?)

    case radioState(BleRadioState)
    case failed(code: Int)

    /// The system relaunched the app for this manager and handed back its state (iOS state restoration).
    case restored(count: Int)
}

protocol BleScanner: AnyObject {
    var onScanEvent: ((ScanEvent) -> Void)? { get set }

    /// Starts a scan filtered on the service UUID as soon as Bluetooth is on (immediately when it is). Called again
    /// with another `allowDuplicates` it restarts the scan. Links are not touched.
    func start(allowDuplicates: Bool)

    /// Stops scanning only; the links that are open stay open (the transport pauses the scan between duty cycles).
    func stop()
}

enum AdvertiseEvent: Equatable {
    case started
    case radioState(BleRadioState)
    case failed(code: Int)

    /// The system relaunched the app for the peripheral manager (iOS state restoration).
    case restored(count: Int)
}

protocol BleAdvertiser: AnyObject {
    var onAdvertiseEvent: ((AdvertiseEvent) -> Void)? { get set }

    /// Advertises the service (connectable, the service UUID only, no name) as soon as it can.
    func start()
    func stop()
}

enum ServerEvent: Equatable {
    /// The service is registered and can be used.
    case ready
    case failed(code: Int)
}

/// The peripheral side: hosts the GATT service and hands out one ``GattLink`` per central that subscribed.
protocol GattServerPort: AnyObject {
    /// A central subscribed to the TX characteristic. The link reports ``GattLinkEvent/ready(payloadMtu:)`` right
    /// after this call returns.
    var onIncoming: ((GattLink) -> Void)? { get set }
    var onServerEvent: ((ServerEvent) -> Void)? { get set }

    /// Registers the service as soon as Bluetooth is on.
    func open()

    /// Removes the service and forgets every link it handed out.
    func close()

    /// The identifier the server shows in the HELLO characteristic from now on.
    func setHello(_ id: UInt64)
}

/// The central side: opens outgoing connections.
protocol GattConnector: AnyObject {
    /// The identifier written to the HELLO characteristic of every link opened from now on.
    func setHello(_ id: UInt64)

    /// Starts connecting to the device with this address (one that was in a sighting) and returns the link at once;
    /// the outcome arrives as events (ready, or closed when it failed). `nil` when the device is unknown or
    /// Bluetooth is not on.
    func connect(address: String) -> GattLink?
}

public enum GattRole: String, Sendable {
    /// We connected to the remote device (we write and receive notifications).
    case client

    /// The remote device connected to us (we notify and receive writes).
    case server
}

/// Why a link ended.
enum CloseReason: Equatable {
    /// ``GattLink/close()`` was called.
    case local

    /// The remote device disconnected, went out of range or Bluetooth was switched off.
    case remote

    /// The connection could not be established.
    case connectFailed

    /// The remote device does not offer the service, or something in the setup went wrong.
    case setupFailed
}

enum GattLinkEvent: Equatable {
    /// The link is usable: the service was found, the notifications are on (client) or the remote device subscribed
    /// (server), and the HELLO exchange is done or was skipped. `payloadMtu` is `min(ATT MTU - 3, 512)` at that
    /// moment (the platform tells the payload size, not the ATT MTU).
    case ready(payloadMtu: Int)

    /// The identifier of the device on the other side. At most once, only when it speaks profile 1.1.
    case peerHello(UInt64)

    /// The payload size changed after the link became ready.
    case mtuChanged(payloadMtu: Int)

    /// One packet arrived.
    case received(Data)

    /// The send that ``GattLink/send(_:)`` accepted is finished.
    case sendCompleted(success: Bool)

    /// The platform can take packets again after ``SendResult/busy``.
    case readyToSend

    /// The link is gone. Reported once; no event follows it.
    case closed(CloseReason)
}

enum SendResult: Equatable {
    /// The platform took the packet; ``GattLinkEvent/sendCompleted(success:)`` follows.
    case accepted

    /// The platform cannot take it now (its queue is full). ``GattLinkEvent/readyToSend`` follows; until then the
    /// transport asks again at every tick.
    case busy

    /// The link is not usable (not ready, closed, packet too large for it).
    case failed
}

/// One GATT connection, in either role. A link carries one core packet per write or notification; there is no
/// framing on top, because the core already fits every packet to the MTU. Only one send is in progress at a time.
protocol GattLink: AnyObject {
    var role: GattRole { get }
    var address: String { get }
    var onEvent: ((GattLinkEvent) -> Void)? { get set }

    func send(_ bytes: Data) -> SendResult

    /// Asks the platform for the current payload size and reports ``GattLinkEvent/mtuChanged(payloadMtu:)`` when it
    /// is larger than the last one (Apple has no callback for it).
    func pollMtu()

    /// Disconnects (a central) or stops serving (a peripheral cannot drop a central: CoreBluetooth has no call for
    /// it) and releases the link. After it no event is delivered any more.
    func close()
}
