import SwiftUI
#if os(macOS)
import AppKit
import UniformTypeIdentifiers
#endif

/// A QR code on a white tile: a code is read as dark on light, so it stays black on white in the dark themes too.
struct QrCodeView: View {
    private let image: CGImage?
    private let label: LocalizedStringKey

    init(text: String, label: LocalizedStringKey) {
        // The quiet zone is part of the picture, so the tile needs no padding of its own.
        image = QrCode.image(of: text, moduleSize: 6, quietZone: 4)
        self.label = label
    }

    var body: some View {
        if let image {
            Image(image, scale: 1, label: Text(label))
                .interpolation(.none)
                .resizable()
                .aspectRatio(1, contentMode: .fit)
                .frame(maxWidth: 360)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                .frame(maxWidth: .infinity)
        }
    }
}

/// The user's own contact card as a QR code, for the other person to scan, plus the same card as text to copy or share
/// when a camera is not an option. A card holds only public data: the public key and its signature. Anyone who has it
/// can write to you, which is why people meet to exchange it.
struct MyCardView: View {
    @ObservedObject private var prefs = UiPreferences.shared
    @ObservedObject private var contacts = ContactsModel.shared
    @State private var copied = false
    @State private var pictureNote: LocalizedStringKey?

    var body: some View {
        ScreenColumn {
            if let hex = contacts.introduced {
                InfoCard(title: "mycard_introduced_title", tone: .success) {
                    Text("mycard_introduced_body \(contacts.displayName(hex: hex, language: prefs.language))")
                }
            }
            if let card = prefs.cardText {
                InfoCard(title: "mycard_title") {
                    Text("mycard_body")
                    Text("mycard_pairing_hint")
                    QrCodeView(text: card, label: "mycard_qr_desc")
                    if let number = prefs.safetyNumber {
                        Text("add_safety_label").font(TypeScale.label)
                        Text(OnboardingFlow.formatSafetyNumber(number))
                            .font(TypeScale.monospace)
                            .textSelection(.enabled)
                    }
                }
                SecondaryButton("mycard_copy") {
                    Clipboard.copy(card)
                    copied = true
                }
                if copied { Text("mycard_copied").font(TypeScale.bodySmall) }
                ShareLink(item: card) { Text("mycard_share") }
                    .buttonStyle(CrisisButtonStyle(kind: .secondary))
                #if os(macOS)
                // The card as a picture, for a person who cannot hold a screen up to a camera: copy it or save it.
                SecondaryButton("mycard_copy_picture") { copyPicture(of: card) }
                SecondaryButton("mycard_save_picture") { savePicture(of: card) }
                if let pictureNote { Text(pictureNote).font(TypeScale.bodySmall) }
                #endif
            } else {
                InfoCard(title: "mycard_title", tone: .warning) { Text("mycard_unavailable") }
            }
        }
        .navigationTitle("mycard_title")
        .inlineNavigationTitle()
        // While this code is on screen, a person who scans it is added to the contacts here too (one scan is enough).
        .onAppear { contacts.setPairingScreen(open: true) }
        .onDisappear { contacts.setPairingScreen(open: false) }
    }

    #if os(macOS)
    private func copyPicture(of card: String) {
        guard let png = QrCode.pngData(of: card) else {
            pictureNote = "mycard_picture_failed"
            return
        }
        Clipboard.copyPng(png)
        pictureNote = "mycard_picture_copied"
    }

    /// The save panel is the system's (outside the app), so the app needs no access to the person's files beyond what
    /// they pick there (the sandbox entitlement `files.user-selected.read-write`).
    private func savePicture(of card: String) {
        guard let png = QrCode.pngData(of: card) else {
            pictureNote = "mycard_picture_failed"
            return
        }
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.png]
        panel.nameFieldStringValue = "crisis-network-card.png"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            try png.write(to: url, options: .atomic)
            pictureNote = "mycard_picture_saved"
        } catch {
            pictureNote = "mycard_picture_failed"
        }
    }
    #endif
}
