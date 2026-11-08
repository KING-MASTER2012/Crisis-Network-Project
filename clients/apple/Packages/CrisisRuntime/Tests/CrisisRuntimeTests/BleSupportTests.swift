import XCTest
@testable import CrisisRuntime

final class BleSupportTests: XCTestCase {
    func testTheProfileUsesTheUuidsOfTheAndroidClient() {
        XCTAssertEqual(BleProfile.serviceUUID.uuidString.lowercased(), "6f1d5a30-7c2e-4b8a-9d41-c3b5e0a7f2d6")
        XCTAssertEqual(BleProfile.rxUUID.uuidString.lowercased(), "6f1d5a31-7c2e-4b8a-9d41-c3b5e0a7f2d6")
        XCTAssertEqual(BleProfile.txUUID.uuidString.lowercased(), "6f1d5a32-7c2e-4b8a-9d41-c3b5e0a7f2d6")
        XCTAssertEqual(BleProfile.helloUUID.uuidString.lowercased(), "6f1d5a33-7c2e-4b8a-9d41-c3b5e0a7f2d6")
    }

    func testThePayloadIsTheAttMtuMinusThreeAndAtMost512() {
        XCTAssertEqual(BleProfile.payload(forAttMtu: 23), 20)
        XCTAssertEqual(BleProfile.payload(forAttMtu: 185), 182)
        XCTAssertEqual(BleProfile.payload(forAttMtu: 517), 512)
        XCTAssertEqual(BleProfile.payload(forAttMtu: 1_000), 512)
        XCTAssertEqual(BleProfile.payload(forAttMtu: 1), 0)
    }

    func testHelloIsOneVersionByteAndABigEndianIdentifier() {
        let value = HelloValue.encode(0x0102_0304_0506_0708)
        XCTAssertEqual([UInt8](value), [1, 1, 2, 3, 4, 5, 6, 7, 8])
        XCTAssertEqual(HelloValue.decode(value), 0x0102_0304_0506_0708)
        XCTAssertEqual(HelloValue.decode(HelloValue.encode(UInt64.max)), UInt64.max)
    }

    func testAHelloOfAnotherVersionOrTooShortIsIgnored() {
        XCTAssertNil(HelloValue.decode(nil))
        XCTAssertNil(HelloValue.decode(Data([1, 2, 3])))
        XCTAssertNil(HelloValue.decode(Data([2, 0, 0, 0, 0, 0, 0, 0, 1])))
    }

    func testAHelloFromALaterVersionIsReadByItsFirstNineBytes() {
        var value = HelloValue.encode(7)
        value.append(contentsOf: [9, 9])
        XCTAssertEqual(HelloValue.decode(value), 7)
    }

    func testAHelloInASliceOfDataIsDecoded() {
        let whole = Data([0xFF]) + HelloValue.encode(42)
        let slice = whole.dropFirst()
        XCTAssertEqual(HelloValue.decode(Data(slice)), 42)
        XCTAssertEqual(HelloValue.decode(slice), 42)
    }

    func testHandlesAreNeverReused() {
        var handles = HandleAllocator()
        XCTAssertEqual(handles.next(), 1)
        XCTAssertEqual(handles.next(), 2)
        XCTAssertEqual(handles.next(), 3)
    }

    func testTheSendQueueKeepsOrderAndRefusesWhatDoesNotFit() {
        var queue = SendQueue(maxBytes: 10)
        XCTAssertTrue(queue.offer(Data([1, 2, 3, 4])))
        XCTAssertTrue(queue.offer(Data([5, 6, 7, 8, 9, 10])))
        XCTAssertFalse(queue.offer(Data([11])), "the queue is full")
        XCTAssertEqual(queue.bytes, 10)
        XCTAssertEqual(queue.peek(), Data([1, 2, 3, 4]))
        XCTAssertEqual(queue.removeFirst(), Data([1, 2, 3, 4]))
        XCTAssertEqual(queue.bytes, 6)
        XCTAssertTrue(queue.offer(Data([11, 12, 13, 14])))
        queue.clear()
        XCTAssertTrue(queue.isEmpty)
        XCTAssertEqual(queue.bytes, 0)
        XCTAssertNil(queue.removeFirst())
    }

    func testTheRolesRunTheRightSides() {
        XCTAssertTrue(BleRole.central.runsCentral)
        XCTAssertFalse(BleRole.central.runsPeripheral)
        XCTAssertFalse(BleRole.peripheral.runsCentral)
        XCTAssertTrue(BleRole.peripheral.runsPeripheral)
        XCTAssertTrue(BleRole.both.runsCentral && BleRole.both.runsPeripheral)
    }
}
