import CrisisCore
import SwiftUI

/// Runs the self-test of the Rust core and shows every step. On a phone this is the proof that the core works
/// there; it needs no Bluetooth and no permission.
struct SelfCheckView: View {
    @State private var report: SelfCheckReport?
    @State private var running = false

    var body: some View {
        NavigationStack {
            List {
                if let report {
                    Section {
                        ForEach(report.steps) { step in
                            StepRow(step: step)
                        }
                    } footer: {
                        Text(report.passed ? "Everything works on this device." : "Something does not work on this device.")
                    }
                } else {
                    ProgressView("Running the self-test…")
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
        // The checks are quick, but they open a database, so they stay off the main thread.
        report = await Task.detached(priority: .userInitiated) { CoreSelfCheck.run() }.value
        running = false
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
