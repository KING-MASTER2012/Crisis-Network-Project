import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Reading a contact card from a **picture** of its QR code (phase I8): a file the person chooses or drops, a picture on
/// the clipboard (a screenshot of the other person's card, sent over some other channel). It is the Mac's alternative
/// to the camera: a Mac may have no usable camera, and the camera is a permission some people refuse.
///
/// Nothing here is Mac-only (ImageIO and CoreImage exist everywhere), so the rules are tested on the simulator. The
/// picture comes from outside, so it is bounded before it is decoded: a file over ``maxFileBytes`` is refused, and a
/// picture with more than ``maxPixels`` on a side is decoded at that size, not bigger.
enum QrImageImport {
    /// The largest file read: a screenshot of a whole display is a few MB; 20 MB is generous.
    static let maxFileBytes = 20 * 1024 * 1024

    /// The longest side a picture is decoded at. A QR code of a card is 49 modules wide, so this leaves 80 pixels per
    /// module even when the code fills a tenth of the picture.
    static let maxPixels = 4096

    enum Outcome: Equatable, Sendable {
        /// A QR code with a contact card in it: the card's bytes.
        case card(Data)

        /// A QR code, but not one of ours.
        case notACard

        /// A picture without a QR code in it.
        case noQrCode

        /// Not a picture, or one that cannot be decoded.
        case unreadable

        /// Over ``QrImageImport/maxFileBytes``.
        case tooLarge

        /// The key of the sentence that tells the person what went wrong; `nil` for a card.
        var problemKey: String? {
            switch self {
            case .card: return nil
            case .notACard: return "scan_invalid"
            case .noQrCode: return "scan_image_no_qr"
            case .unreadable: return "scan_image_unreadable"
            case .tooLarge: return "scan_image_too_large"
            }
        }
    }

    /// What the picture in `data` (any format ImageIO reads) holds.
    static func outcome(forImageData data: Data) -> Outcome {
        guard data.count <= maxFileBytes else { return .tooLarge }
        guard let image = decode(data) else { return .unreadable }
        guard let text = QrCode.read(image) else { return .noQrCode }
        if let card = CardText.decode(text) { return .card(card) }
        return .notACard
    }

    /// What the picture in the file at `url` holds. A file the person chose in a panel or dropped on the window is
    /// outside the sandbox: its access is opened for the time of the read.
    static func outcome(forFileAt url: URL) -> Outcome {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        if let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > maxFileBytes { return .tooLarge }
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { return .unreadable }
        return outcome(forImageData: data)
    }

    /// The picture, at its own size, or at ``maxPixels`` when it is bigger. `nil` when the data is no picture.
    static func decode(_ data: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil), CGImageSourceGetCount(source) > 0 else { return nil }
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        let width = (properties?[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue ?? 0
        let height = (properties?[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue ?? 0
        guard width > 0, height > 0 else { return nil }
        if max(width, height) <= maxPixels {
            return CGImageSourceCreateImageAtIndex(source, 0, nil)
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixels,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    /// `image` as PNG data, `nil` when it cannot be encoded.
    static func pngData(of image: CGImage) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }
}

extension QrCode {
    /// The QR code of `text` as a PNG file, for saving or copying, `nil` when it cannot be drawn.
    static func pngData(of text: String, moduleSize: Int = 8, quietZone: Int = 4) -> Data? {
        guard let image = image(of: text, moduleSize: moduleSize, quietZone: quietZone) else { return nil }
        return QrImageImport.pngData(of: image)
    }
}
