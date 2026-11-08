import Foundation

/// The GATT profile of the Crisis Network link (see `docs/src/protocol/ble-link.md`). Every device runs both sides of
/// it: it advertises the service and serves it (peripheral), and it scans for the service and connects to it
/// (central).
///
/// These values are part of the radio protocol and are the ones of the Android client (`BleUuids.kt`): changing one
/// splits the network.
public enum BleProfile {
    /// The service every node advertises.
    public static let serviceUUID = UUID(uuidString: "6F1D5A30-7C2E-4B8A-9D41-C3B5E0A7F2D6")!

    /// Central -> peripheral. Write without response; one core packet per write.
    public static let rxUUID = UUID(uuidString: "6F1D5A31-7C2E-4B8A-9D41-C3B5E0A7F2D6")!

    /// Peripheral -> central. Notifications; one core packet per notification. (The CCCD, 0x2902, is added by
    /// CoreBluetooth itself for a characteristic with the notify property.)
    public static let txUUID = UUID(uuidString: "6F1D5A32-7C2E-4B8A-9D41-C3B5E0A7F2D6")!

    /// HELLO (profile 1.1): read and write. A device shows its current identifier here and the connecting device
    /// writes its own, so that both ends of a link know who is on the other side. See ``HelloValue``.
    public static let helloUUID = UUID(uuidString: "6F1D5A33-7C2E-4B8A-9D41-C3B5E0A7F2D6")!

    /// ATT bytes that surround a value in a write or a notification (opcode + handle).
    public static let attOverhead = 3

    /// The largest attribute value ATT allows, and so the largest packet a link carries.
    public static let maxPayload = 512

    /// The packet size a link with this ATT MTU carries: `min(ATT MTU - 3, 512)`.
    public static func payload(forAttMtu attMtu: Int) -> Int {
        min(max(attMtu - attOverhead, 0), maxPayload)
    }
}

/// The value of the HELLO characteristic (profile 1.1): one version byte, then a 64-bit identifier in big-endian
/// order. Nine bytes; a longer value from a later version is read by its first nine bytes.
///
/// The identifier is random and only visible to a device that connected, never in an advertisement. Two devices that
/// connected to each other, one in each direction, use it to notice that both links lead to the same neighbour and
/// keep only one of them. Identifiers are compared as unsigned numbers, so that every platform orders them alike.
public enum HelloValue {
    public static let version: UInt8 = 1
    public static let size = 9

    public static func encode(_ id: UInt64) -> Data {
        var out = Data()
        out.reserveCapacity(size)
        out.append(version)
        var shift = 56
        while shift >= 0 {
            out.append(UInt8(truncatingIfNeeded: id >> UInt64(shift)))
            shift -= 8
        }
        return out
    }

    /// The identifier in `data`, or `nil` when the value is missing, too short or of another version.
    public static func decode(_ data: Data?) -> UInt64? {
        guard let data, data.count >= size else { return nil }
        let bytes = [UInt8](data)
        guard bytes[0] == version else { return nil }
        var id: UInt64 = 0
        for index in 1...8 { id = (id << 8) | UInt64(bytes[index]) }
        return id
    }
}
