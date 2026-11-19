#if os(macOS)
import AppKit
import AVFoundation
import CoreImage
import SwiftUI

/// The camera picture with a QR code reader on it (macOS). Same contract as the iOS `QrScannerView`: `onCode` gets the
/// text of every QR code the camera sees, on the main thread, as often as it is found; the caller decides when it has
/// had enough; the session runs while the view exists; nothing is recorded or sent anywhere.
///
/// The iOS reader asks the system for QR metadata (`AVCaptureMetadataOutput`). A Mac's cameras (built-in, USB,
/// Continuity Camera) do not all offer that, so this one takes the frames itself and gives them to the same CoreImage
/// detector that reads pictures (``QrCode/read(_:)``), at most four times a second.
struct MacQrScannerView: NSViewRepresentable {
    let onCode: (String) -> Void

    func makeNSView(context: Context) -> MacScannerPreview {
        let view = MacScannerPreview()
        view.reader.onCode = onCode
        view.reader.start()
        return view
    }

    func updateNSView(_ view: MacScannerPreview, context: Context) {
        view.reader.onCode = onCode
    }

    static func dismantleNSView(_ view: MacScannerPreview, coordinator: ()) {
        view.reader.stop()
    }
}

/// The capture session and its frame reader. `startRunning` and `stopRunning` block, so they run on a queue of their
/// own; frames arrive on another one; `onCode` is called on the main queue.
final class MacQrReader: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    let session = AVCaptureSession()
    var onCode: ((String) -> Void)?

    /// The shortest time between two looks at a frame.
    private static let minInterval = 0.25

    private let control = DispatchQueue(label: "org.crisisnetwork.qr-reader.control")
    private let frames = DispatchQueue(label: "org.crisisnetwork.qr-reader.frames")
    private var configured = false
    private var lastLook = 0.0

    func start() {
        control.async { [self] in
            if !configured { configured = configure() }
            if configured, !session.isRunning { session.startRunning() }
        }
    }

    func stop() {
        control.async { [self] in
            if session.isRunning { session.stopRunning() }
        }
    }

    private func configure() -> Bool {
        guard let device = CameraAccess.captureDevice(),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input)
        else { return false }
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.addInput(input)
        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: frames)
        guard session.canAddOutput(output) else { return false }
        session.addOutput(output)
        return true
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        let now = ProcessInfo.processInfo.systemUptime
        guard now - lastLook >= Self.minInterval else { return }
        lastLook = now
        guard let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        guard let text = QrCode.read(CIImage(cvPixelBuffer: buffer)) else { return }
        DispatchQueue.main.async { [weak self] in self?.onCode?(text) }
    }
}

/// The view that shows what the camera sees.
final class MacScannerPreview: NSView {
    let reader = MacQrReader()
    private let preview = AVCaptureVideoPreviewLayer()

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        preview.session = reader.session
        preview.videoGravity = .resizeAspectFill
        layer?.addSublayer(preview)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override func layout() {
        super.layout()
        preview.frame = bounds
    }
}
#endif
