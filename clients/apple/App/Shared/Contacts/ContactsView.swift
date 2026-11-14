import CrisisCore
import SwiftUI

/// The screens a contact destination leads to. Pushed on the destination's own `NavigationStack`, so the back button and
/// the swipe from the edge are the platform's.
enum ContactRoute: Hashable {
    case myCard
    case scan
    case paste
    case detail(String)
}

extension TrustLevel {
    var label: LocalizedStringKey {
        switch self {
        case .unknown: return "trust_unknown"
        case .verified: return "trust_verified"
        case .blocked: return "trust_blocked"
        }
    }

    var tone: Tone {
        switch self {
        case .unknown: return .neutral
        case .verified: return .success
        case .blocked: return .error
        }
    }
}

extension ContactsModel {
    /// The name shown for a node, in the language chosen in the app: the nickname, or "Node 0123abcd".
    func displayName(hex: String, language: AppLanguage) -> String {
        ContactBook.displayName(
            hex: hex,
            names: names,
            unknownFormat: L10n.string("name_unknown_node %@", language: language)
        )
    }
}

/// The people this device knows, and the ways to add one: show your own card, scan theirs, or enter it as text.
struct ContactsView: View {
    @ObservedObject private var model = ContactsModel.shared
    @ObservedObject private var prefs = UiPreferences.shared
    @Environment(\.palette) private var palette

    var body: some View {
        ScreenColumn {
            actions
            if !model.ready { InfoCard(title: "mesh_off_title") { Text("contacts_mesh_off_body") } }
            if model.contacts.isEmpty {
                emptyNote
            } else {
                ForEach(model.contacts) { contact in
                    NavigationLink(value: ContactRoute.detail(contact.hex)) { row(contact) }
                        .buttonStyle(.plain)
                }
            }
        }
        .navigationTitle("nav_contacts")
        .task { await model.refresh() }
        .navigationDestination(for: ContactRoute.self) { route in
            switch route {
            case .myCard: MyCardView()
            case .scan:
                #if os(iOS)
                ScanCardView()
                #else
                // The Mac reads cards as text; its camera comes with the Mac-specific phase (I8).
                PasteCardView()
                #endif
            case .paste: PasteCardView()
            case let .detail(hex): ContactDetailView(hex: hex)
            }
        }
    }

    private var actions: some View {
        VStack(spacing: 8) {
            NavigationLink(value: ContactRoute.myCard) { Text("contacts_my_card") }
                .buttonStyle(CrisisButtonStyle(kind: .primary))
            #if os(iOS)
            NavigationLink(value: ContactRoute.scan) { Text("contacts_scan") }
                .buttonStyle(CrisisButtonStyle(kind: .secondary))
            #endif
            NavigationLink(value: ContactRoute.paste) { Text("contacts_paste") }
                .buttonStyle(CrisisButtonStyle(kind: .quiet))
        }
    }

    private var emptyNote: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("contacts_empty_title").font(TypeScale.titleSmall)
            Text("contacts_empty_body")
                .font(TypeScale.body)
                .foregroundStyle(palette.onSurfaceVariant.color)
        }
        .padding(.top, 8)
    }

    private func row(_ contact: ContactRow) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(model.displayName(hex: contact.hex, language: prefs.language))
                .font(TypeScale.titleSmall)
                .lineLimit(1)
            HStack(spacing: 8) {
                StatusPill(contact.trust.label, tone: contact.trust.tone)
                if !contact.hasCard { StatusPill("contact_no_card_pill", tone: .warning) }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .foregroundStyle(palette.onSurface.color)
        .background(palette.container.color, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}
