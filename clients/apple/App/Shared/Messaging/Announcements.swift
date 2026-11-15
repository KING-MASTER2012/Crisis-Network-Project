import CrisisCore
import SwiftUI

enum AnnouncementRoute: Hashable {
    case compose
}

/// The public announcements this device holds, the newest first, and the button to write one. Important and emergency
/// announcements get their own colour, and always a word as well. While the screen is open the announcements count as
/// read.
struct AnnouncementsView: View {
    @ObservedObject private var messaging = MessagingModel.shared
    @ObservedObject private var contacts = ContactsModel.shared
    @ObservedObject private var prefs = UiPreferences.shared
    @Environment(\.palette) private var palette

    var body: some View {
        let feed = messaging.feed
        LazyScreenColumn {
            if !messaging.ready { MeshOffNotice() }
            NavigationLink(value: AnnouncementRoute.compose) { Text("ann_write") }
                .buttonStyle(CrisisButtonStyle(kind: .primary))
            if feed.loaded && feed.messages.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("announcements_empty_title").font(TypeScale.titleSmall)
                    Text("announcements_empty_body")
                        .font(TypeScale.body)
                        .foregroundStyle(palette.onSurfaceVariant.color)
                }
                .padding(.top, 8)
            }
            ForEach(feed.messages) { message in
                VStack(alignment: .leading, spacing: 8) {
                    card(message)
                    // The newest feed is read from the top: the line sits under the oldest message that was unread.
                    if message.seq == feed.firstUnreadSeq { UnreadDivider() }
                }
            }
            if feed.hasOlder {
                Text("loading_older")
                    .font(TypeScale.bodySmall)
                    .frame(maxWidth: .infinity)
                    .padding(8)
                    .onAppear { Task { await messaging.loadOlderFeed() } }
            }
        }
        .navigationTitle("nav_announcements")
        .onAppear { messaging.openFeed() }
        .onDisappear { messaging.closeFeed() }
        .navigationDestination(for: AnnouncementRoute.self) { _ in ComposeAnnouncementView() }
    }

    private func card(_ message: MessageRow) -> some View {
        // One item for a screen reader: the severity, the sender, the time, the text and the state are read together.
        InfoCard(tone: message.severity.tone) {
            HStack(spacing: 8) {
                if message.severity != .normal { StatusPill(message.severity.label, tone: message.severity.tone) }
                sender(message).font(TypeScale.label)
                Spacer(minLength: 0)
                TimeLabel(ms: message.timeMs).font(TypeScale.bodySmall)
            }
            MessageText(text: message.text)
            if let status = message.status { Text(status.label).font(TypeScale.bodySmall) }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func sender(_ message: MessageRow) -> some View {
        if let hex = message.senderHex {
            Text(contacts.displayName(hex: hex, language: prefs.language)).lineLimit(1)
        } else {
            Text("you")
        }
    }
}

/// Marks the "custom" row of the lifetime choice.
enum LifetimeChoice: Hashable {
    case preset(Int64)
    case custom
}

/// Writing a public announcement: the text (with a byte counter), how important it is, and how long devices keep it, from
/// one second up to the longest the core allows. Important and emergency announcements ask for a confirmation first,
/// because they cannot be taken back.
struct ComposeAnnouncementView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette
    @ObservedObject private var messaging = MessagingModel.shared
    @ObservedObject private var prefs = UiPreferences.shared

    @State private var text = ""
    @State private var severity = AnnouncementSeverity.normal
    @State private var choice: LifetimeChoice
    @State private var customValue = "1"
    @State private var customUnit = LifetimeUnit.hours
    @State private var error: LocalizedStringKey?
    @State private var confirm = false

    init() {
        // One day is preselected, or the longest lifetime there is when the core allows less.
        let limits = Limits.fromCore()
        let presets = lifetimePresets(maxSecs: limits.maxLifetimeSecs)
        let wanted = conventionInfo().defaultLifetimeSecs
        let initial = presets.contains(wanted) ? wanted : presets.last
        _choice = State(initialValue: initial.map(LifetimeChoice.preset) ?? .custom)
    }

    private var limits: Limits { messaging.limits }

    private var cleanText: String {
        sanitizeText(text: text).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var lifetimeSecs: Int64 {
        switch choice {
        case let .preset(secs): return secs
        case .custom: return lifetimeToSeconds(value: Int64(customValue) ?? 0, unit: customUnit)
        }
    }

    private var lifetimeValid: Bool {
        lifetimeIsValid(secs: lifetimeSecs, maxSecs: limits.maxLifetimeSecs)
    }

    private var count: ByteCount {
        let used = encodeAnnouncement(text: cleanText, severity: severity).count
        return budgetStatus(used: UInt32(clamping: used), limit: UInt32(clamping: limits.maxPayloadBytes))
    }

    private var canPublish: Bool {
        messaging.ready && !cleanText.isEmpty && !count.over && lifetimeValid
    }

    var body: some View {
        ScreenColumn {
            if !messaging.ready { MeshOffNotice() }
            InfoCard(tone: .warning) { Text("ann_public_note") }
            VStack(alignment: .leading, spacing: 4) {
                TextField("ann_field", text: $text, axis: .vertical)
                    .lineLimit(3...8)
                    .textFieldStyle(.roundedBorder)
                    .onChange(of: text) { _, _ in error = nil }
                ByteCounter(count: count)
            }

            Text("ann_severity_title").font(TypeScale.titleSmall).accessibilityAddTraits(.isHeader)
            Picker("ann_severity_title", selection: $severity) {
                ForEach([AnnouncementSeverity.normal, .important, .emergency], id: \.self) { level in
                    Text(level.label).tag(level)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            Text("ann_lifetime_title").font(TypeScale.titleSmall).accessibilityAddTraits(.isHeader)
            Text("ann_lifetime_hint").font(TypeScale.bodySmall)
            lifetimePicker
            if choice == .custom { customLifetime }

            if let error { Text(error).font(TypeScale.body).foregroundStyle(palette.error.color) }
            PrimaryButton("ann_publish") {
                if severity == .normal { publish() } else { confirm = true }
            }
            .disabled(!canPublish)
        }
        .navigationTitle("ann_compose_title")
        .inlineNavigationTitle()
        .alert(
            L10n.string("ann_confirm_title %@", language: prefs.language, L10n.string(severity.labelKey, language: prefs.language)),
            isPresented: $confirm
        ) {
            Button("ann_publish") { publish() }
            Button("add_cancel", role: .cancel) {}
        } message: {
            Text("ann_confirm_body")
        }
    }

    private var lifetimePicker: some View {
        Picker("ann_lifetime_title", selection: $choice) {
            ForEach(lifetimePresets(maxSecs: limits.maxLifetimeSecs), id: \.self) { secs in
                DurationLabel(secs: secs).tag(LifetimeChoice.preset(secs))
            }
            Text("lifetime_custom").tag(LifetimeChoice.custom)
        }
        .pickerStyle(.menu)
        .labelsHidden()
    }

    @ViewBuilder
    private var customLifetime: some View {
        TextField("lifetime_custom_value", text: $customValue)
            .textFieldStyle(.roundedBorder)
            #if os(iOS)
            .keyboardType(.numberPad)
            #endif
            .onChange(of: customValue) { _, value in
                let digits = String(value.filter(\.isNumber).prefix(9))
                if digits != value { customValue = digits }
            }
        Picker("ann_lifetime_title", selection: $customUnit) {
            Text("unit_seconds").tag(LifetimeUnit.seconds)
            Text("unit_minutes").tag(LifetimeUnit.minutes)
            Text("unit_hours").tag(LifetimeUnit.hours)
            Text("unit_days").tag(LifetimeUnit.days)
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        if !lifetimeValid {
            let longest = L10n.string("ann_lifetime_invalid %@", language: prefs.language, durationText(limits.maxLifetimeSecs))
            Text(verbatim: longest).font(TypeScale.body).foregroundStyle(palette.error.color)
        }
    }

    /// "15 minutes", "1 day" as a String, for the places that cannot show a `Text`.
    private func durationText(_ secs: Int64) -> String {
        let parts = splitLifetime(secs: secs)
        let amount = Int(clamping: parts.value)
        let key: String
        switch parts.unit {
        case .seconds: key = "duration_seconds %lld"
        case .minutes: key = "duration_minutes %lld"
        case .hours: key = "duration_hours %lld"
        case .days: key = "duration_days %lld"
        }
        // The plural forms of a key are expanded by the format, in the locale of the language chosen in the app.
        return L10n.string(key, language: prefs.language, amount)
    }

    private func publish() {
        let sending = cleanText
        Task {
            let result = await messaging.publishAnnouncement(text: sending, severity: severity, lifetimeSecs: lifetimeSecs)
            if result == .sent { dismiss() } else { error = result.messageKey }
        }
    }
}
