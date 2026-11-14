import AVFoundation
import CrisisCore
import CrisisRuntime
import XCTest
@testable import CrisisNetwork_iOS

/// Contacts (phase I5): the lists and rules behind the screens, the model with a real core underneath, the QR code and
/// the text form of a card. The conventions themselves are tested against the shared vectors in the `CrisisCore`
/// package (`ConventionVectorTests`).
final class ContactsTests: XCTestCase {
    // MARK: Helpers

    private static let start: UInt64 = 1_000_000

    private func node(_ seedByte: UInt8) throws -> CrisisNode {
        try CrisisNode.inMemory(seed: Data(repeating: seedByte, count: 32), settings: defaultSettings(), nowMs: Self.start)
    }

    private func info(
        _ byte: UInt8,
        card: Bool = false,
        trust: TrustLevel = .unknown,
        lastSeen: UInt64 = 0
    ) -> ContactInfo {
        ContactInfo(
            nodeId: Data(repeating: byte, count: 32),
            card: card ? Data(repeating: byte, count: 128) : nil,
            safetyNumber: card ? "1111 2222 3333 4444 5555 6666 7777 8888" : nil,
            trust: trust,
            firstSeenMs: 1,
            lastSeenMs: lastSeen
        )
    }

    @MainActor
    private func makeModel(
        backend: (any ContactsBackend)?,
        ownNodeId: String? = nil,
        nicknames: MemoryKeyValueStore = MemoryKeyValueStore(),
        pending: MemoryKeyValueStore = MemoryKeyValueStore(),
        savedCard: SavedCard? = nil
    ) -> ContactsModel {
        ContactsModel(
            nicknames: NicknameStore(nicknames),
            pending: PendingCards(pending),
            backend: { backend },
            ownNodeIdHex: { ownNodeId },
            saveCardText: { savedCard?.text = $0 }
        )
    }

    /// A box that remembers the card text the model saved.
    final class SavedCard {
        var text: String?
    }

    /// A node of the core without a transport, as the model's backend.
    final class NodeBackend: ContactsBackend, @unchecked Sendable {
        let node: CrisisNode
        init(_ node: CrisisNode) { self.node = node }
        var nodeIdHex: String { node.nodeIdHex() }
        var contactCard: Data { node.contactCard() }
        func addContact(card: Data) async throws -> CardDetails { try node.addContact(nowMs: 1_000_000, card: card) }
        func contacts(limit: UInt32) async throws -> [ContactInfo] { try node.contacts(limit: limit) }
        func setTrust(nodeId: Data, trust: TrustLevel) async throws {
            try node.setTrust(nowMs: 1_000_000, nodeId: nodeId, trust: trust)
        }
    }

    // MARK: Hexadecimal

    func testHexRoundTripsAndRefusesWhatIsNotHex() {
        let bytes = Data([0x00, 0x0f, 0xa5, 0xff])
        XCTAssertEqual(Hex.encode(bytes), "000fa5ff")
        XCTAssertEqual(Hex.decode("000fa5ff"), bytes)
        XCTAssertEqual(Hex.decode("000FA5FF"), bytes)
        XCTAssertEqual(Hex.decode(""), Data())
        XCTAssertNil(Hex.decode("abc"))
        XCTAssertNil(Hex.decode("zz"))
    }

    // MARK: Nicknames

    func testANicknameIsCleanedAndAnEmptyOneRemovesIt() {
        let store = NicknameStore(MemoryKeyValueStore())
        XCTAssertEqual(store.set("aa", "  Ali \n"), "Ali")
        XCTAssertEqual(store.loadAll(), ["aa": "Ali"])
        XCTAssertNil(store.set("aa", "   "))
        XCTAssertEqual(store.loadAll(), [:])
    }

    func testANicknameIsAtMostFortyCharacters() {
        XCTAssertEqual(NicknameStore.maxLength, 40)
        XCTAssertEqual(NicknameStore.normalize(String(repeating: "x", count: 60))?.count, 40)
    }

    func testNicknamesSurviveInTheDefaultsAndStayInTheirOwnKey() {
        let suite = "org.crisisnetwork.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set("keep", forKey: "other")
        NicknameStore(DefaultsKeyValueStore(defaults: defaults, key: "names")).set("aa", "Ali")
        let again = NicknameStore(DefaultsKeyValueStore(defaults: defaults, key: "names"))
        XCTAssertEqual(again.loadAll(), ["aa": "Ali"])
        again.clear()
        XCTAssertEqual(again.loadAll(), [:])
        XCTAssertEqual(defaults.string(forKey: "other"), "keep")
    }

    // MARK: Pending cards

    func testAPendingCardIsKeptOnceAndCanBeRemoved() {
        let pending = PendingCards(MemoryKeyValueStore())
        pending.add("crisis:one")
        pending.add("crisis:one")
        pending.add("crisis:two")
        XCTAssertEqual(Set(pending.all()), ["crisis:one", "crisis:two"])
        XCTAssertEqual(pending.all().count, 2)
        pending.remove("crisis:one")
        XCTAssertEqual(pending.all(), ["crisis:two"])
        pending.clear()
        XCTAssertTrue(pending.all().isEmpty)
    }

    // MARK: The list

    func testOnlyKnownPeopleAreContacts() {
        let seenOnly = info(1)
        let withCard = info(2, card: true)
        let changed = info(3, trust: .blocked)
        let named = info(4)
        let names = [Hex.encode(named.nodeId): "Ayşe"]
        XCTAssertFalse(ContactBook.isKnownPerson(seenOnly, names: names))
        XCTAssertTrue(ContactBook.isKnownPerson(withCard, names: names))
        XCTAssertTrue(ContactBook.isKnownPerson(changed, names: names))
        XCTAssertTrue(ContactBook.isKnownPerson(named, names: names))
        let rows = ContactBook.rows(from: [seenOnly, withCard, changed, named], names: names)
        XCTAssertEqual(Set(rows.map(\.hex)), Set([withCard, changed, named].map { Hex.encode($0.nodeId) }))
    }

    func testTheListShowsTheLastSeenFirstAndNeverJumpsOnTies() {
        let rows = ContactBook.rows(
            from: [info(1, card: true, lastSeen: 10), info(3, card: true, lastSeen: 30), info(2, card: true, lastSeen: 30)],
            names: [:]
        )
        XCTAssertEqual(rows.map(\.lastSeenMs), [30, 30, 10])
        XCTAssertEqual(rows[0].hex < rows[1].hex, true)
    }

    func testARowKnowsWhetherItHasACard() {
        let row = ContactRow(info(5, card: true, trust: .verified))
        XCTAssertTrue(row.hasCard)
        XCTAssertEqual(row.trust, .verified)
        XCTAssertNotNil(row.safetyNumber)
        XCTAssertFalse(ContactRow(info(6, trust: .blocked)).hasCard)
    }

    func testANodeWithoutANicknameIsCalledByItsShortAddress() {
        let hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        XCTAssertEqual(ContactBook.displayName(hex: hex, names: [hex: "Ali"], unknownFormat: "Node %@"), "Ali")
        XCTAssertEqual(ContactBook.displayName(hex: hex, names: [:], unknownFormat: "Node %@"), "Node 01234567")
    }

    func testTrustLevelsHaveATonePerLevel() {
        XCTAssertEqual(TrustLevel.unknown.tone, .neutral)
        XCTAssertEqual(TrustLevel.verified.tone, .success)
        XCTAssertEqual(TrustLevel.blocked.tone, .error)
    }

    // MARK: The model with a real core

    @MainActor
    func testAddingACardStoresTheContactWithItsNickname() async throws {
        let mine = try node(1), theirs = try node(2)
        let model = makeModel(backend: NodeBackend(mine))
        let result = await model.addContact(card: theirs.contactCard(), nickname: "  Ali ")
        XCTAssertEqual(result, .added(hex: theirs.nodeIdHex()))
        XCTAssertEqual(model.contacts.map(\.hex), [theirs.nodeIdHex()])
        XCTAssertEqual(model.contacts.first?.hasCard, true)
        XCTAssertEqual(model.contacts.first?.trust, .unknown)
        XCTAssertEqual(model.names[theirs.nodeIdHex()], "Ali")
        XCTAssertTrue(model.ready)
    }

    @MainActor
    func testYourOwnCardAndGarbageAreRefused() async throws {
        let mine = try node(1)
        let model = makeModel(backend: NodeBackend(mine))
        let own = await model.addContact(card: mine.contactCard(), nickname: nil)
        XCTAssertEqual(own, .ownCard)
        let garbage = await model.addContact(card: Data(count: 3), nickname: nil)
        XCTAssertEqual(garbage, .invalid)
        XCTAssertTrue(model.contacts.isEmpty)
    }

    @MainActor
    func testYourOwnCardIsRecognisedWhileTheNodeIsNotRunning() async throws {
        let mine = try node(1)
        let model = makeModel(backend: nil, ownNodeId: mine.nodeIdHex())
        let own = await model.addContact(card: mine.contactCard(), nickname: nil)
        XCTAssertEqual(own, .ownCard)
    }

    @MainActor
    func testACardAddedWhileTheNodeIsOffIsKeptAndAddedWhenItRuns() async throws {
        let mine = try node(1), theirs = try node(2)
        let pending = MemoryKeyValueStore()
        let box = BackendBox()
        let model = ContactsModel(
            nicknames: NicknameStore(MemoryKeyValueStore()),
            pending: PendingCards(pending),
            backend: { box.backend },
            ownNodeIdHex: { mine.nodeIdHex() }
        )
        let queued = await model.addContact(card: theirs.contactCard(), nickname: "Ali")
        XCTAssertEqual(queued, .queued(hex: theirs.nodeIdHex()))
        XCTAssertFalse(model.ready)
        XCTAssertEqual(PendingCards(pending).all().count, 1)
        XCTAssertEqual(model.names[theirs.nodeIdHex()], "Ali")

        box.backend = NodeBackend(mine)
        await model.refresh()
        XCTAssertTrue(model.ready)
        XCTAssertEqual(model.contacts.map(\.hex), [theirs.nodeIdHex()])
        XCTAssertTrue(PendingCards(pending).all().isEmpty)
    }

    final class BackendBox: @unchecked Sendable {
        var backend: (any ContactsBackend)?
    }

    @MainActor
    func testTheTrustLevelChangesOnlyWhileTheNodeRuns() async throws {
        let mine = try node(1), theirs = try node(2)
        let hex = theirs.nodeIdHex()
        let model = makeModel(backend: NodeBackend(mine))
        _ = await model.addContact(card: theirs.contactCard(), nickname: nil)

        var changed = await model.setTrust(hex: hex, trust: .verified)
        XCTAssertTrue(changed)
        XCTAssertEqual(model.contacts.first?.trust, .verified)
        changed = await model.setTrust(hex: hex, trust: .blocked)
        XCTAssertTrue(changed)
        XCTAssertEqual(model.contacts.first?.trust, .blocked)
        changed = await model.setTrust(hex: "not hex", trust: .unknown)
        XCTAssertFalse(changed)

        let off = makeModel(backend: nil)
        changed = await off.setTrust(hex: hex, trust: .unknown)
        XCTAssertFalse(changed)
    }

    @MainActor
    func testANicknameAloneMakesAPersonAndResetForgetsEverything() async throws {
        let mine = try node(1)
        let model = makeModel(backend: NodeBackend(mine))
        model.setNickname(hex: "ab", raw: "Ayşe")
        XCTAssertEqual(model.names["ab"], "Ayşe")
        model.setNickname(hex: "ab", raw: "")
        XCTAssertNil(model.names["ab"])
        model.setNickname(hex: "cd", raw: "Can")
        model.reset()
        XCTAssertTrue(model.names.isEmpty)
        XCTAssertTrue(model.contacts.isEmpty)
    }

    @MainActor
    func testTheModelKeepsTheTextOfTheOwnCardWhileTheNodeRuns() async throws {
        let mine = try node(1)
        let saved = SavedCard()
        let model = makeModel(backend: NodeBackend(mine), savedCard: saved)
        await model.refresh()
        XCTAssertEqual(saved.text, CardText.encode(mine.contactCard()))
        XCTAssertTrue(saved.text?.hasPrefix(conventionInfo().cardPrefix) == true)
    }

    // MARK: Card text and the QR code

    func testACardSurvivesItsTextForm() throws {
        let card = try node(7).contactCard()
        let text = CardText.encode(card)
        XCTAssertEqual(CardText.decode(text), card)
        XCTAssertEqual(CardText.decode("  \(text.uppercased().prefix(7))\(text.dropFirst(7))\n"), card)
        XCTAssertNil(CardText.decode("crisis:!!!"))
        XCTAssertNil(CardText.decode("hello"))
    }

    func testACardTextCanBeDrawnAsAQrCodeAndReadBack() throws {
        let text = CardText.encode(try node(8).contactCard())
        let image = try XCTUnwrap(QrCode.image(of: text, moduleSize: 8, quietZone: 4))
        // Square, and big enough to read: version 8 is 49 modules, plus the quiet zone.
        XCTAssertEqual(image.width, image.height)
        XCTAssertGreaterThanOrEqual(image.width, (45 + 8) * 8)
        XCTAssertEqual(QrCode.read(image), text)
    }

    func testAPictureWithoutAQrCodeReadsAsNothing() throws {
        let size = 64
        let space = CGColorSpaceCreateDeviceGray()
        let context = try XCTUnwrap(CGContext(
            data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size,
            space: space, bitmapInfo: CGImageAlphaInfo.none.rawValue
        ))
        context.setFillColor(gray: 1, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: size, height: size))
        let white = try XCTUnwrap(context.makeImage())
        XCTAssertNil(QrCode.read(white))
    }

    // MARK: The camera

    func testTheCameraReadinessFollowsThePermission() {
        XCTAssertEqual(CameraReadiness.of(status: .authorized, hasCamera: true), .ready)
        XCTAssertEqual(CameraReadiness.of(status: .notDetermined, hasCamera: true), .notDetermined)
        XCTAssertEqual(CameraReadiness.of(status: .denied, hasCamera: true), .denied)
        XCTAssertEqual(CameraReadiness.of(status: .restricted, hasCamera: true), .restricted)
        // No camera wins over everything else: there is nothing to ask for.
        XCTAssertEqual(CameraReadiness.of(status: .authorized, hasCamera: false), .unavailable)
        XCTAssertEqual(CameraReadiness.of(status: .notDetermined, hasCamera: false), .unavailable)
    }

    // MARK: Strings

    func testTheStringsOfTheContactScreensAreTranslated() {
        let keys = [
            "contacts_empty_title", "contacts_mesh_off_body", "trust_unknown", "trust_verified", "trust_blocked",
            "contacts_my_card", "contacts_scan", "contacts_paste", "mycard_body", "mycard_unavailable", "scan_hint",
            "scan_permission_body", "scan_denied_body", "scan_no_camera", "paste_hint", "paste_invalid", "add_title",
            "add_safety_hint", "add_queued", "add_own", "contact_unknown_hint", "contact_safety_hint", "contact_verify",
            "contact_block_hint", "contact_needs_mesh",
        ]
        for key in keys {
            let english = L10n.string(key, language: .english)
            let turkish = L10n.string(key, language: .turkish)
            XCTAssertNotEqual(english, key, "\(key) is missing in English")
            XCTAssertNotEqual(turkish, key, "\(key) is missing in Turkish")
            XCTAssertNotEqual(english, turkish, "\(key) is the same in both languages")
        }
        XCTAssertEqual(L10n.string("name_unknown_node %@", language: .english, "01234567"), "Node 01234567")
        XCTAssertEqual(L10n.string("name_unknown_node %@", language: .turkish, "01234567"), "Düğüm 01234567")
    }
}
