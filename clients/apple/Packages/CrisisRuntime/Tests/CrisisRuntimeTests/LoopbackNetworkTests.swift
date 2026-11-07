import XCTest
@testable import CrisisRuntime

final class LoopbackNetworkTests: XCTestCase {
    func testConnectingReportsAFreshHandleOnBothSides() async {
        let network = LoopbackNetwork()
        let a = network.endpoint()
        let b = network.endpoint()
        var atA = a.events.makeAsyncIterator()
        var atB = b.events.makeAsyncIterator()
        let link = network.connect(a, b, linkMtu: 100)
        let seenByA = await atA.next()
        let seenByB = await atB.next()
        XCTAssertEqual(seenByA, .peerSeen(peer: link.handleA, linkMtu: 100))
        XCTAssertEqual(seenByB, .peerSeen(peer: link.handleB, linkMtu: 100))
        let second = network.connect(a, b)
        XCTAssertNotEqual(second.handleA, link.handleA, "a handle is never reused")
    }

    func testPacketsArriveInOrderUnderTheHandleOfTheReceiver() async {
        let network = LoopbackNetwork()
        let a = network.endpoint()
        let b = network.endpoint()
        var atB = b.events.makeAsyncIterator()
        let link = network.connect(a, b)
        _ = await atB.next() // peerSeen
        a.send(peer: link.handleA, bytes: Data([1]))
        a.send(peer: link.handleA, bytes: Data([2]))
        let first = await atB.next()
        let second = await atB.next()
        XCTAssertEqual(first, .packetReceived(peer: link.handleB, bytes: Data([1])))
        XCTAssertEqual(second, .packetReceived(peer: link.handleB, bytes: Data([2])))
    }

    func testAPacketBeyondTheLinkMtuIsDropped() async {
        let network = LoopbackNetwork()
        let a = network.endpoint()
        let b = network.endpoint()
        var atB = b.events.makeAsyncIterator()
        let link = network.connect(a, b, linkMtu: 10)
        _ = await atB.next()
        a.send(peer: link.handleA, bytes: Data(count: 11))
        a.send(peer: link.handleA, bytes: Data(count: 10))
        let arrived = await atB.next()
        XCTAssertEqual(arrived, .packetReceived(peer: link.handleB, bytes: Data(count: 10)))
        XCTAssertEqual(a.oversizedPacketsDropped, 1)
    }

    func testCuttingALinkReportsTheLossOnceOnEachSide() async {
        let network = LoopbackNetwork()
        let a = network.endpoint()
        let b = network.endpoint()
        var atA = a.events.makeAsyncIterator()
        var atB = b.events.makeAsyncIterator()
        let link = network.connect(a, b)
        _ = await atA.next()
        _ = await atB.next()
        network.disconnect(link)
        network.disconnect(link)
        let lostAtA = await atA.next()
        let lostAtB = await atB.next()
        XCTAssertEqual(lostAtA, .peerLost(peer: link.handleA))
        XCTAssertEqual(lostAtB, .peerLost(peer: link.handleB))
        a.send(peer: link.handleA, bytes: Data([1])) // goes nowhere
        a.disconnect(peer: link.handleA) // harmless
    }

    func testDisconnectingFromOneSideCutsTheOtherToo() async {
        let network = LoopbackNetwork()
        let a = network.endpoint()
        let b = network.endpoint()
        var atB = b.events.makeAsyncIterator()
        let link = network.connect(a, b)
        _ = await atB.next()
        a.disconnect(peer: link.handleA)
        let lost = await atB.next()
        XCTAssertEqual(lost, .peerLost(peer: link.handleB))
    }
}
