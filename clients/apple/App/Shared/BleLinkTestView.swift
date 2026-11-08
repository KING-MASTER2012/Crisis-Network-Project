import CrisisRuntime
import SwiftUI

/// The Bluetooth test bench of phase I2: start the node with the radio, watch the links, send test announcements and
/// count what arrives. Two devices (iPhone and Android, or two iPhones) in the foreground exchange packets; that is
/// the exit test of the phase. It needs real devices: the simulator has no Bluetooth.
struct BleLinkTestView: View {
    @StateObject private var model = BleLinkTestModel()

    var body: some View {
        NavigationStack {
            List {
                controlSection
                if model.running {
                    if let stats = model.stats { radioSection(stats) }
                    actionSection
                    linksSection
                    countersSection
                }
                logSection
            }
            .navigationTitle("Bluetooth")
        }
    }

    private var controlSection: some View {
        Section {
            Picker("Role", selection: $model.role) {
                ForEach(BleRole.allCases) { role in
                    Text(role.title).tag(role)
                }
            }
            .pickerStyle(.segmented)
            .disabled(model.running || model.busy)

            Button(model.running ? "Stop" : "Start") {
                Task {
                    if model.running {
                        await model.stop()
                    } else {
                        await model.start()
                    }
                }
            }
            .disabled(model.busy)

            if let error = model.errorText {
                Text(error).font(.footnote).foregroundStyle(.red)
            }
            if model.running {
                LabeledContent("Node", value: model.nodeId).font(.footnote)
            }
        } footer: {
            Text("Bluetooth works in the foreground only in this phase: keep the app open and the screen on. "
                + "Use Central on one device and Peripheral on the other to avoid two links between them; Both is what the product does.")
        }
    }

    private func radioSection(_ stats: BleStats) -> some View {
        Section("Radio") {
            if stats.role.runsCentral {
                LabeledContent("Central", value: "\(stats.centralState.title)\(stats.scanning ? ", scanning" : "")")
                LabeledContent("Seen, not linked", value: "\(stats.candidates)")
                LabeledContent("Connect attempts / failures", value: "\(stats.connectAttempts) / \(stats.connectFailures)")
            }
            if stats.role.runsPeripheral {
                LabeledContent("Peripheral", value: "\(stats.peripheralState.title)\(stats.advertising ? ", advertising" : "")")
                LabeledContent("Service", value: stats.serverReady ? "registered" : "not registered")
            }
            LabeledContent("Duplicate links closed", value: "\(stats.duplicateLinks)")
        }
        .font(.footnote)
    }

    private var actionSection: some View {
        Section("Test traffic") {
            Button("Send an announcement") { model.sendAnnouncement() }
            Button("Send a burst (10 x 1,000 B)") { model.sendBurst() }
            Button("Reset counters", role: .destructive) { model.resetCounters() }
        }
    }

    private var linksSection: some View {
        Section("Links (\(model.links.filter(\.active).count) usable)") {
            if model.links.isEmpty {
                Text("No link yet.").foregroundStyle(.secondary)
            }
            ForEach(model.links) { link in
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(link.role.rawValue) \(link.address.prefix(8))  \(link.active ? "up" : "setting up")")
                        .font(.headline)
                    Text("handle \(link.handle.map { String($0) } ?? "-"), packet size \(link.payloadMtu) B, queued \(link.queuedBytes) B")
                    Text("sent \(link.metrics.packetsSent) / \(link.metrics.bytesSent) B, received \(link.metrics.packetsReceived) / \(link.metrics.bytesReceived) B"
                        + (link.metrics.packetsDropped > 0 ? ", dropped \(link.metrics.packetsDropped)" : ""))
                    if let rate = link.metrics.rxBytesPerSecond {
                        Text(String(format: "receiving at %.1f KB/s", rate / 1000))
                    }
                }
                .font(.footnote)
                .accessibilityElement(children: .combine)
            }
        }
    }

    private var countersSection: some View {
        Section("Received announcements") {
            LabeledContent("Messages", value: "\(model.counters.messages)")
            LabeledContent("Payload bytes", value: "\(model.counters.bytes)")
            if let rate = model.counters.bytesPerSecond {
                LabeledContent("Rate", value: String(format: "%.1f KB/s", rate / 1000))
            }
        }
    }

    private var logSection: some View {
        Section("Log") {
            if model.log.isEmpty {
                Text("Nothing yet.").foregroundStyle(.secondary)
            }
            ForEach(Array(model.log.enumerated().reversed()), id: \.offset) { entry in
                Text(entry.element).font(.system(.caption2, design: .monospaced))
            }
        }
    }
}

private extension BleRole {
    var title: String {
        switch self {
        case .central: return "Central"
        case .peripheral: return "Peripheral"
        case .both: return "Both"
        }
    }
}

private extension BleRadioState {
    var title: String {
        switch self {
        case .unknown: return "unknown"
        case .resetting: return "resetting"
        case .unsupported: return "not supported"
        case .unauthorized: return "not allowed (Settings > Privacy > Bluetooth)"
        case .poweredOff: return "off"
        case .poweredOn: return "on"
        }
    }
}
