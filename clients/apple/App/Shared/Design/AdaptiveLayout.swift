import SwiftUI

/// Layout helpers for large text (phase I9). The text styles of the app already grow with the system's text size; what
/// does not grow by itself is a fixed width, a one-line limit and a row that has no room for large text. At the
/// accessibility sizes (the five largest) a row becomes a column, a name may take several lines, and a button is as wide
/// as its container.

/// A row of views that is a column at the accessibility text sizes (a text field with its button beside it, for
/// example). The views are lined up at the bottom in a row, at the leading edge in a column.
struct AdaptiveStack<Content: View>: View {
    @Environment(\.dynamicTypeSize) private var typeSize
    private let spacing: CGFloat
    private let content: Content

    init(spacing: CGFloat = 8, @ViewBuilder content: () -> Content) {
        self.spacing = spacing
        self.content = content()
    }

    var body: some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: spacing))
            : AnyLayout(HStackLayout(alignment: .bottom, spacing: spacing))
        layout { content }
    }
}

/// `lineLimit(lines)` for normal text sizes, no limit for the accessibility sizes: a name that a person with large text
/// reads must not end in "…".
private struct LineLimitUnlessLargeText: ViewModifier {
    @Environment(\.dynamicTypeSize) private var typeSize
    let lines: Int

    func body(content: Content) -> some View {
        content.lineLimit(typeSize.isAccessibilitySize ? nil : lines)
    }
}

/// A width that grows with the text size and is given up (the view is as wide as it can be) at the accessibility sizes.
private struct ScaledFixedWidth: ViewModifier {
    @Environment(\.dynamicTypeSize) private var typeSize
    /// 1 at the default text size, larger with larger text: the width is `base` times it.
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1
    let base: CGFloat

    func body(content: Content) -> some View {
        content.frame(width: typeSize.isAccessibilitySize ? nil : base * scale)
    }
}

extension View {
    /// At most `lines` lines, unless the text size is one of the accessibility sizes.
    func lineLimit(unlessLargeText lines: Int) -> some View {
        modifier(LineLimitUnlessLargeText(lines: lines))
    }

    /// `base` points wide at the default text size, wider with larger text, unconstrained at the accessibility sizes.
    func scaledWidth(_ base: CGFloat) -> some View {
        modifier(ScaledFixedWidth(base: base))
    }
}
