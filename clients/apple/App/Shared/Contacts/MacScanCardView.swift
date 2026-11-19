#if os(macOS)
import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// Reading a card on a Mac (phase I8): the camera when there is one and it is allowed, and always the alternatives that
/// need none: a **picture file** (a screenshot, a photo of the card), a **picture from the clipboard**, a picture
/// **dropped** on the window, and the card as text. A code that is not a card, or a picture without a code, is told in
/// words and the person can try again.
struct MacScanCardView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette
    @StateObject private var camera = CameraAccess()
    @State private var card: Data?
    @State private var problemKey: String?
    @State private var choosingFile = false
    @State private var dropTargeted = false

    var body: some View {
        ScreenColumn {
            if let card {
                AddContactForm(card: card, onDone: { dismiss() }, onCancel: { self.card = nil; problemKey = nil })
            } else {
                content
            }
        }
        .navigationTitle("scan_title")
        .onAppear { camera.refresh() }
        .fileImporter(isPresented: $choosingFile, allowedContentTypes: [.image]) { result in
            guard case let .success(url) = result else { return }
            take(QrImageImport.outcome(forFileAt: url))
        }
        .dropDestination(for: URL.self) { urls, _ in
            guard let url = urls.first else { return false }
            take(QrImageImport.outcome(forFileAt: url))
            return true
        } isTargeted: { dropTargeted = $0 }
    }

    @ViewBuilder
    private var content: some View {
        cameraPart
        picturePart
        if let problemKey {
            InfoCard(tone: .warning) { Text(LocalizedStringKey(problemKey)) }
        }
        NavigationLink(value: ContactRoute.paste) { Text("scan_use_text") }
            .buttonStyle(CrisisButtonStyle(kind: .quiet))
    }

    @ViewBuilder
    private var cameraPart: some View {
        switch camera.readiness {
        case .ready:
            Text("scan_hint").font(TypeScale.body)
            MacQrScannerView { found in handleCamera(found) }
                .frame(maxWidth: 480)
                .aspectRatio(4.0 / 3.0, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .frame(maxWidth: .infinity)
                .accessibilityLabel("scan_hint")
        case .notDetermined:
            InfoCard(title: "scan_permission_title") { Text("scan_permission_body") }
            PrimaryButton("scan_allow") { camera.request() }
        case .denied, .restricted:
            InfoCard(title: "scan_permission_title", tone: .warning) { Text("scan_denied_body") }
            if camera.readiness == .denied { PrimaryButton("bt_open_settings") { camera.openSettings() } }
        case .unavailable:
            InfoCard(title: "scan_permission_title", tone: .warning) { Text("scan_mac_no_camera") }
        }
    }

    /// The ways that need no camera.
    private var picturePart: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("scan_mac_picture_title").font(TypeScale.titleSmall)
            Text("scan_mac_picture_hint")
                .font(TypeScale.body)
                .foregroundStyle(palette.onSurfaceVariant.color)
            SecondaryButton("scan_mac_choose") { choosingFile = true }
            SecondaryButton("scan_mac_paste") { pastePicture() }
            Text("scan_mac_drop")
                .font(TypeScale.bodySmall)
                .foregroundStyle(palette.onSurfaceVariant.color)
                .frame(maxWidth: .infinity, minHeight: 56)
                .overlay {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(
                            dropTargeted ? palette.primary.color : palette.onSurfaceVariant.color.opacity(0.4),
                            style: StrokeStyle(lineWidth: dropTargeted ? 2 : 1, dash: [6, 4])
                        )
                }
        }
    }

    private func handleCamera(_ found: String) {
        guard card == nil else { return }
        if let bytes = CardText.decode(found) {
            card = bytes
            problemKey = nil
        } else {
            problemKey = "scan_invalid"
        }
    }

    private func pastePicture() {
        guard let data = Clipboard.imageData() else {
            problemKey = "scan_mac_clipboard_empty"
            return
        }
        take(QrImageImport.outcome(forImageData: data))
    }

    private func take(_ outcome: QrImageImport.Outcome) {
        if case let .card(bytes) = outcome {
            card = bytes
            problemKey = nil
        } else {
            problemKey = outcome.problemKey
        }
    }
}
#endif
