import Charts
import SwiftUI

struct StoryDetailView: View {
    @Environment(AppModel.self) private var model
    let frame: StoryFrame

    @State private var timeline: [TimelinePoint] = []
    @State private var selectedTime: Date?

    /// Fixed order, grouped: the two "kept watching" actions, then the two
    /// "left" actions. Emphasis carries the grouping; the axis labels carry
    /// identity, so color never has to.
    private var actions: [NavAction] {
        [
            NavAction(label: "Forward", value: frame.navTapForward ?? 0, group: .stayed),
            NavAction(label: "Back", value: frame.navTapBack ?? 0, group: .stayed),
            NavAction(label: "Next account", value: frame.navSwipeForward ?? 0, group: .left),
            NavAction(label: "Exited", value: frame.navTapExit ?? 0, group: .left),
        ]
    }

    private var viewSeries: [TimelinePoint] {
        timeline.filter { $0.metric == "views" }.sorted { $0.capturedAt < $1.capturedAt }
    }

    private var selectedPoint: TimelinePoint? {
        guard let t = selectedTime else { return nil }
        return viewSeries.min {
            abs($0.capturedAt.timeIntervalSince(t)) < abs($1.capturedAt.timeIntervalSince(t))
        }
    }

    var body: some View {
        List {
            Section {
                metricsGrid
            } header: {
                Text(frame.publishedAt.formatted(date: .abbreviated, time: .shortened))
            } footer: {
                Text("Frame \(frame.frameIndex ?? 1) of \(frame.framesThatDay ?? 1) that day · captured \(frame.captureCount)×")
            }

            if !frame.suppressedLowVolume {
                Section {
                    navigationChart
                } header: {
                    Text("What viewers did")
                } footer: {
                    Text(navigationFooter)
                }
            }

            Section {
                timelineChart
            } header: {
                Text("Views as they came in")
            } footer: {
                Text("Each point is one capture. Instagram deletes these numbers after 24 hours; this is the only copy. Drag across the chart to read a point.")
            }

            if let link = frame.permalink, let url = URL(string: link) {
                Section {
                    Link(destination: url) {
                        Label("Open on Instagram", systemImage: "arrow.up.right.square")
                    }
                }
            }
        }
        .navigationTitle("Frame \(frame.frameIndex ?? 1)")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: frame.id) {
            timeline = await model.timeline(for: frame)
        }
    }

    // MARK: - Pieces

    private var metricsGrid: some View {
        Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 12) {
            GridRow {
                StatTile(title: "Reached", value: count(frame.reach), prominent: false)
                StatTile(title: "Views", value: count(frame.views), prominent: false)
                StatTile(title: "Retention", value: percent(frame.retention),
                         caption: "of frame 1", prominent: false)
            }
            GridRow {
                StatTile(title: "Replies", value: count(frame.replies), prominent: false)
                StatTile(title: "Shares", value: count(frame.shares), prominent: false)
                StatTile(title: "Follows", value: count(frame.follows), prominent: false)
            }
            GridRow {
                StatTile(title: "Profile visits", value: count(frame.profileVisits), prominent: false)
                StatTile(title: "Link taps", value: count(frame.linkClicks), prominent: false)
                StatTile(title: "Rewatched", value: percent(frame.rewatchRate), prominent: false)
            }
        }
        .padding(.vertical, 4)
    }

    private var navigationChart: some View {
        Chart(actions) { action in
            BarMark(
                x: .value("Viewers", action.value),
                y: .value("Action", action.label),
                height: .ratio(0.55)
            )
            .cornerRadius(4)
            .foregroundStyle(action.group == .left ? Palette.accent : Palette.deemphasis)
            .annotation(position: .trailing, alignment: .leading, spacing: 6) {
                // Values wear text ink, never the bar's color.
                Text("\(action.value)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            .accessibilityLabel(action.label)
            .accessibilityValue("\(action.value) viewers")
        }
        .chartXAxis(.hidden)
        .chartYAxis {
            AxisMarks { _ in
                AxisValueLabel()
            }
        }
        .chartLegend(.hidden)
        .frame(height: 170)
        .padding(.vertical, 6)
    }

    private var navigationFooter: String {
        let left = frame.leftCount
        guard let reach = frame.reach, reach > 0 else {
            return "Highlighted: viewers who left your stories at this frame."
        }
        let pct = 100 * Double(left) / Double(reach)
        return "Highlighted: \(left) of \(reach) viewers (\(String(format: "%.1f", pct))%) left your stories at this frame, either closing them or skipping to the next account."
    }

    @ViewBuilder
    private var timelineChart: some View {
        if viewSeries.count > 1 {
            Chart {
                ForEach(viewSeries) { point in
                    LineMark(
                        x: .value("Captured", point.capturedAt),
                        y: .value("Views", point.value)
                    )
                    .lineStyle(StrokeStyle(lineWidth: 2))
                    .interpolationMethod(.monotone)
                    .foregroundStyle(Palette.accent)
                }

                if let point = selectedPoint {
                    RuleMark(x: .value("Captured", point.capturedAt))
                        .foregroundStyle(Palette.muted)
                        .lineStyle(StrokeStyle(lineWidth: 1))
                        .annotation(position: .top, spacing: 4,
                                    overflowResolution: .init(x: .fit(to: .chart), y: .disabled)) {
                            VStack(spacing: 2) {
                                Text("\(point.value) views")
                                    .font(.caption.weight(.semibold))
                                Text(point.capturedAt, format: .dateTime.hour().minute())
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            .padding(.horizontal, 8)
                            .padding(.vertical, 5)
                            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 6))
                        }

                    PointMark(
                        x: .value("Captured", point.capturedAt),
                        y: .value("Views", point.value)
                    )
                    .symbolSize(64)
                    .foregroundStyle(Palette.accent)
                }
            }
            .chartXSelection(value: $selectedTime)
            .chartYAxis {
                AxisMarks(position: .leading) { _ in
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5))
                        .foregroundStyle(Palette.gridline)
                    AxisValueLabel()
                }
            }
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                    AxisValueLabel(format: .dateTime.hour())
                }
            }
            .frame(height: 210)
            .padding(.top, 28)
            .padding(.bottom, 6)
        } else {
            Text("Needs at least two captures. New stories fill in over the first few hours.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Formatting

    private func count(_ v: Int?) -> String {
        v.map { $0.formatted() } ?? "–"
    }

    private func percent(_ v: Double?) -> String {
        v.map { String(format: "%.0f%%", $0) } ?? "–"
    }
}
