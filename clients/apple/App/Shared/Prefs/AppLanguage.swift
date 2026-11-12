import Foundation

/// The language of the interface. `system` follows the device; the others are fixed and take effect **at once**: the
/// views get the matching `locale` in their environment (see ``RootView``), so no restart is needed.
enum AppLanguage: String, CaseIterable, Identifiable, Sendable {
    case system
    case english
    case turkish

    var id: String { rawValue }

    /// The language code of a fixed choice (the name of the `.lproj` folder), `nil` for the system's.
    var code: String? {
        switch self {
        case .system: return nil
        case .english: return "en"
        case .turkish: return "tr"
        }
    }

    var locale: Locale {
        guard let code else { return Locale.autoupdatingCurrent }
        return Locale(identifier: code)
    }
}

/// Strings that are not shown by a `Text` (an alert built in code, an accessibility sentence put together from parts)
/// have to be looked up by hand, in the language that was chosen in the app and not in the one of the process.
enum L10n {
    /// The string table of `language` (the main bundle's for the system language or a language the app lacks).
    static func bundle(for language: AppLanguage, in main: Bundle = .main) -> Bundle {
        guard let code = language.code,
              let path = main.path(forResource: code, ofType: "lproj"),
              let bundle = Bundle(path: path)
        else { return main }
        return bundle
    }

    /// The text for `key` in `language`, with `%@`, `%lld` and so on filled in from `arguments`. The key itself when
    /// there is no translation, which makes a missing string easy to spot.
    static func string(_ key: String, language: AppLanguage, _ arguments: CVarArg..., in main: Bundle = .main) -> String {
        let format = bundle(for: language, in: main).localizedString(forKey: key, value: key, table: nil)
        guard !arguments.isEmpty else { return format }
        return String(format: format, locale: language.locale, arguments: arguments)
    }
}
