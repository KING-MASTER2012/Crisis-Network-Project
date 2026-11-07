import CrisisCore
import CrisisRuntime
import SwiftUI

/// Runs the self-tests of the Rust core and of the runtime and shows every step. On a phone this is the proof that
/// they work there; it needs no Bluetooth and no permission.
struct SelfCheckView: View {
    @State private var coreReport: SelfCheckReport?
    @State private var runtimeReport: SelfCheckReport?
    @State private var running = false

    var body: some View {
        NavigationStack {
            List {
                if coreReport == nil && runtimeReport == nil {
                    ProgressView("Running the self-test…")
                }
                if let coreReport {
                    ReportSection(title: "Core", report: coreReport)
                }
                if let runtimeReport {
                    ReportSection(title: "Runtime", report: runtimeReport)
                }
            }
            .navigationTitle("Crisis Network")
            .toolbar {
                ToolbarItem {
                    Button("Run again") { Task { await run() } }
                        .disabled(running)
                }
            }
        }
        .task { await run() }
    }

    private func run() async {
        running = true
        // The checks open databases and the Keychain, so they stay off the main thread.
        coreReport = await Task.detached(priority: .userInitiated) { CoreSelfCheck.run() }.value
        runtimeReport = await RuntimeSelfCheck.run()
        running = false
    }
}

private struct ReportSection: View {
    let title: String
    let report: SelfCheckReport

    var body: some View {
        Section {
            ForEach(report.steps) { step in
                StepRow(step: step)
            }
        } header: {
            Text(title)
        } footer: {
            Text(report.passed ? "Everything works on this device." : "Something does not work on this device.")
        }
    }
}

private struct StepRow: View {
    let step: SelfCheckStep

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: step.passed ? "checkmark.circle.fill" : "xmark.octagon.fill")
                .foregroundStyle(step.passed ? .green : .red)
                .accessibilityLabel(step.passed ? "Passed" : "Failed")
            VStack(alignment: .leading, spacing: 4) {
                Text(step.title).font(.headline)
                Text(step.detail).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
