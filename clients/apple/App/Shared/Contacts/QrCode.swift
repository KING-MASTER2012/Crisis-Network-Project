import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation

/// QR codes without any library: CoreImage draws them and reads them. A contact card is tiny (171 characters), so the
/// system's own detector is enough, and the app gains nothing to ship or to audit. The Android client does the same
/// with ZXing.
enum QrCode {
    // A CIContext is thread-safe by Apple's documentation, but is not marked `Sendable`.
    private nonisolated(unsafe) static let context = CIContext()

    /// `text` as a picture ready to show or to read back: every module is `moduleSize` pixels, with `quietZone` modules
    /// of white around it (a reader needs the white; four modules is the standard).
    static func image(of text: String, moduleSize: Int = 8, quietZone: Int = 4) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "L"
        guard let output = filter.outputImage else { return nil }
        let scale = CGFloat(max(moduleSize, 1))
        let margin = CGFloat(max(quietZone, 0)) * scale
        // Nearest-neighbour sampling keeps the modules sharp; the default would blur their edges.
        let scaled = output.samplingNearest().transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let canvas = scaled.extent.insetBy(dx: -margin, dy: -margin)
        let white = CIImage(color: .white).cropped(to: canvas)
        return context.createCGImage(scaled.composited(over: white), from: canvas)
    }

    /// The text of the first QR code in `image`, `nil` when there is none.
    static func read(_ image: CGImage) -> String? {
        let detector = CIDetector(
            ofType: CIDetectorTypeQRCode,
            context: nil,
            options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]
        )
        let features = detector?.features(in: CIImage(cgImage: image)) ?? []
        for case let feature as CIQRCodeFeature in features {
            if let text = feature.messageString { return text }
        }
        return nil
    }
}
