import CrisisCore
import CrisisRuntime
import SwiftUI

extension BleRadioState {
    /// The key of the words for the state of a radio ("on", "off", "not allowed", ...).
    var labelKey: LocalizedStringKey {
        switch self {
        case .poweredOn: return "net_radio_on"
        case .poweredOff: return "net_radio_off"
        case .unauthorized: return "net_radio_unauthorized"
        case .unsupported: return "net_radio_unsupported"
        case .resetting, .unknown: return "net_radio_unknown"
        }
    }
}

/// Opened from the settings and the home screen (phase I7): who this device is connected to, how full the message store
/// is, what the radio does and the counters of the node. For people who want to know why a message did not arrive, and
/// for the people who help them. Reads what the running node and the Bluetooth transport already publish: the links and
/// the radio once a second, the node's counters every two seconds while this screen is open.
struct NetworkView: View {
    @ObservedObject private var mesh = BleLinkTestModel.shared
    @ObservedObject private var prefs = UiPreferences.shared
    @Environment(\.palette) private var palette
    @State private var nodeStats: NodeStats?

    private var locale: Locale { prefs.language.locale }

    var body: some View {
        ScreenColumn {
            if mesh.running {
                neighboursCard
                storageCard
                if let radio = mesh.stats { radioCard(radio) }
                if let nodeStats { countersCards(nodeStats) }
            } else {
                InfoCard(title: "net_title", tone: .warning) { Text("net_not_running") }
            }
        }
        .navigationTitle("net_title")
        .inlineNavigationTitle()
        // Polls while the screen is on and the node runs; leaving the screen (or stopping the node) ends the loop.
        .task(id: mesh.running) { await pollNodeStats() }
    }

    private func pollNodeStats() async {
        guard mesh.running else {
            nodeStats = nil
            return
        }
        while !Task.isCancelled {
            if let runtime = mesh.activeRuntime { nodeStats = try? await runtime.stats() }
            try? await Task.sleep(nanoseconds: 2_000_000_000)
        }
    }

    // MARK: Neighbours

    private var neighboursCard: some View {
        InfoCard(title: "net_neighbours_title") {
            if mesh.links.isEmpty {
                Text("net_neighbours_none")
            } else {
                ForEach(Array(mesh.links.enumerated()), id: \.element.id) { index, link in
                    linkRow(index + 1, link)
                    if index < mesh.links.count - 1 { Divider() }
                }
            }
            if let stats = mesh.stats, stats.candidates > 0 {
                Text("net_candidates \(stats.candidates)").font(TypeScale.bodySmall)
            }
        }
    }

    /// One neighbour. The address is a random identifier of the system, so only its last characters are shown: enough to
    /// tell two apart.
    private func linkRow(_ number: Int, _ link: BleLinkInfo) -> some View {
        let metrics = link.metrics
        return VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Text("net_link_title \(number) \(String(link.address.suffix(5)))").font(TypeScale.label)
                StatusPill(link.active ? "net_link_active" : "net_link_connecting", tone: link.active ? .success : .neutral)
            }
            // A conditional between two literals would pick the verbatim `Text`: the key is looked up on purpose.
            Text(LocalizedStringKey(link.role == .client ? "net_link_role_client" : "net_link_role_server"))
                .font(TypeScale.bodySmall)
            Text("net_link_traffic \(ByteSizes.format(metrics.bytesReceived, locale: locale)) \(ByteSizes.format(metrics.bytesSent, locale: locale))")
                .font(TypeScale.bodySmall)
            Text("net_link_mtu \(link.payloadMtu)").font(TypeScale.bodySmall)
            if link.queuedBytes > 0 {
                Text("net_link_queued \(ByteSizes.format(Int64(link.queuedBytes), locale: locale))").font(TypeScale.bodySmall)
            }
            if metrics.packetsDropped > 0 {
                Text("net_link_dropped \(ByteSizes.count(metrics.packetsDropped, locale: locale))")
                    .font(TypeScale.bodySmall)
                    .foregroundStyle(palette.error.color)
            }
        }
        .accessibilityElement(children: .combine)
    }

    // MARK: Storage

    private var storageCard: some View {
        // The size the running node was opened with, not the one chosen since (that applies at the next start).
        let limit = StoreLimits.bytes(of: mesh.openedChoices?.storeLimitMb ?? StoreLimits.defaultMb)
        let used = nodeStats?.storedBytes ?? 0
        return InfoCard(title: "net_storage_title") {
            if let nodeStats {
                ProgressView(value: ByteSizes.fraction(used: used, limit: limit))
                    .accessibilityLabel("net_storage_title")
                    .accessibilityValue(Text("net_storage_used \(ByteSizes.format(used, locale: locale)) \(ByteSizes.format(limit, locale: locale))"))
                Text("net_storage_used \(ByteSizes.format(used, locale: locale)) \(ByteSizes.format(limit, locale: locale))")
                Text("net_storage_messages \(Int(clamping: nodeStats.storedMessages))").font(TypeScale.bodySmall)
            } else {
                Text("net_loading")
            }
            if mesh.settingsPending { Text("settings_pending").font(TypeScale.bodySmall) }
        }
    }

    // MARK: Radio

    private func radioCard(_ radio: BleStats) -> some View {
        InfoCard(title: "net_radio_title") {
            statusRow("net_radio_central") { Text(radio.centralState.labelKey) }
            statusRow("net_radio_peripheral") { Text(radio.peripheralState.labelKey) }
            statusRow("net_radio_scanning") { Text(LocalizedStringKey(radio.scanning ? "net_yes" : "net_no")) }
            statusRow("net_radio_advertising") { Text(LocalizedStringKey(radio.advertising ? "net_yes" : "net_no")) }
            statusRow("net_radio_connections") { Text("\(mesh.links.count) / \(radio.effectiveMaxLinks)") }
            statusRow("net_radio_backed_off") { Text("\(radio.backedOff)") }
            statusRow("net_radio_connect_failures") { Text(ByteSizes.count(radio.connectFailures, locale: locale)) }
        }
    }

    private func statusRow<Value: View>(_ key: LocalizedStringKey, @ViewBuilder value: () -> Value) -> some View {
        LabeledContent(key) { value() }.font(TypeScale.body)
    }

    // MARK: Counters

    @ViewBuilder
    private func countersCards(_ stats: NodeStats) -> some View {
        ForEach(NetworkBook.statGroups(stats)) { group in
            InfoCard(title: LocalizedStringKey(group.titleKey)) {
                ForEach(group.rows) { row in
                    LabeledContent(LocalizedStringKey(row.key)) { Text(ByteSizes.count(row.value, locale: locale)) }
                        .font(TypeScale.body)
                }
            }
        }
    }
}
