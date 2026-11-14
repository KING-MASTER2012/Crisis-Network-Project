import SwiftUI

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
    @State private var copied = false

    var body: some View {
        ScreenColumn {
            if let card = prefs.cardText {
                InfoCard(title: "mycard_title") {
                    Text("mycard_body")
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
            } else {
                InfoCard(title: "mycard_title", tone: .warning) { Text("mycard_unavailable") }
            }
        }
        .navigationTitle("mycard_title")
        .inlineNavigationTitle()
    }
}
