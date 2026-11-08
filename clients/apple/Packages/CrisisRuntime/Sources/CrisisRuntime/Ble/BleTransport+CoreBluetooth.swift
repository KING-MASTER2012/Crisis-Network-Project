#if canImport(CoreBluetooth)
import Foundation

extension BleTransport {
    /// A transport over CoreBluetooth, with the sides the role asks for. Nothing happens, and no permission is asked
    /// for, until ``start()``.
    ///
    /// - Parameter config: set `minLinkMtu` to `coreInfo().minLinkMtu`.
    public static func coreBluetooth(config: BleConfig = BleConfig()) -> BleTransport {
        // One queue for the transport and both managers: every callback arrives where the state lives.
        let queue = DispatchQueue(label: "org.crisisnetwork.ble", qos: .userInitiated)
        let central = config.role.runsCentral ? CoreBluetoothCentral(queue: queue) : nil
        let peripheral = config.role.runsPeripheral ? CoreBluetoothPeripheral(queue: queue) : nil
        return BleTransport(
            config: config,
            queue: queue,
            scanner: central,
            advertiser: peripheral,
            server: peripheral,
            connector: central,
            clockMs: { BleTransport.monotonicMs() },
            helloSource: { UInt64.random(in: UInt64.min...UInt64.max) }
        )
    }
}
#endif
