import XCTest
@testable import CrisisRuntime

/// Phase I3 on the Mac: the advertisement token, the tie-break, the scan duty cycle, the power profiles, link rotation
/// and the HELLO rotation, all against fake radios and a manual clock.
final class BleDiscoveryTests: XCTestCase {
    // MARK: Advertisement

    func testTheTokenIsOneFlagByteAndABigEndianNumber() {
        let advertisement = BleAdvertisement(token: 0x0102_0304, canInitiate: true)
        XCTAssertEqual([UInt8](advertisement.encode()), [0x11, 1, 2, 3, 4])
        XCTAssertEqual(BleAdvertisement.decode(payload: advertisement.encode()), advertisement)
        XCTAssertEqual([UInt8](BleAdvertisement(token: 7, canInitiate: false).encode()), [0x01, 0, 0, 0, 7])
        XCTAssertEqual(BleAdvertisement.decode(payload: Data([0x01, 0xFF, 0xFF, 0xFF, 0xFF]))?.token, UInt32.max)
    }

    func testAReservedBitIsIgnoredAndAnotherVersionOrAShortPayloadIsNoToken() {
        XCTAssertEqual(BleAdvertisement.decode(payload: Data([0xF1, 0, 0, 0, 1])), BleAdvertisement(token: 1, canInitiate: true))
        XCTAssertNil(BleAdvertisement.decode(payload: Data([0x02, 0, 0, 0, 1])))
        XCTAssertNil(BleAdvertisement.decode(payload: Data([0x01, 0, 0, 1])))
        XCTAssertNil(BleAdvertisement.decode(payload: nil))
    }

    func testTheManufacturerDataOfCoreBluetoothStartsWithTheLittleEndianCompanyId() {
        XCTAssertEqual(
            BleAdvertisement.decode(manufacturerData: Data([0xFF, 0xFF, 0x11, 0, 0, 1, 0])),
            BleAdvertisement(token: 256, canInitiate: true)
        )
        XCTAssertNil(BleAdvertisement.decode(manufacturerData: Data([0x4C, 0x00, 0x11, 0, 0, 1, 0])), "another company")
        XCTAssertNil(BleAdvertisement.decode(manufacturerData: Data([0xFF, 0xFF, 0x11])))
        XCTAssertNil(BleAdvertisement.decode(manufacturerData: nil))
    }

    // MARK: Tie-break

    func testWithoutAUsableTokenFromTheNeighbourWeConnect() {
        XCTAssertTrue(TieBreak.shouldInitiate(ownToken: nil, peer: nil, yieldToInitiatingPeers: true))
        XCTAssertTrue(TieBreak.shouldInitiate(ownToken: 5, peer: nil, yieldToInitiatingPeers: true))
        XCTAssertTrue(TieBreak.shouldInitiate(ownToken: nil, peer: BleAdvertisement(token: 1, canInitiate: false), yieldToInitiatingPeers: true))
    }

    func testWithoutATokenOfOurOwnWeLeaveTheConnectionToAnInitiatingNeighbourOnlyWhenAsked() {
        let peer = BleAdvertisement(token: 1, canInitiate: true)
        XCTAssertFalse(TieBreak.shouldInitiate(ownToken: nil, peer: peer, yieldToInitiatingPeers: true))
        XCTAssertTrue(TieBreak.shouldInitiate(ownToken: nil, peer: peer, yieldToInitiatingPeers: false))
    }

    func testWithTwoTokensTheSmallerOneConnects() {
        let peer = BleAdvertisement(token: 100, canInitiate: true)
        XCTAssertTrue(TieBreak.shouldInitiate(ownToken: 99, peer: peer, yieldToInitiatingPeers: false))
        XCTAssertFalse(TieBreak.shouldInitiate(ownToken: 101, peer: peer, yieldToInitiatingPeers: false))
        XCTAssertFalse(TieBreak.shouldInitiate(ownToken: 100, peer: peer, yieldToInitiatingPeers: false))
    }

    private let initiator = BleAdvertisement(token: 42, canInitiate: true)

    func testAnInitiatingNeighbourIsLeftToConnectToUsWhileWeAdvertiseInTheForeground() {
        let rig = BleRig(role: .both)
        rig.start()
        rig.sight("A", advertisement: initiator)
        XCTAssertEqual(rig.connector.connected, [])
        XCTAssertEqual(rig.transport.stats().heldByTieBreak, 1)
    }

    func testAHeldBackNeighbourIsConnectedToAfterTheFallbackTime() {
        let rig = BleRig(role: .both)
        rig.start()
        rig.sight("A", advertisement: initiator)
        rig.advance(15_000)
        rig.sight("A", advertisement: initiator)
        rig.advance(14_999)
        XCTAssertEqual(rig.connector.connected, [])
        rig.advance(2)
        rig.sight("A", advertisement: initiator)
        XCTAssertEqual(rig.connector.connected, ["A"])
    }

    func testTheFallbackDoesNotApplyWhileThereIsAnotherLink() {
        let rig = BleRig(role: .both)
        rig.start()
        _ = rig.incoming("C")
        rig.sight("A", advertisement: initiator)
        rig.advance(15_000)
        rig.sight("A", advertisement: initiator)
        rig.advance(15_001)
        rig.sight("A", advertisement: initiator)
        XCTAssertEqual(rig.connector.connected, [])
    }

    func testACentralOnlyDeviceConnectsAtOnceBecauseNobodyCanConnectToIt() {
        let rig = BleRig(role: .central)
        rig.start()
        rig.sight("A", advertisement: initiator)
        XCTAssertEqual(rig.connector.connected, ["A"])
    }

    func testInTheBackgroundWeConnectToEveryoneBecauseAnAndroidScanCannotSeeUs() {
        let rig = BleRig(role: .both)
        rig.start()
        rig.sight("A", advertisement: initiator)
        XCTAssertEqual(rig.connector.connected, [])
        rig.transport.setAppForeground(false)
        rig.run {}
        XCTAssertEqual(rig.connector.connected, ["A"])
    }

    func testANeighbourThatCannotInitiateIsConnectedToAtOnce() {
        let rig = BleRig(role: .both)
        rig.start()
        rig.sight("A", advertisement: BleAdvertisement(token: 1, canInitiate: false))
        XCTAssertEqual(rig.connector.connected, ["A"])
    }

    func testYieldingCanBeSwitchedOff() {
        let rig = BleRig(role: .both) { $0.yieldToInitiators = false }
        rig.start()
        rig.sight("A", advertisement: initiator)
        XCTAssertEqual(rig.connector.connected, ["A"])
    }

    // MARK: Scan duty cycle

    func testTheBalancedProfileScansFiveSecondsInFifteen() {
        let rig = BleRig(role: .central)
        rig.start()
        XCTAssertEqual(rig.scanner.starts, [false])
        rig.advance(4_000)
        XCTAssertEqual(rig.scanner.stopCount, 0)
        rig.advance(1_001)
        XCTAssertEqual(rig.scanner.stopCount, 1, "the pause starts after 5 s")
        rig.advance(9_000)
        XCTAssertEqual(rig.scanner.startCount, 1)
        rig.advance(1_001)
        XCTAssertEqual(rig.scanner.startCount, 2, "the next window starts after 15 s")
    }

    func testTheAggressiveProfileScansWithoutAPauseAndSeesDuplicates() {
        let rig = BleRig(role: .central) { $0.profile = .aggressive }
        rig.start()
        rig.advance(60_000)
        XCTAssertEqual(rig.scanner.starts, [true])
        XCTAssertEqual(rig.scanner.stopCount, 0)
    }

    func testInTheBackgroundTheScanStaysOnAndDoesNotAskForDuplicates() {
        let rig = BleRig(role: .central) { $0.profile = .aggressive }
        rig.start()
        rig.transport.setAppForeground(false)
        rig.run {}
        XCTAssertEqual(rig.scanner.starts, [true, false], "the scan restarts without duplicates")
        rig.advance(60_000)
        XCTAssertEqual(rig.scanner.stopCount, 0)
    }

    func testChangingTheProfileRestartsTheCycle() {
        let rig = BleRig(role: .central)
        rig.start()
        rig.advance(6_000)
        XCTAssertEqual(rig.scanner.stopCount, 1)
        rig.transport.setPowerProfile(.aggressive)
        rig.run {}
        XCTAssertEqual(rig.scanner.starts, [false, true])
        rig.advance(30_000)
        XCTAssertEqual(rig.scanner.stopCount, 1)
    }

    func testANodeThatHasAllItsLinksScansLikeTheSaver() {
        let rig = BleRig(role: .central) {
            $0.profile = .aggressive
            $0.maxLinks = 2
        }
        rig.start()
        rig.connect("A")
        rig.connect("B")
        rig.advance(5_000)
        XCTAssertEqual(rig.scanner.stopCount, 1, "2 s in 20 s instead of continuous")
    }

    // MARK: Power profile limits

    func testTheProfileLimitsTheNumberOfLinks() {
        let rig = BleRig(role: .central) { $0.profile = .saver }
        rig.start()
        rig.connect("A")
        rig.connect("B")
        rig.sight("C")
        rig.advance(1)
        XCTAssertEqual(rig.connector.connected, ["A", "B"], "3 links, one slot kept for incoming")
        rig.transport.setPowerProfile(.aggressive)
        rig.run {}
        XCTAssertEqual(rig.connector.connected, ["A", "B", "C"])
    }

    // MARK: Rotation

    func testASaturatedNodeSwapsItsOldestOutgoingLinkForAWaitingNeighbour() {
        let rig = BleRig(role: .central) { $0.maxLinks = 3 }
        rig.start()
        let oldest = rig.connect("A")
        rig.advance(1_000)
        rig.connect("B")
        rig.advance(598_000)
        rig.sight("C")
        XCTAssertEqual(rig.connector.connected, ["A", "B"])
        rig.advance(1_100)
        XCTAssertEqual(oldest.closeCount, 1)
        XCTAssertEqual(rig.connector.connected, ["A", "B", "C"])
        XCTAssertEqual(rig.events.filter { $0 == .peerLost(peer: 1) }.count, 1)
        XCTAssertEqual(rig.transport.stats().linkRotations, 1)
    }

    func testNothingIsRotatedWithoutAWaitingNeighbour() {
        let rig = BleRig(role: .central) { $0.maxLinks = 3 }
        rig.start()
        let first = rig.connect("A")
        rig.connect("B")
        rig.advance(601_000)
        XCTAssertEqual(first.closeCount, 0)
    }

    // MARK: HELLO rotation

    func testTheHelloIdentifierIsReplacedNowAndThenAndHandedToThePorts() {
        let rig = BleRig(role: .both, hello: 100, rotatingHello: true)
        rig.start()
        XCTAssertEqual(rig.connector.hello, 100)
        rig.advance(599_000)
        XCTAssertEqual(rig.connector.hello, 100)
        rig.advance(2_000)
        XCTAssertEqual(rig.connector.hello, 101)
        XCTAssertEqual(rig.server.hello, 101)
        XCTAssertTrue(rig.diagnostics.contains(.helloRotated))
    }

    // MARK: Restoration

    func testARestoredManagerIsReportedInTheDiagnostics() {
        let rig = BleRig(role: .both)
        rig.start()
        rig.run {
            rig.scanner.onScanEvent?(.restored(count: 2))
            rig.advertiser.onAdvertiseEvent?(.restored(count: 1))
        }
        XCTAssertTrue(rig.diagnostics.contains(.restored(side: "central", count: 2)))
        XCTAssertTrue(rig.diagnostics.contains(.restored(side: "peripheral", count: 1)))
    }
}
