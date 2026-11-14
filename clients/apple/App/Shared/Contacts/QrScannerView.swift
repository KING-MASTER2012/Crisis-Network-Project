#if os(iOS)
import AVFoundation
import SwiftUI
import UIKit

/// The camera picture with a QR code reader on it (iOS). `onCode` gets the text of every QR code the camera sees, on the
/// main thread, as often as the system reports it: the caller decides when it has had enough. The session runs while the
/// view exists. Nothing is recorded or sent anywhere.
struct QrScannerView: UIViewRepresentable {
    let onCode: (String) -> Void

    func makeUIView(context: Context) -> ScannerPreview {
        let view = ScannerPreview()
        view.reader.onCode = onCode
        view.reader.start()
        return view
    }

    func updateUIView(_ view: ScannerPreview, context: Context) {
        view.reader.onCode = onCode
    }

    static func dismantleUIView(_ view: ScannerPreview, coordinator: ()) {
        view.reader.stop()
    }
}

/// The capture session and its QR reader. Not tied to the main actor: `startRunning` and `stopRunning` block, so they
/// run on a queue of their own, and the system reports codes on the main queue (where `onCode` is called and set).
final class QrReader: NSObject, AVCaptureMetadataOutputObjectsDelegate, @unchecked Sendable {
    let session = AVCaptureSession()
    var onCode: ((String) -> Void)?

    private let queue = DispatchQueue(label: "org.crisisnetwork.qr-reader")
    private var configured = false

    func start() {
        queue.async { [self] in
            if !configured { configured = configure() }
            if configured, !session.isRunning { session.startRunning() }
        }
    }

    func stop() {
        queue.async { [self] in
            if session.isRunning { session.stopRunning() }
        }
    }

    private func configure() -> Bool {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input)
        else { return false }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return false }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        if output.availableMetadataObjectTypes.contains(.qr) {
            output.metadataObjectTypes = [.qr]
        }
        return true
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput objects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        for case let code as AVMetadataMachineReadableCodeObject in objects {
            if let text = code.stringValue {
                onCode?(text)
                return
            }
        }
    }
}

/// The view that shows what the camera sees.
final class ScannerPreview: UIView {
    let reader = QrReader()

    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    private var previewLayer: AVCaptureVideoPreviewLayer? {
        layer as? AVCaptureVideoPreviewLayer
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        previewLayer?.session = reader.session
        previewLayer?.videoGravity = .resizeAspectFill
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        // Only the picture follows the interface; the reader finds a code in any orientation.
        let angle: CGFloat
        switch window?.windowScene?.interfaceOrientation {
        case .portraitUpsideDown: angle = 270
        case .landscapeLeft: angle = 180
        case .landscapeRight: angle = 0
        default: angle = 90
        }
        if let connection = previewLayer?.connection, connection.isVideoRotationAngleSupported(angle) {
            connection.videoRotationAngle = angle
        }
    }
}
#endif
