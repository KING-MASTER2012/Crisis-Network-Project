import CrisisCore
import XCTest
@testable import CrisisNetwork_iOS

/// The introduction: one scan of a QR code is enough to become contacts of each other.
final class IntroductionTests: XCTestCase {
    private func node(_ seedByte: UInt8) throws -> CrisisNode {
        try CrisisNode.inMemory(seed: Data(repeating: seedByte, count: 32), settings: defaultSettings(), nowMs: 1_000_000)
    }

    @MainActor
    private func makeModel(_ node: CrisisNode, now: (@MainActor () -> Date)? = nil) -> ContactsModel {
        let backend = ContactsTests.NodeBackend(node)
        let model = ContactsModel(
            nicknames: NicknameStore(MemoryKeyValueStore()),
            pending: PendingCards(MemoryKeyValueStore()),
            backend: { backend }
        )
        if let now { model.now = now }
        return model
    }

    private func knows(_ owner: CrisisNode, _ other: CrisisNode) throws -> Bool {
        try owner.contacts(limit: 10).contains { $0.nodeId == other.nodeId() && $0.card != nil }
    }

    func testOrdinaryMessagesAreNotIntroductions() {
        XCTAssertFalse(Introduction.looksLike(Data()))
        XCTAssertFalse(Introduction.looksLike(Data("hello".utf8)))
        XCTAssertFalse(Introduction.looksLike(Data(String(repeating: "A", count: 171).utf8)))
        XCTAssertFalse(Introduction.looksLike(Data("see crisis:\(String(repeating: "A", count: 171))".utf8)))
        XCTAssertFalse(Introduction.looksLike(Data("crisis:\(String(repeating: "A", count: 500))".utf8)))
    }

    func testACardFromItsOwnerIsAnIntroductionAndNobodyElsesIs() throws {
        let alice = try node(1)
        let body = Introduction.encode(alice.contactCard())
        XCTAssertTrue(Introduction.looksLike(body))
        XCTAssertEqual(Introduction.parse(body, owner: alice.nodeId()), alice.contactCard())
        XCTAssertNil(Introduction.parse(body, owner: try node(2).nodeId()))
    }

    @MainActor
    func testAnIntroductionAddsTheSenderOnlyWhileTheOwnCodeIsOnScreen() async throws {
        let alice = try node(1)
        let bob = try node(2)
        let body = Introduction.encode(alice.contactCard())

        let closed = makeModel(bob)
        await closed.receive(introductionFrom: alice.nodeId(), body: body)
        XCTAssertFalse(try knows(bob, alice))
        XCTAssertNil(closed.introduced)

        let open = makeModel(bob)
        open.setPairingScreen(open: true)
        await open.receive(introductionFrom: alice.nodeId(), body: body)
        XCTAssertTrue(try knows(bob, alice))
        XCTAssertEqual(open.introduced, Hex.encode(alice.nodeId()))
    }

    @MainActor
    func testTheWindowStaysOpenForAWhileAfterTheScreenIsLeft() async throws {
        let alice = try node(1)
        let body = Introduction.encode(alice.contactCard())
        var clock = Date(timeIntervalSince1970: 1_000)

        let late = try node(2)
        let model = makeModel(late, now: { clock })
        model.setPairingScreen(open: true)
        model.setPairingScreen(open: false)
        clock = clock.addingTimeInterval(ContactsModel.pairingGraceSeconds + 1)
        await model.receive(introductionFrom: alice.nodeId(), body: body)
        XCTAssertFalse(try knows(late, alice))

        let soon = try node(3)
        let quick = makeModel(soon, now: { clock })
        quick.setPairingScreen(open: true)
        quick.setPairingScreen(open: false)
        clock = clock.addingTimeInterval(60)
        await quick.receive(introductionFrom: alice.nodeId(), body: body)
        XCTAssertTrue(try knows(soon, alice))
    }

    @MainActor
    func testACardThatBelongsToSomeoneElseAddsNobody() async throws {
        let alice = try node(1)
        let mallory = try node(3)
        let bob = try node(2)
        let model = makeModel(bob)
        model.setPairingScreen(open: true)
        await model.receive(introductionFrom: mallory.nodeId(), body: Introduction.encode(alice.contactCard()))
        XCTAssertFalse(try knows(bob, alice))
        XCTAssertFalse(try knows(bob, mallory))
    }
}
