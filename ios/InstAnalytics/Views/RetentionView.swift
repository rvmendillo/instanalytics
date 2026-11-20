import Charts
import SwiftUI

/// How many of your frame-1 viewers are still there at frame 2, 3, 4...
/// The question it answers: how long should my stories be?
struct RetentionView: View {
    @Environment(AppModel.self) private var model

    private struct Drop {
        let from: Int
        let to: Int
        let points: Double
    }

    private var points: [RetentionPoint] {
        model.retention.filter { $0.samples > 0 }.sorted { $0.position < $1.position }
    }

    private var biggestDrop: Drop? {
        var best: Drop?
        for (a, b) in zip(points, points.dropFirst()) {
            let d = a.pct - b.pct
            if d > (best?.points ?? -.infinity) {
                best = Drop(from: a.position, to: b.position, points: d)
            }
        }
        return best
    }

    /// Selective labels: the start, the end, and where the biggest loss lands.
    private func isLabeled(_ p: RetentionPoint) -> Bool {
        p.position == points.first?.position
            || p.position == points.last?.position
            || p.position == biggestDrop?.to
    }

    var body: some View {
        List {
            if points.isEmpty {
                ContentUnavailableView(
                    "Not enough stories yet",
                    systemImage: "chart.bar",
                    description: Text("Retention needs a few days of stories with more than one frame."))
            } else {
                Section {
                    chart
                } header: {
                    Text("Viewers still watching, by frame")
                } footer: {
                    Text("100% is everyone who saw frame 1 that day, averaged over 90 days.")
                }

                if let drop = biggestDrop {
                    Section("Readout") {
                        LabeledContent("Biggest drop", value: "Frame \(drop.from) → \(drop.to)")
                        LabeledContent("Lost there", value: "\(Int(drop.points.rounded())) points")
                        if let last = points.last {
                            LabeledContent("Reach frame \(last.position)",
                                           value: "\(Int(last.pct.rounded()))%")
                        }
                        Text(advice(for: drop))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                // The table twin: every value readable without the chart.
                Section("By position") {
                    ForEach(points) { p in
                        LabeledContent("Frame \(p.position)") {
                            Text("\(p.pct, specifier: "%.1f")%  ·  \(p.samples) days")
                                .monospacedDigit()
                        }
                    }
                }
            }
        }
        .navigationTitle("Retention")
        .refreshable { await model.refresh() }
    }

    private var chart: some View {
        Chart(points) { p in
            BarMark(
                x: .value("Frame", "\(p.position)"),
                y: .value("Still watching", p.pct),
                width: .ratio(0.6)
            )
            .cornerRadius(4)
            .foregroundStyle(Palette.accent)
            .annotation(position: .top, spacing: 4) {
                if isLabeled(p) {
                    Text("\(Int(p.pct.rounded()))%")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            .accessibilityLabel("Frame \(p.position)")
            .accessibilityValue("\(Int(p.pct.rounded())) percent still watching")
        }
        .chartYScale(domain: 0...108)
        .chartYAxis {
            AxisMarks(values: [0.0, 25.0, 50.0, 75.0, 100.0]) { value in
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5))
                    .foregroundStyle(Palette.gridline)
                AxisValueLabel {
                    Text("\(Int(value.as(Double.self) ?? 0))%")
                        .font(.caption2)
                }
            }
        }
        .chartXAxisLabel("Frame")
        .frame(height: 250)
        .padding(.vertical, 6)
    }

    private func advice(for drop: Drop) -> String {
        if drop.from == 1 {
            return "Most of the loss happens right after the opener. A stronger first frame, or putting the payoff earlier, matters more than anything after it."
        }
        return "Viewers hold through frame \(drop.from), then fall off. Stories that run past \(drop.from) frames are paying a real cost; consider ending there or making frame \(drop.to) earn it."
    }
}
