import XCTest
@testable import CrisisRuntime

/// The state machine of the Bluetooth transport against fake radios: no CoreBluetooth, no permission, no timing.
final class BleTransportTests: XCTestCase {
    // MARK: Roles and start-up

    func testBothSidesStartAndTheOwnIdentifierIsHandedToThePorts() {
        let rig = BleRig(role: .both, hello: 0xABCD)
        rig.start()
        XCTAssertEqual(rig.scanner.startCount, 1)
        XCTAssertEqual(rig.advertiser.startCount, 1)
        XCTAssertEqual(rig.server.openCount, 1)
        XCTAssertEqual(rig.connector.hello, 0xABCD)
        XCTAssertEqual(rig.server.hello, 0xABCD)
    }

    func testACentralOnlyScans() {
        let rig = BleRig(role: .central)
        rig.start()
        XCTAssertEqual(rig.scanner.startCount, 1)
        XCTAssertEqual(rig.advertiser.startCount, 0)
        XCTAssertEqual(rig.server.openCount, 0)
    }

    func testAPeripheralOnlyAdvertisesAndNeverConnects() {
        let rig = BleRig(role: .peripheral)
        rig.start()
        XCTAssertEqual(rig.scanner.startCount, 0)
        XCTAssertEqual(rig.advertiser.startCount, 1)
        XCTAssertEqual(rig.server.openCount, 1)
        rig.sight("A")
        XCTAssertEqual(rig.connector.connected, [])
    }

    func testStartingTwiceFails() {
        let rig = BleRig()
        rig.start()
        XCTAssertThrowsError(try rig.transport.start())
    }

    func testTheRadioStatesShowInTheStats() {
        let rig = BleRig()
        rig.start()
        rig.run {
            rig.scanner.onScanEvent?(.radioState(.poweredOn))
            rig.scanner.onScanEvent?(.started)
            rig.advertiser.onAdvertiseEvent?(.radioState(.unauthorized))
            rig.server.onServerEvent?(.ready)
        }
        let stats = rig.transport.stats()
        XCTAssertEqual(stats.centralState, .poweredOn)
        XCTAssertTrue(stats.scanning)
        XCTAssertEqual(stats.peripheralState, .unauthorized)
        XCTAssertFalse(stats.advertising)
        XCTAssertTrue(stats.serverReady)
    }

    // MARK: Connecting as a central

    func testASightingStartsAConnectionAndAReadyLinkIsReportedToTheCore() {
        let rig = BleRig(role: .central)
        rig.start()
        rig.sight("A")
        XCTAssertEqual(rig.connector.connected, ["A"])
        let link = rig.connector.links["A"]!
        XCTAssertEqual(rig.events, [], "nothing is reported before the link is ready")
        rig.emit(link, .ready(payloadMtu: 182))
        XCTAssertEqual(rig.events, [.peerSeen(peer: 1, linkMtu: 182)])
    }

    func testADeviceThatIsAlreadyLinkedIsNotConnectedToAgain() {
        let rig = BleRig(role: .central)
        rig.start()
        rig.connect("A")
        rig.sight("A")
        rig.advance(1)
        XCTAssertEqual(rig.connector.connected, ["A"])
    }

    func testOnlyOneConnectionIsOpenedAtATime() {
        let rig = BleRig(role: .central)
        rig.start()
        rig.sight("A")
        rig.sight("B")
        XCTAssertEqual(rig.connector.connected, ["A"])
        rig.emit(rig.connector.links["A"]!, .ready(payloadMtu: 182))
        rig.advance(1)
        XCTAssertEqual(rig.connector.connected, ["A", "B"])
    }

    func testOneSlotStaysFreeForIncomingConnections() {
        let rig = BleRig(role: .central) { $0.maxLinks = 3 }
        rig.start()
        rig.connect("A")
        rig.connect("B")
        rig.sight("C")
        rig.advance(1)
        XCTAssertEqual(rig.connector.connected, ["A", "B"])
    }

    func testADeviceThatCannotBeConnectedToIsBackedOffWithADoublingDelay() {
        let rig = BleRig(role: .central)
        rig.connector.unknown = ["A"]
        rig.start()
        rig.sight("A")
        XCTAssertEqual(rig.connector.connected, ["A"])
        rig.sight("A")
        XCTAssertEqual(rig.connector.connected, ["A"], "no new attempt during the cool-down")
        rig.advance(5_001)
        XCTAssertEqual(rig.connector.connected, ["A", "A"], "the second attempt comes after 5 s")
        rig.advance(5_001)
        XCTAssertEqual(rig.connector.connected, ["A", "A"], "the delay doubled to 10 s")
        rig.sight("A")
        rig.advance(5_000)
        XCTAssertEqual(rig.connector.connected, ["A", "A", "A"])
        XCTAssertEqual(rig.transport.stats().connectFailures, 3)
    }

    func testALinkThatNeverBecomesUsableIsClosedAfterTheHandshakeTime() {
        let rig = BleRig(role: .central)
        rig.start()
        rig.sight("A")
        let link = rig.connector.links["A"]!
        rig.advance(14_000)
        XCTAssertEqual(link.closeCount, 0)
        rig.advance(1_001)
        XCTAssertEqual(link.closeCount, 1)
        XCTAssertEqual(rig.events, [])
        XCTAssertTrue(rig.transport.linkInfos().isEmpty)
    }

    func testALinkThatFailsToConnectIsForgotten() {
        let rig = BleRig(role: .central)
        rig.start()
        rig.sight("A")
        let link = rig.connector.links["A"]!
        rig.emit(link, .closed(.connectFailed))
        XCTAssertTrue(rig.transport.linkInfos().isEmpty)
        XCTAssertEqual(rig.events, [])
        XCTAssertEqual(link.closeCount, 0, "a link the platform closed is not closed again")
    }

    // MARK: Link size

    func testALinkThatIsTooSmallIsNotReportedUntilItGrows() {
        let rig = BleRig(role: .central)
        rig.start()
        let link = rig.connect("A", payload: 40)
        XCTAssertEqual(rig.events, [])
        rig.emit(link, .mtuChanged(payloadMtu: 100))
        XCTAssertEqual(rig.events, [.peerSeen(peer: 1, linkMtu: 100)])
    }

    func testALinkThatStaysTooSmallIsClosed() {
        let rig = BleRig(role: .central)
        rig.start()
        let link = rig.connect("A", payload: 40)
        rig.advance(15_001)
        XCTAssertEqual(link.closeCount, 1)
        XCTAssertEqual(rig.events, [])
    }

    func testAGrowingLinkIsReportedAgainUnderTheSameHandle() {
        let rig = BleRig(role: .central)
        rig.start()
        let link = rig.connect("A", payload: 100)
        rig.emit(link, .mtuChanged(payloadMtu: 100))
        rig.emit(link, .mtuChanged(payloadMtu: 50))
        rig.emit(link, .mtuChanged(payloadMtu: 300))
        XCTAssertEqual(rig.events, [.peerSeen(peer: 1, linkMtu: 100), .peerSeen(peer: 1, linkMtu: 300)])
    }

    func testThePayloadSizeIsPolledAtEveryTick() {
        let rig = BleRig(role: .central)
        rig.start()
        let link = rig.connect("A", payload: 100)
        link.polledPayload = 180
        rig.advance(1)
        XCTAssertEqual(rig.events, [.peerSeen(peer: 1, linkMtu: 100), .peerSeen(peer: 1, linkMtu: 180)])
    }

    // MARK: Incoming links

    func testACentralThatSubscribedBecomesALink() {
        let rig = BleRig(role: .peripheral)
        rig.start()
        let link = rig.incoming("C", payload: 182)
        XCTAssertEqual(link.role, .server)
        XCTAssertEqual(rig.events, [.peerSeen(peer: 1, linkMtu: 182)])
    }

    func testIncomingLinksAboveTheLimitAreRefused() {
        let rig = BleRig(role: .peripheral) { $0.maxLinks = 1 }
        rig.start()
        _ = rig.incoming("C1")
        let second = rig.incoming("C2")
        XCTAssertEqual(second.closeCount, 1)
        XCTAssertEqual(rig.events, [.peerSeen(peer: 1, linkMtu: 185)])
    }

    // MARK: Receiving

    func testPacketsAreHandedToTheCoreUnderTheHandleOfTheLink() {
        let rig = BleRig(role: .central)
        rig.start()
        let link = rig.connect("A")
        rig.emit(link, .received(Data([1, 2, 3])))
        rig.emit(link, .received(Data()))
        XCTAssertEqual(rig.events, [.peerSeen(peer: 1, linkMtu: 185), .packetReceived(peer: 1, bytes: Data([1, 2, 3]))])
        let info = rig.transport.linkInfos()
        XCTAssertEqual(info.first?.metrics.packetsReceived, 1)
        XCTAssertEqual(info.first?.metrics.bytesReceived, 3)
    }

    func testPacketsBeforeTheLinkIsUsableAreIgnored() {
        let rig = BleRig(role: .central)
        rig.start()
        rig.sight("A")
        let link = rig.connector.links["A"]!
        rig.emit(link, .received(Data([9])))
        XCTAssertEqual(rig.events, [])
    }

    // MARK: Sending

    func testOnePacketIsInFlightAtATime() {
        let rig = BleRig(role: .central)
        rig.start()
        let link = rig.connect("A")
        rig.transport.send(peer: 1, bytes: Data([1]))
        rig.transport.send(peer: 1, bytes: Data([2]))
        rig.run {}
        XCTAssertEqual(link.sent, [Data([1])])
        rig.emit(link, .sendCompleted(success: true))
        XCTAssertEqual(link.sent, [Data([1]), Data([2])])
        rig.emit(link, .sendCompleted(success: true))
        XCTAssertEqual(rig.transport.linkInfos().first?.metrics.packetsSent, 2)
        XCTAssertEqual(rig.transport.linkInfos().first?.metrics.bytesSent, 2)
        XCTAssertEqual(rig.transport.linkInfos().first?.queuedBytes, 0)
    }

    func testAPacketLargerThanTheLinkIsDropped() {
        let rig = BleRig(role: .central)
        rig.start()
        let link = rig.connect("A", payload: 100)
        rig.transport.send(peer: 1, bytes: Data(count: 101))
        rig.transport.send(peer: 1, bytes: Data(count: 100))
        rig.run {}
        XCTAssertEqual(link.sent, [Data(count: 100)])
        XCTAssertEqual(rig.transport.linkInfos().first?.metrics.packetsDropped, 1)
    }

    func testAPacketThatDoesNotFitTheQueueIsDropped() {
        let rig = BleRig(role: .central) { $0.maxQueuedBytes = 150 }
        rig.start()
        let link = rig.connect("A", payload: 100)
        rig.transport.send(peer: 1, bytes: Data(count: 100))
        rig.transport.send(peer: 1, bytes: Data(count: 100))
        rig.run {}
        XCTAssertEqual(link.sent.count, 1)
        XCTAssertEqual(rig.transport.linkInfos().first?.metrics.packetsDropped, 1)
        XCTAssertEqual(rig.transport.linkInfos().first?.queuedBytes, 100)
    }

    func testABusyPlatformIsAskedAgainWhenItIsReady() {
        let rig = BleRig(role: .central)
        rig.start()
        let link = rig.connect("A")
        link.sendResult = .busy
        rig.transport.send(peer: 1, bytes: Data([7]))
        rig.run {}
        XCTAssertEqual(link.sent, [])
        link.sendResult = .accepted
        rig.emit(link, .readyToSend)
        XCTAssertEqual(link.sent, [Data([7])])
    }

    func testABusyPlatformIsAlsoAskedAgainAtTheNextTick() {
        let rig = BleRig(role: .central)
        rig.start()
        let link = rig.connect("A")
        link.sendResult = .busy
        rig.transport.send(peer: 1, bytes: Data([7]))
        rig.run {}
        link.sendResult = .accepted
        rig.advance(250)
        XCTAssertEqual(link.sent, [Data([7])])
    }

    func testAPlatformThatStaysBusyClosesTheLink() {
        let rig = BleRig(role: .central)
        rig.start()
        let link = rig.connect("A")
        link.sendResult = .busy
        rig.transport.send(peer: 1, bytes: Data([7]))
        rig.run {}
        rig.advance(4_000)
        XCTAssertEqual(link.closeCount, 0)
        rig.advance(1_001)
        XCTAssertEqual(link.closeCount, 1)
        XCTAssertEqual(rig.events.last, .peerLost(peer: 1))
    }

    func testASendThatNeverCompletesClosesTheLink() {
        let rig = BleRig(role: .central)
        rig.start()
        let link = rig.connect("A")
        rig.transport.send(peer: 1, bytes: Data([7]))
        rig.run {}
        rig.advance(5_001)
        XCTAssertEqual(link.closeCount, 1)
        XCTAssertEqual(rig.events.last, .peerLost(peer: 1))
    }

    func testSendsThatKeepFailingCloseTheLink() {
        let rig = BleRig(role: .central)
        rig.start()
        let link = rig.connect("A")
        link.sendResult = .failed
        for byte in 1...3 { rig.transport.send(peer: 1, bytes: Data([UInt8(byte)])) }
        rig.run {}
        XCTAssertEqual(link.closeCount, 1)
        XCTAssertEqual(rig.events.last, .peerLost(peer: 1))
    }

    func testSendingToAnUnknownPeerDoesNothing() {
        let rig = BleRig(role: .central)
        rig.start()
        let link = rig.connect("A")
        rig.transport.send(peer: 99, bytes: Data([1]))
        rig.run {}
        XCTAssertEqual(link.sent, [])
    }

    // MARK: Losing links

    func testALostLinkIsReportedOnceAndAReconnectGetsANewHandle() {
        let rig = BleRig(role: .central)
        rig.start()
        let first = rig.connect("A")
        rig.emit(first, .closed(.remote))
        XCTAssertEqual(rig.events, [.peerSeen(peer: 1, linkMtu: 185), .peerLost(peer: 1)])

        rig.sight("A")
        XCTAssertEqual(rig.connector.connected, ["A"], "the address is left alone for a moment")
        rig.advance(5_001)
        XCTAssertEqual(rig.connector.connected, ["A", "A"])
        let second = rig.connector.links["A"]!
        XCTAssertFalse(first === second)
        rig.emit(second, .ready(payloadMtu: 185))
        XCTAssertEqual(rig.events.last, .peerSeen(peer: 2, linkMtu: 185))
    }

    func testTheCoreCanCloseALink() {
        let rig = BleRig(role: .central)
        rig.start()
        let link = rig.connect("A")
        rig.transport.disconnect(peer: 1)
        rig.run {}
        XCTAssertEqual(link.closeCount, 1)
        XCTAssertEqual(rig.events.last, .peerLost(peer: 1))
        rig.transport.disconnect(peer: 1)
        rig.run {}
        XCTAssertEqual(rig.events.filter { $0 == .peerLost(peer: 1) }.count, 1)
    }

    func testStoppingClosesEverythingAndReportsTheLoss() {
        let rig = BleRig()
        rig.start()
        let outgoing = rig.connect("A")
        let incoming = rig.incoming("C")
        rig.transport.stop()
        XCTAssertEqual(outgoing.closeCount, 1)
        XCTAssertEqual(incoming.closeCount, 1)
        XCTAssertEqual(rig.scanner.stopCount, 1)
        XCTAssertEqual(rig.advertiser.stopCount, 1)
        XCTAssertEqual(rig.server.closeCount, 1)
        XCTAssertTrue(rig.events.contains(.peerLost(peer: 1)))
        XCTAssertTrue(rig.events.contains(.peerLost(peer: 2)))
        rig.transport.send(peer: 1, bytes: Data([1]))
        rig.run {}
        XCTAssertEqual(outgoing.sent, [])
    }

    // MARK: Duplicate links (profile 1.1)

    func testOfTwoLinksToOneNeighbourTheOneOpenedByTheLowerIdentifierStays() {
        // We are 0x1111. The neighbour is 0x2222: our (client) link stays, the one it opened to us goes.
        let rig = BleRig(role: .both, hello: 0x1111)
        rig.start()
        let ours = rig.connect("X")
        let theirs = rig.incoming("Y")
        rig.emit(ours, .peerHello(0x2222))
        XCTAssertEqual(theirs.closeCount, 0)
        rig.emit(theirs, .peerHello(0x2222))
        XCTAssertEqual(ours.closeCount, 0)
        XCTAssertEqual(theirs.closeCount, 1)
        XCTAssertEqual(rig.events.last, .peerLost(peer: 2))
        XCTAssertEqual(rig.transport.stats().duplicateLinks, 1)
    }

    func testTheOtherWayRoundOurOwnClientLinkIsClosedAndNotOpenedAgainForAWhile() {
        // The neighbour is 0x0005, lower than we are: the link it opened stays, ours goes.
        let rig = BleRig(role: .both, hello: 0x1111)
        rig.start()
        let ours = rig.connect("X")
        let theirs = rig.incoming("Y")
        rig.emit(ours, .peerHello(0x0005))
        rig.emit(theirs, .peerHello(0x0005))
        XCTAssertEqual(ours.closeCount, 1)
        XCTAssertEqual(theirs.closeCount, 0)
        XCTAssertEqual(rig.events.last, .peerLost(peer: 1))

        rig.sight("X")
        rig.advance(60_000)
        XCTAssertEqual(rig.connector.connected, ["X"], "the extra link is not opened again")
    }

    func testTwoLinksOpenedByTheSameClientAreLeftToThatClient() {
        let rig = BleRig(role: .peripheral, hello: 0x1111)
        rig.start()
        let first = rig.incoming("C1")
        let second = rig.incoming("C2")
        rig.emit(first, .peerHello(7))
        rig.emit(second, .peerHello(7))
        XCTAssertEqual(first.closeCount, 0)
        XCTAssertEqual(second.closeCount, 0)
    }

    func testOnlyTheFirstHelloOfALinkCounts() {
        let rig = BleRig(role: .both, hello: 0x1111)
        rig.start()
        let ours = rig.connect("X")
        let theirs = rig.incoming("Y")
        rig.emit(ours, .peerHello(0x2222))
        rig.emit(theirs, .peerHello(0x3333))
        rig.emit(theirs, .peerHello(0x2222))
        XCTAssertEqual(theirs.closeCount, 0, "the second hello of a link is ignored")
    }

    // MARK: The event stream

    func testTheEventStreamDeliversWhatTheCoreMustSee() async {
        let rig = BleRig(role: .central)
        rig.start()
        var iterator = rig.transport.events.makeAsyncIterator()
        rig.connect("A")
        let first = await iterator.next()
        XCTAssertEqual(first, .peerSeen(peer: 1, linkMtu: 185))
    }
}
