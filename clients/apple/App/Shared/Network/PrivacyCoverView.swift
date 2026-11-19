import SwiftUI

/// What the app switcher shows instead of the messages: the app's own colours and name. Covers the whole window, and is
/// hidden from screen readers (it is only there while the app is not in use).
struct PrivacyCoverView: View {
    @Environment(\.palette) private var palette

    var body: some View {
        ZStack {
            palette.background.color.ignoresSafeArea()
            VStack(spacing: 12) {
                Image(systemName: "lock.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(palette.primary.color)
                Text("app_name")
                    .font(TypeScale.titleSmall)
                    .foregroundStyle(palette.onBackground.color)
            }
        }
        .accessibilityHidden(true)
    }
}
