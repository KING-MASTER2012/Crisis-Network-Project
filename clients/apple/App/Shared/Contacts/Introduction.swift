import CrisisCore
import Foundation

/// The introduction: how one scan of a QR code is enough to become contacts of each other. The same rule as the Android
/// `IntroCodec`, so that the two clients understand each other.
///
/// A private message can only be written to a person whose contact card is known. When A shows the QR code and B scans
/// it, only B knows A's card. To save A from scanning B's code as well, B's device sends A a private message whose text
/// is B's own card in its text form (``CardText``). A's device recognises it, adds the card and never shows the message.
///
/// A message counts as an introduction only when the card inside belongs to its **sender** (the sender is authenticated
/// by the signature of the message), so nobody can introduce somebody else. Whether a received introduction is acted on
/// is decided by ``ContactsModel`` (only while the person's own code is on screen or was a moment ago).
enum Introduction {
    /// A card as text is 178 characters; anything much longer is an ordinary message.
    static let maxBytes = 400

    /// The body of the introduction that carries `card` (the 128 card bytes).
    static func encode(_ card: Data) -> Data {
        Data(CardText.encode(card).utf8)
    }

    /// A cheap test that rejects ordinary messages without calling the core: a short text that starts with the card
    /// prefix (the case of the prefix does not matter).
    static func looksLike(_ body: Data) -> Bool {
        guard !body.isEmpty, body.count <= maxBytes else { return false }
        let text = String(decoding: body, as: UTF8.self).drop(while: { $0.isWhitespace })
        return text.lowercased().hasPrefix("crisis:")
    }

    /// The card bytes when `body` is an introduction from `owner` (the card's node id is `owner`), otherwise `nil`.
    static func parse(_ body: Data, owner: Data) -> Data? {
        guard looksLike(body),
              let card = CardText.decode(String(decoding: body, as: UTF8.self)),
              let details = try? inspectContactCard(card: card),
              details.nodeId == owner
        else { return nil }
        return card
    }
}
