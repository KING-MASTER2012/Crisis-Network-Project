import Combine
import CrisisCore
import CrisisRuntime
import Foundation

/// What the contact screens need from a running node. ``NodeRuntime`` is the real one; the tests use a node of their
/// own, without a transport.
protocol ContactsBackend: Sendable {
    var nodeIdHex: String { get }
    var contactCard: Data { get }
    func addContact(card: Data) async throws -> CardDetails
    func contacts(limit: UInt32) async throws -> [ContactInfo]
    func setTrust(nodeId: Data, trust: TrustLevel) async throws
}

extension NodeRuntime: ContactsBackend {}

/// How adding a contact ended.
enum AddResult: Equatable, Sendable {
    /// The contact is stored.
    case added(hex: String)

    /// The node is not running: the card is kept and added as soon as it runs.
    case queued(hex: String)

    /// The card is this device's own.
    case ownCard
    case invalid
    case failed(detail: String)
}

/// Everything the contact screens read and do (phase I5), in one holder for the whole app so that it survives a screen
/// being closed and does not depend on a screen being there. The same job as the contact half of the Android
/// `Messaging` object, with the same rules:
///
/// * The **contacts** come from the core, which keeps the cards and the trust levels. They are read again when the node
///   starts or stops and whenever its data changes.
/// * **Nicknames** are kept here, on this device only, by node id.
/// * The node is only open while the mesh runs (the database cannot be opened twice), so the trust level can only be
///   changed then (`ready`). A card added while it is off is **kept** and added as soon as the node runs.
@MainActor
final class ContactsModel: ObservableObject {
    static let shared: ContactsModel = {
        let model = ContactsModel(
            nicknames: NicknameStore(DefaultsKeyValueStore(key: "contacts.nicknames")),
            pending: PendingCards(DefaultsKeyValueStore(key: "contacts.pendingCards")),
            backend: { BleLinkTestModel.shared.activeRuntime },
            ownNodeIdHex: { UiPreferences.shared.nodeIdHex },
            saveCardText: { UiPreferences.shared.saveCardText($0) }
        )
        model.sendIntroduction = ContactsModel.sendThroughRuntime
        return model
    }()

    /// Sends this device's own card to `peer` as an introduction (see ``Introduction``) through the running node.
    private static func sendThroughRuntime(to peer: Data) async {
        guard let runtime = BleLinkTestModel.shared.activeRuntime else { return }
        _ = try? await runtime.publishDirect(to: peer, plaintext: Introduction.encode(runtime.contactCard))
    }

    /// How long after leaving the "my QR code" screen a scan of that code still adds the scanner (late delivery over the mesh).
    static let pairingGraceSeconds: TimeInterval = 10 * 60

    /// How many contacts are read at most.
    static let contactLimit: UInt32 = 1_000

    /// `true` while the node is open: the trust levels can be changed and the list is current.
    @Published private(set) var ready = false

    /// The known people (see ``ContactBook/isKnownPerson(_:names:)``).
    @Published private(set) var contacts: [ContactRow] = []

    /// Nicknames by node id in hexadecimal.
    @Published private(set) var names: [String: String]

    /// The node id (hexadecimal) of the person who just added this device by scanning its code, or `nil`.
    @Published private(set) var introduced: String?

    private let nicknames: NicknameStore
    private let pending: PendingCards
    private let backendProvider: @MainActor () -> (any ContactsBackend)?
    private let ownNodeIdHex: @MainActor () -> String?
    private let saveCardText: @MainActor (String) -> Void
    /// How an introduction is sent. `nil` (the tests, a model without a node) sends nothing. Set after `init`, and not
    /// passed to it, because an initialiser parameter that is an `async` or `@MainActor` closure (with a default
    /// value) is rejected by the compiler ("default argument cannot be both main actor-isolated and actor-isolated").
    var sendIntroduction: (@MainActor (Data) async -> Void)?

    /// The clock of the pairing window; the tests replace it.
    var now: @MainActor () -> Date = { Date() }

    // The person's own QR code is on screen, or was a moment ago: introductions are accepted (see ``setPairingScreen(open:)``).
    private var pairingOpen = false
    private var pairingUntil = Date.distantPast
    private var observation: AnyCancellable?
    private var refreshTask: Task<Void, Never>?
    private var refreshAgain = false

    init(
        nicknames: NicknameStore,
        pending: PendingCards,
        backend: @escaping @MainActor () -> (any ContactsBackend)?,
        ownNodeIdHex: @escaping @MainActor () -> String? = { nil },
        saveCardText: @escaping @MainActor (String) -> Void = { _ in }
    ) {
        self.nicknames = nicknames
        self.pending = pending
        backendProvider = backend
        self.ownNodeIdHex = ownNodeIdHex
        self.saveCardText = saveCardText
        names = nicknames.loadAll()
    }

    // MARK: Observing the node

    /// Reads the contacts again whenever `changes` fires (a burst of changes becomes one read). Called once, at launch.
    func observe(_ changes: AnyPublisher<Void, Never>) {
        observation = changes
            .debounce(for: .milliseconds(80), scheduler: DispatchQueue.main)
            .sink { [weak self] in
                guard let self else { return }
                Task { await self.refresh() }
            }
    }

    /// Reads the contacts again whenever the node of `mesh` starts, stops or its data changes.
    func observe(_ mesh: BleLinkTestModel) {
        observe(
            mesh.$running.map { _ in () }
                .merge(with: mesh.$dataVersion.map { _ in () })
                .eraseToAnyPublisher()
        )
    }

    /// Reads the node's contacts into ``contacts``. Without a running node the list is left as it was and `ready` is
    /// `false`. Reads that overlap are merged: a call that arrives while a read runs makes it read once more when it
    /// is done, and **returns only when that read is finished**, so the list is current for whoever asked.
    func refresh() async {
        if let running = refreshTask {
            refreshAgain = true
            await running.value
            return
        }
        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            repeat {
                self.refreshAgain = false
                await self.readOnce()
            } while self.refreshAgain
            self.refreshTask = nil
        }
        refreshTask = task
        await task.value
    }

    private func readOnce() async {
        guard let backend = backendProvider() else {
            ready = false
            return
        }
        ready = true
        saveCardText(encodeCardText(card: backend.contactCard))
        await addPendingCards(to: backend)
        do {
            let infos = try await backend.contacts(limit: Self.contactLimit)
            contacts = ContactBook.rows(from: infos, names: names)
        } catch {
            // A failed read leaves the previous list on screen; the next change tries again.
        }
    }

    /// Adds the cards that were scanned while the node was not running.
    private func addPendingCards(to backend: any ContactsBackend) async {
        for text in pending.all() {
            guard let card = decodeCardText(text: text) else {
                pending.remove(text)
                continue
            }
            do {
                let details = try await backend.addContact(card: card)
                pending.remove(text)
                await sendIntroduction?(details.nodeId)
            } catch {
                // A card the core refuses is dropped; any other failure is tried again next time.
                if CoreFailure(error) == .invalidContactCard { pending.remove(text) }
            }
        }
    }

    // MARK: Actions

    /// Adds the owner of `card` as a contact and remembers `nickname` for them (nothing, or a text that normalises to
    /// nothing, means no nickname). While the node is not running the card is kept and added later (``AddResult/queued``).
    func addContact(card: Data, nickname: String?) async -> AddResult {
        let details: CardDetails
        do {
            details = try inspectContactCard(card: card)
        } catch {
            return CoreFailure(error) == .invalidContactCard ? .invalid : .failed(detail: "\(error)")
        }
        let hex = details.nodeIdHex
        let backend = backendProvider()
        if hex == (backend?.nodeIdHex ?? ownNodeIdHex()) { return .ownCard }
        if let nickname { setNickname(hex: hex, raw: nickname) }

        guard let backend else {
            pending.add(encodeCardText(card: card))
            return .queued(hex: hex)
        }
        do {
            _ = try await backend.addContact(card: card)
            await refresh()
            await sendIntroduction?(details.nodeId)
            return .added(hex: hex)
        } catch {
            return CoreFailure(error) == .invalidContactCard ? .invalid : .failed(detail: "\(error)")
        }
    }

    // MARK: Introductions: one scan is enough

    /// The screen with this device's own QR code opened (`open`) or closed. While it is open, and for a while after, a
    /// person who scanned the code is added back automatically (``receive(introductionFrom:body:)``); otherwise strangers
    /// could add themselves to the contacts by sending introductions.
    func setPairingScreen(open: Bool) {
        pairingOpen = open
        if open {
            introduced = nil
        } else {
            pairingUntil = now().addingTimeInterval(Self.pairingGraceSeconds)
        }
    }

    private var acceptingIntroductions: Bool { pairingOpen || now() < pairingUntil }

    /// A private message arrived. When it is an introduction and the pairing window is open, the sender's card is added
    /// to the contacts (unless it is known already). Anything else is left alone.
    func receive(introductionFrom sender: Data, body: Data) async {
        guard acceptingIntroductions,
              let card = Introduction.parse(body, owner: sender),
              let backend = backendProvider()
        else { return }
        let known = (try? await backend.contacts(limit: Self.contactLimit))?
            .contains { $0.nodeId == sender && $0.card != nil } ?? false
        if known { return }
        guard (try? await backend.addContact(card: card)) != nil else { return }
        introduced = Hex.encode(sender)
        await refresh()
    }

    /// Changes the trust level of a contact. Needs the node, so it fails (`false`) while the mesh is off.
    func setTrust(hex: String, trust: TrustLevel) async -> Bool {
        guard let backend = backendProvider(), let nodeId = Hex.decode(hex), nodeId.count == 32 else { return false }
        do {
            try await backend.setTrust(nodeId: nodeId, trust: trust)
            await refresh()
            return true
        } catch {
            return false
        }
    }

    /// The identity was deleted: nothing of the old contacts is meaningful any more.
    func reset() {
        nicknames.clear()
        pending.clear()
        names = [:]
        contacts = []
    }

    /// Saves a nickname; an empty text removes it. The list is rebuilt, because a nickname can make a node a contact.
    func setNickname(hex: String, raw: String) {
        let saved = nicknames.set(hex, raw)
        names[hex] = saved
        Task { await refresh() }
    }
}
