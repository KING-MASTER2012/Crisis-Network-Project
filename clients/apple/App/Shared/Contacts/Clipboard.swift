import Foundation
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// The system clipboard, for the card as text.
enum Clipboard {
    /// The text on the clipboard, `nil` when there is none.
    static func text() -> String? {
        #if os(iOS)
        return UIPasteboard.general.string
        #else
        return NSPasteboard.general.string(forType: .string)
        #endif
    }

    static func copy(_ text: String) {
        #if os(iOS)
        UIPasteboard.general.string = text
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #endif
    }
}
