import CrisisCore
import SwiftUI

extension AnnouncementSeverity {
    var label: LocalizedStringKey {
        switch self {
        case .normal: return "sev_normal"
        case .important: return "sev_important"
        case .emergency: return "sev_emergency"
        }
    }

    var tone: Tone {
        switch self {
        case .normal: return .neutral
        case .important: return .warning
        case .emergency: return .emergency
        }
    }
}

extension AnnouncementSeverity {
    /// The key of the label, for the places that look a string up by hand (an alert).
    var labelKey: String {
        switch self {
        case .normal: return "sev_normal"
        case .important: return "sev_important"
        case .emergency: return "sev_emergency"
        }
    }
}

extension SendResult {
    /// The words for a message that was not sent.
    var messageKey: LocalizedStringKey {
        switch self {
        case .sent, .failed: return "send_err_failed"
        case .empty: return "send_err_empty"
        case .tooLong: return "bytes_over"
        case .meshOff: return "send_err_mesh_off"
        case .unknownContact: return "send_err_unknown_contact"
        case .rejected: return "send_err_rejected"
        }
    }
}

/// A scrolling screen like ``ScreenColumn``, but lazy: a list of hundreds of messages is only drawn as far as it shows.
struct LazyScreenColumn<Content: View>: View {
    @Environment(\.palette) private var palette
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 8) { content }
                .frame(maxWidth: 640, alignment: .leading)
                .padding(16)
                .frame(maxWidth: .infinity)
        }
        .background(palette.background.color)
    }
}

extension DeliveryStatus {
    var label: LocalizedStringKey {
        switch self {
        case .published: return "status_published"
        case .delivered: return "status_delivered"
        }
    }
}

/// Shown while the node is closed: messages can only be read and sent while the device is part of the mesh.
struct MeshOffNotice: View {
    var body: some View {
        InfoCard(title: "mesh_off_title", tone: .warning) { Text("mesh_off_body") }
    }
}

/// The time of a message: only the time for today, the date and the time for earlier days, in the language of the app.
struct TimeLabel: View {
    let ms: UInt64

    var body: some View {
        if ms > 0 {
            let date = Date(timeIntervalSince1970: Double(ms) / 1000)
            if MessageBook.isToday(ms, now: UInt64(Date().timeIntervalSince1970 * 1000)) {
                Text(date, format: Date.FormatStyle(date: .omitted, time: .shortened))
            } else {
                Text(date, format: Date.FormatStyle(date: .abbreviated, time: .shortened))
            }
        }
    }
}

/// A lifetime in the largest unit that divides it evenly: "15 minutes", "1 day".
struct DurationLabel: View {
    let secs: Int64

    var body: some View {
        let parts = splitLifetime(secs: secs)
        let amount = Int(clamping: parts.value)
        switch parts.unit {
        case .seconds: Text("duration_seconds \(amount)")
        case .minutes: Text("duration_minutes \(amount)")
        case .hours: Text("duration_hours \(amount)")
        case .days: Text("duration_days \(amount)")
        }
    }
}

/// The line above the first message that was unread when a conversation was opened.
struct UnreadDivider: View {
    @Environment(\.palette) private var palette

    var body: some View {
        HStack(spacing: 8) {
            Rectangle().fill(palette.outlineVariant.color).frame(height: 1)
            Text("unread_divider").font(TypeScale.bodySmall).foregroundStyle(palette.primary.color)
            Rectangle().fill(palette.outlineVariant.color).frame(height: 1)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

/// "12 of 1000 bytes", and the word that says it is too long: the box counts UTF-8 bytes, not characters.
struct ByteCounter: View {
    let count: ByteCount
    @Environment(\.palette) private var palette

    var body: some View {
        HStack(spacing: 4) {
            Text("bytes_counter \(Int(count.used)) \(Int(count.limit))")
            if count.over { Text(verbatim: "·"); Text("bytes_over") }
        }
        .font(TypeScale.bodySmall)
        .foregroundStyle(count.over ? palette.error.color : palette.onSurfaceVariant.color)
    }
}

/// The text of a message: selectable, or an explanation when it cannot be shown.
struct MessageText: View {
    let text: String?

    var body: some View {
        if let text {
            Text(text).font(TypeScale.bodyLarge).textSelection(.enabled)
        } else {
            Text("msg_unreadable").font(TypeScale.body).italic()
        }
    }
}

/// One message of a chat: mine at the end in the accent colour, theirs at the start. The state of a sent message is
/// written out ("Delivered"), never only coloured.
struct MessageBubble: View {
    let message: MessageRow
    @Environment(\.palette) private var palette

    var body: some View {
        let outgoing = message.outgoing
        HStack {
            if outgoing { Spacer(minLength: 40) }
            VStack(alignment: .leading, spacing: 4) {
                MessageText(text: message.text)
                HStack(spacing: 6) {
                    TimeLabel(ms: message.timeMs)
                    if let status = message.status { Text(verbatim: "·"); Text(status.label) }
                }
                .font(TypeScale.bodySmall)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .foregroundStyle((outgoing ? palette.onPrimaryContainer : palette.onSurface).color)
            .background(
                (outgoing ? palette.primaryContainer : palette.container).color,
                in: RoundedRectangle(cornerRadius: 16, style: .continuous)
            )
            .frame(maxWidth: 480, alignment: outgoing ? .trailing : .leading)
            // One item for a screen reader: who it is from, the text, the time and the state, not a stop per line.
            .accessibilityElement(children: .combine)
            .accessibilityHint(outgoing ? Text("a11y_message_outgoing") : Text("a11y_message_incoming"))
            if !outgoing { Spacer(minLength: 40) }
        }
        .frame(maxWidth: .infinity, alignment: outgoing ? .trailing : .leading)
    }
}
