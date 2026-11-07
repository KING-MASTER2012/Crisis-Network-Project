import CrisisCore
import XCTest
@testable import CrisisRuntime

/// Phase I1's exit test: two runtimes, each driving a real core, talk through the in-memory network (the same
/// scenarios as the Android `NodeRuntimeLoopbackTest`).
final class NodeRuntimeTests: XCTestCase {
    private struct Pair {
        let network: LoopbackNetwork
        let a: NodeRuntime
        let b: NodeRuntime
        let transportA: LoopbackTransport
        let transportB: LoopbackTransport
    }

    private func startedPair() async throws -> Pair {
        let network = LoopbackNetwork()
        let transportA = network.endpoint()
        let transportB = network.endpoint()
        let a = try makeRuntime(seedByte: 1, transport: transportA)
        let b = try makeRuntime(seedByte: 2, transport: transportB)
        try await a.start()
        try await b.start()
        return Pair(network: network, a: a, b: b, transportA: transportA, transportB: transportB)
    }

    func testABroadcastReachesTheNeighbour() async throws {
        let pair = try await startedPair()
        let atB = await pair.b.notifications()
        pair.network.connect(pair.transportA, pair.transportB)
        let text = Data("water at the school".utf8)
        _ = try await pair.a.publishBroadcast(payload: text)

        let received = await firstNotification(in: atB) { notification in
            if case let .messageReceived(_, _, kind, body) = notification { return kind == .broadcast && body == text }
            return false
        }
        XCTAssertNotNil(received, "the broadcast did not arrive")
        await pair.a.stop()
        await pair.b.stop()
    }

    func testAPrivateMessageArrivesAndIsAcknowledged() async throws {
        let pair = try await startedPair()
        let atA = await pair.a.notifications()
        let atB = await pair.b.notifications()
        pair.network.connect(pair.transportA, pair.transportB)
        _ = try await pair.a.addContact(card: pair.b.contactCard)
        _ = try await pair.b.addContact(card: pair.a.contactCard)

        let text = Data("only for you".utf8)
        let outcome = try await pair.a.publishDirect(to: pair.b.nodeId, plaintext: text)
        let messageId = outcome.messageId

        let arrived = await firstNotification(in: atB) { notification in
            if case let .messageReceived(_, _, kind, body) = notification { return kind == .direct && body == text }
            return false
        }
        XCTAssertNotNil(arrived, "the private message did not arrive")
        let acknowledged = await firstNotification(in: atA) { notification in
            if case let .delivered(id) = notification { return id == messageId }
            return false
        }
        XCTAssertNotNil(acknowledged, "the acknowledgement did not come back")
        await pair.a.stop()
        await pair.b.stop()
    }

    func testTheRuntimeFollowsTheLinkUpAndDown() async throws {
        let pair = try await startedPair()
        let link = pair.network.connect(pair.transportA, pair.transportB)
        let connected = await eventually { (try? await pair.a.connectedPeers())?.count == 1 }
        XCTAssertTrue(connected, "the neighbour was not seen")

        pair.network.disconnect(link)
        let gone = await eventually { (try? await pair.a.connectedPeers())?.isEmpty == true }
        XCTAssertTrue(gone, "the loss of the neighbour was not seen")
        await pair.a.stop()
        await pair.b.stop()
    }

    func testTheLifecycleHasThreeStates() async throws {
        let runtime = try makeRuntime(seedByte: 3, transport: LoopbackNetwork().endpoint())
        var state = await runtime.state
        XCTAssertEqual(state, .created)
        try await runtime.start()
        state = await runtime.state
        XCTAssertEqual(state, .running)
        await runtime.stop()
        state = await runtime.state
        XCTAssertEqual(state, .stopped)
        await runtime.stop() // harmless
    }

    func testStartingTwiceIsAnError() async throws {
        let runtime = try makeRuntime(seedByte: 3, transport: LoopbackNetwork().endpoint())
        try await runtime.start()
        do {
            try await runtime.start()
            XCTFail("a second start must fail")
        } catch {
            guard case .invalidArgument = CoreFailure(error) else { return XCTFail("unexpected: \(error)") }
        }
        await runtime.stop()
    }

    func testCallsAfterStopFail() async throws {
        let runtime = try makeRuntime(seedByte: 3, transport: LoopbackNetwork().endpoint())
        try await runtime.start()
        await runtime.stop()
        do {
            _ = try await runtime.stats()
            XCTFail("a stopped runtime must refuse calls")
        } catch {
            XCTAssertEqual(CoreFailure(error), .runtimeStopped)
        }
    }

    func testCallsBeforeStartAreServed() async throws {
        let runtime = try makeRuntime(seedByte: 3, transport: LoopbackNetwork().endpoint())
        let contacts = try await runtime.contacts(limit: 10)
        XCTAssertTrue(contacts.isEmpty)
        await runtime.stop()
    }

    func testATransportThatCannotStartFailsTheRuntime() async throws {
        let runtime = try makeRuntime(seedByte: 3, transport: FailingTransport())
        do {
            try await runtime.start()
            XCTFail("the runtime must not start without its transport")
        } catch {
            guard case .unexpected = CoreFailure(error) else { return XCTFail("unexpected: \(error)") }
        }
        let state = await runtime.state
        guard case .failed = state else { return XCTFail("expected failed, got \(state)") }
    }

    func testTheStateStreamTellsTheLifecycle() async throws {
        let runtime = try makeRuntime(seedByte: 3, transport: LoopbackNetwork().endpoint())
        let states = await runtime.states()
        try await runtime.start()
        await runtime.stop()
        var seen: [RuntimeState] = []
        for await state in states { seen.append(state) }
        XCTAssertEqual(seen.first, .created)
        XCTAssertEqual(seen.last, .stopped)
        XCTAssertTrue(seen.contains(.running))
    }

    func testTheNodeIdentityIsAvailableWithoutAwaiting() throws {
        let runtime = try makeRuntime(seedByte: 4, transport: LoopbackNetwork().endpoint())
        XCTAssertEqual(runtime.nodeId.count, 32)
        XCTAssertEqual(runtime.contactCard.count, 128)
        XCTAssertFalse(runtime.safetyNumber.isEmpty)
        XCTAssertEqual(runtime.nodeIdHex.count, 64)
    }
}
