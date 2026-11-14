#if os(iOS)
import SwiftUI

/// Reading a card from the camera (iOS). Asks for the camera only when the person presses the button, explains each
/// refusal and always offers the card as text instead. A code that is not a card is reported and the camera goes on.
struct ScanCardView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var camera = CameraAccess()
    @State private var card: Data?
    @State private var rejected = false

    var body: some View {
        ScreenColumn {
            if let card {
                AddContactForm(card: card, onDone: { dismiss() }, onCancel: { self.card = nil; rejected = false })
            } else {
                content
            }
        }
        .navigationTitle("scan_title")
        .inlineNavigationTitle()
        .onAppear { camera.refresh() }
    }

    @ViewBuilder
    private var content: some View {
        switch camera.readiness {
        case .ready:
            Text("scan_hint").font(TypeScale.body)
            QrScannerView { found in handle(found) }
                .frame(maxWidth: .infinity)
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .accessibilityLabel("scan_hint")
            if rejected { InfoCard(tone: .warning) { Text("scan_invalid") } }
            textInstead
        case .notDetermined:
            InfoCard(title: "scan_permission_title") { Text("scan_permission_body") }
            PrimaryButton("scan_allow") { camera.request() }
            textInstead
        case .denied, .restricted:
            InfoCard(title: "scan_permission_title", tone: .warning) { Text("scan_denied_body") }
            if camera.readiness == .denied { PrimaryButton("bt_open_settings") { camera.openSettings() } }
            textInstead
        case .unavailable:
            InfoCard(title: "scan_permission_title", tone: .warning) { Text("scan_no_camera") }
            textInstead
        }
    }

    private var textInstead: some View {
        NavigationLink(value: ContactRoute.paste) { Text("scan_use_text") }
            .buttonStyle(CrisisButtonStyle(kind: .quiet))
    }

    private func handle(_ found: String) {
        guard card == nil else { return }
        if let bytes = CardText.decode(found) {
            card = bytes
        } else {
            rejected = true
        }
    }
}
#endif
