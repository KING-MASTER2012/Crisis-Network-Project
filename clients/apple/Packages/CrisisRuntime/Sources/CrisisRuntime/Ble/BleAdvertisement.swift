import Foundation

/// The token an Android device puts into its advertisement (profile 1.1, `docs/src/protocol/ble-link.md`): manufacturer
/// specific data with company identifier `0xFFFF` and a 5-byte payload, big endian:
///
/// ```
/// byte 0      bits 0..3  format version (1); bit 4 "can initiate"; bits 5..7 reserved
/// bytes 1..4  token, an unsigned 32-bit random number
/// ```
///
/// Apple platforms can read it in a scan but **cannot advertise one**: CoreBluetooth advertises the service UUID
/// only. So an iPhone sees the tokens of its neighbours and has none of its own.
public struct BleAdvertisement: Sendable, Equatable {
    public static let companyId: UInt16 = 0xFFFF
    public static let formatVersion: UInt8 = 1
    public static let payloadSize = 5

    public var token: UInt32
    public var canInitiate: Bool

    public init(token: UInt32, canInitiate: Bool) {
        self.token = token
        self.canInitiate = canInitiate
    }

    public func encode() -> Data {
        var out = Data()
        out.append(Self.formatVersion | (canInitiate ? 0x10 : 0))
        var shift = 24
        while shift >= 0 {
            out.append(UInt8(truncatingIfNeeded: token >> UInt32(shift)))
            shift -= 8
        }
        return out
    }

    /// The token in a 5-byte payload, or `nil` when it is missing, too short or of another format version.
    public static func decode(payload: Data?) -> BleAdvertisement? {
        guard let payload, payload.count >= payloadSize else { return nil }
        let bytes = [UInt8](payload)
        guard bytes[0] & 0x0F == formatVersion else { return nil }
        var token: UInt32 = 0
        for index in 1...4 { token = (token << 8) | UInt32(bytes[index]) }
        return BleAdvertisement(token: token, canInitiate: bytes[0] & 0x10 != 0)
    }

    /// The token in the manufacturer data CoreBluetooth reports (`CBAdvertisementDataManufacturerDataKey`): the two
    /// bytes of the company identifier, little endian, then the payload. `nil` for another company.
    public static func decode(manufacturerData: Data?) -> BleAdvertisement? {
        guard let manufacturerData, manufacturerData.count >= 2 + payloadSize else { return nil }
        let bytes = [UInt8](manufacturerData)
        guard UInt16(bytes[0]) | (UInt16(bytes[1]) << 8) == companyId else { return nil }
        return decode(payload: Data(bytes[2...]))
    }
}

/// Which of two neighbours opens the connection (profile 1.1, "Tie-break").
enum TieBreak {
    /// `true` when this device should connect to the neighbour it just saw.
    ///
    /// * No usable token from the neighbour, or its "can initiate" flag is clear: we must (it will not).
    /// * We advertise no token (always the case on Apple platforms): the neighbour cannot compare, so it will connect
    ///   to us. We may therefore **leave the connection to it** (`yieldToInitiatingPeers`), but only when it can
    ///   actually see us, that is while we advertise in the foreground; otherwise we connect too, and the duplicate
    ///   is removed through the HELLO identifiers.
    /// * Both have a token: the smaller one connects. Equal tokens: nobody, until the next rotation.
    static func shouldInitiate(ownToken: UInt32?, peer: BleAdvertisement?, yieldToInitiatingPeers: Bool) -> Bool {
        guard let peer, peer.canInitiate else { return true }
        guard let ownToken else { return !yieldToInitiatingPeers }
        return ownToken < peer.token
    }
}
