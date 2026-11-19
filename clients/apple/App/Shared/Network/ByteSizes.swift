import CrisisCore
import Foundation

/// Sizes and counters as people read them. Pure, so it can be tested. The same rules as the Android `ByteSizes`.
enum ByteSizes {
    private static let units = ["KB", "MB", "GB", "TB"]

    /// Just below 1024: a value that would be shown as "1024.0" moves up to the next unit.
    private static let unitStep = 1_023.95

    /// `512 B`, `1.5 KB`, `32 MB`: one decimal below 100, none above. Negative sizes count as zero.
    static func format(_ bytes: Int64, locale: Locale = .current) -> String {
        let value = max(bytes, 0)
        if value < 1_024 { return "\(value) B" }
        var size = Double(value) / 1_024
        var unit = 0
        while size >= unitStep, unit < units.count - 1 {
            size /= 1_024
            unit += 1
        }
        let format = size >= 100 ? "%.0f %@" : "%.1f %@"
        return String(format: format, locale: locale, size, units[unit])
    }

    static func format(_ bytes: UInt64, locale: Locale = .current) -> String {
        format(saturated(bytes), locale: locale)
    }

    /// How full the store is, from 0 to 1. A limit of zero counts as empty instead of dividing by it.
    static func fraction(used: UInt64, limit: UInt64) -> Double {
        limit == 0 ? 0 : min(max(Double(used) / Double(limit), 0), 1)
    }

    /// A counter with the digit grouping of the locale.
    static func count(_ value: UInt64, locale: Locale = .current) -> String {
        count(saturated(value), locale: locale)
    }

    static func count(_ value: Int64, locale: Locale = .current) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.locale = locale
        return formatter.string(from: NSNumber(value: value)) ?? "\(value)"
    }

    private static func saturated(_ value: UInt64) -> Int64 {
        value > UInt64(Int64.max) ? Int64.max : Int64(value)
    }
}

/// One counter of the node: the key of its name and its value.
struct StatRow: Equatable, Identifiable {
    let key: String
    let value: UInt64
    var id: String { key }
}

/// Counters that belong together.
struct StatGroup: Equatable, Identifiable {
    let titleKey: String
    let rows: [StatRow]
    var id: String { titleKey }
}

enum NetworkBook {
    /// The counters of the node in three groups: what moved, what was refused or dropped, and what makes delivery fail.
    /// The groups and the order are those of the Android network screen.
    static func statGroups(_ stats: NodeStats) -> [StatGroup] {
        [
            StatGroup(titleKey: "net_group_traffic", rows: [
                StatRow(key: "net_stat_packets_received", value: stats.packetsReceived),
                StatRow(key: "net_stat_packets_sent", value: stats.packetsSent),
                StatRow(key: "net_stat_frames_accepted", value: stats.framesAccepted),
                StatRow(key: "net_stat_duplicates", value: stats.duplicates),
                StatRow(key: "net_stat_acks_sent", value: stats.acksSent),
            ]),
            StatGroup(titleKey: "net_group_refused", rows: [
                StatRow(key: "net_stat_rate_limited", value: stats.rateLimited),
                StatRow(key: "net_stat_sender_rate_limited", value: stats.senderRateLimited),
                StatRow(key: "net_stat_malformed", value: stats.malformedPackets),
                StatRow(key: "net_stat_invalid_signatures", value: stats.invalidSignatures),
                StatRow(key: "net_stat_blocked", value: stats.blockedDropped),
                StatRow(key: "net_stat_unknown_peer", value: stats.unknownPeerPackets),
                StatRow(key: "net_stat_fragments_rejected", value: stats.fragmentsRejected),
                StatRow(key: "net_stat_undecryptable", value: stats.undecryptable),
                StatRow(key: "net_stat_tombstoned", value: stats.tombstoned),
                StatRow(key: "net_stat_quota_rejected", value: stats.quotaRejected),
            ]),
            StatGroup(titleKey: "net_group_trouble", rows: [
                StatRow(key: "net_stat_expired_transfers", value: stats.expiredTransfers),
                StatRow(key: "net_stat_want_gave_up", value: stats.wantGaveUp),
                StatRow(key: "net_stat_inflight_full", value: stats.inflightFull),
                StatRow(key: "net_stat_queue_full", value: stats.queueFull),
                StatRow(key: "net_stat_peers_banned", value: stats.peersBanned),
            ]),
        ]
    }
}
