import SwiftUI

/// The mood of a card or a pill. Every tone also has an icon, so that colour is never the only carrier.
enum Tone: Equatable, Sendable {
    case neutral
    case success
    case warning
    case error

    var systemImage: String {
        switch self {
        case .neutral: return "info.circle"
        case .success: return "checkmark.circle"
        case .warning: return "exclamationmark.triangle"
        case .error: return "xmark.octagon"
        }
    }

    func background(in palette: Palette) -> Swatch {
        switch self {
        case .neutral: return palette.container
        case .success: return palette.status.successContainer
        case .warning: return palette.status.warningContainer
        case .error: return palette.errorContainer
        }
    }

    func foreground(in palette: Palette) -> Swatch {
        switch self {
        case .neutral: return palette.onSurface
        case .success: return palette.status.onSuccessContainer
        case .warning: return palette.status.onWarningContainer
        case .error: return palette.onErrorContainer
        }
    }
}

/// The frame of a screen: scrolls, is as wide as a phone (640 pt at most, centred on a larger screen) and spaces its
/// children evenly.
struct ScreenColumn<Content: View>: View {
    @Environment(\.palette) private var palette
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) { content }
                .frame(maxWidth: 640, alignment: .leading)
                .padding(16)
                .frame(maxWidth: .infinity)
        }
        .background(palette.background.color)
    }
}

struct ScreenTitle: View {
    private let key: LocalizedStringKey

    init(_ key: LocalizedStringKey) {
        self.key = key
    }

    var body: some View {
        Text(key)
            .font(TypeScale.headline)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A rounded panel with an optional title. The text colour of the tone is set for everything inside.
struct InfoCard<Content: View>: View {
    @Environment(\.palette) private var palette
    private let title: LocalizedStringKey?
    private let tone: Tone
    private let content: Content

    init(title: LocalizedStringKey? = nil, tone: Tone = .neutral, @ViewBuilder content: () -> Content) {
        self.title = title
        self.tone = tone
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title {
                HStack(spacing: 8) {
                    Image(systemName: tone.systemImage).accessibilityHidden(true)
                    Text(title).font(TypeScale.titleSmall).accessibilityAddTraits(.isHeader)
                }
            }
            content
        }
        .font(TypeScale.body)
        .foregroundStyle(tone.foreground(in: palette).color)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(tone.background(in: palette).color, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .contain)
    }
}

/// A short status in a capsule, with the icon of its tone.
struct StatusPill: View {
    @Environment(\.palette) private var palette
    private let text: LocalizedStringKey
    private let tone: Tone

    init(_ text: LocalizedStringKey, tone: Tone) {
        self.text = text
        self.tone = tone
    }

    var body: some View {
        Label(text, systemImage: tone.systemImage)
            .font(TypeScale.label)
            .foregroundStyle(tone.foreground(in: palette).color)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(tone.background(in: palette).color, in: Capsule())
    }
}

/// The three kinds of button of the app: filled for the main action of a screen, outlined for the others, plain text
/// for what may be skipped. All are at least 48 pt high and as wide as their container.
struct CrisisButtonStyle: ButtonStyle {
    enum Kind { case primary, secondary, quiet }

    let kind: Kind
    @Environment(\.palette) private var palette
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        let shape = RoundedRectangle(cornerRadius: 12, style: .continuous)
        configuration.label
            .font(TypeScale.titleSmall)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, minHeight: 48)
            .padding(.horizontal, 16)
            .foregroundStyle(foreground.color)
            .background(background, in: shape)
            .overlay(shape.stroke(kind == .secondary ? palette.outline.color : Color.clear, lineWidth: 1.5))
            .opacity(isEnabled ? (configuration.isPressed ? 0.8 : 1) : 0.4)
            .contentShape(shape)
    }

    private var foreground: Swatch {
        switch kind {
        case .primary: return palette.onPrimary
        case .secondary, .quiet: return palette.primary
        }
    }

    private var background: Color {
        switch kind {
        case .primary: return palette.primary.color
        case .secondary, .quiet: return Color.clear
        }
    }
}

struct PrimaryButton: View {
    private let title: LocalizedStringKey
    private let action: () -> Void

    init(_ title: LocalizedStringKey, action: @escaping () -> Void) {
        self.title = title
        self.action = action
    }

    var body: some View {
        Button(action: action) { Text(title) }.buttonStyle(CrisisButtonStyle(kind: .primary))
    }
}

struct SecondaryButton: View {
    private let title: LocalizedStringKey
    private let action: () -> Void

    init(_ title: LocalizedStringKey, action: @escaping () -> Void) {
        self.title = title
        self.action = action
    }

    var body: some View {
        Button(action: action) { Text(title) }.buttonStyle(CrisisButtonStyle(kind: .secondary))
    }
}

struct QuietButton: View {
    private let title: LocalizedStringKey
    private let action: () -> Void

    init(_ title: LocalizedStringKey, action: @escaping () -> Void) {
        self.title = title
        self.action = action
    }

    var body: some View {
        Button(action: action) { Text(title) }.buttonStyle(CrisisButtonStyle(kind: .quiet))
    }
}

/// What a screen shows when it has nothing to show yet.
struct EmptyState: View {
    @Environment(\.palette) private var palette
    private let systemImage: String
    private let title: LocalizedStringKey
    private let message: LocalizedStringKey

    init(systemImage: String, title: LocalizedStringKey, message: LocalizedStringKey) {
        self.systemImage = systemImage
        self.title = title
        self.message = message
    }

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 44))
                .foregroundStyle(palette.secondary.color)
                .accessibilityHidden(true)
            Text(title).font(TypeScale.title).multilineTextAlignment(.center)
            Text(message).font(TypeScale.body).multilineTextAlignment(.center).foregroundStyle(palette.onSurfaceVariant.color)
        }
        .frame(maxWidth: 480)
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(palette.background.color)
        .accessibilityElement(children: .combine)
    }
}

extension View {
    /// An inline navigation title on iOS (the bar of the onboarding steps), nothing on the Mac.
    @ViewBuilder
    func inlineNavigationTitle() -> some View {
        #if os(iOS)
        navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }
}
