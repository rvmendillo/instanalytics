import SwiftUI

struct StoriesView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        List {
            if let summary = model.summary {
                Section {
                    SummaryTiles(summary: summary)
                } header: {
                    Text("Last 30 days")
                } footer: {
                    if summary.missed > 0 {
                        Label("\(summary.missed) frames expired before they were captured",
                              systemImage: "exclamationmark.triangle")
                    }
                }
            }

            if let error = model.lastError {
                Section {
                    Label(error, systemImage: "wifi.exclamationmark")
                        .foregroundStyle(.orange)
                }
            }

            ForEach(model.storiesByDay) { group in
                Section {
                    ForEach(group.frames) { frame in
                        NavigationLink(value: frame) {
                            StoryRow(frame: frame)
                        }
                    }
                } header: {
                    DayHeader(group: group)
                }
            }
        }
        .navigationTitle("Story archive")
        .navigationDestination(for: StoryFrame.self) { frame in
            StoryDetailView(frame: frame)
        }
        .refreshable { await model.refresh() }
        .overlay {
            if model.stories.isEmpty && !model.isLoading {
                ContentUnavailableView(
                    "No stories yet",
                    systemImage: "circle.dashed",
                    description: Text("Post a story. It's captured within 30 minutes and kept here permanently."))
            }
        }
        .toolbar {
            if model.useDemoData {
                ToolbarItem(placement: .topBarLeading) {
                    Text("DEMO")
                        .font(.caption2.weight(.bold))
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Palette.accent.opacity(0.15), in: Capsule())
                        .foregroundStyle(Palette.accent)
                }
            }
        }
    }
}

// MARK: - Summary

struct SummaryTiles: View {
    let summary: StorySummary

    var body: some View {
        Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 14) {
            GridRow {
                StatTile(title: "Avg reach", value: number(summary.avgReach),
                         caption: "\(summary.frames) frames · \(summary.storyDays) days")
                StatTile(title: "Total views", value: summary.totalViews.formatted())
            }
            GridRow {
                StatTile(title: "Left at a frame", value: percent(summary.avgExitRate),
                         caption: "exited or skipped to next account")
                StatTile(title: "Rewatched", value: percent(summary.avgRewatchRate),
                         caption: "tapped back")
            }
            GridRow {
                StatTile(title: "Follows", value: summary.followsFromStories.formatted(),
                         caption: "from stories")
                StatTile(title: "Replies", value: summary.totalReplies.formatted(),
                         caption: summary.linkClicks > 0 ? "\(summary.linkClicks) link taps" : nil)
            }
        }
        .padding(.vertical, 6)
    }

    private func number(_ v: Double?) -> String {
        guard let v else { return "–" }
        return Int(v.rounded()).formatted()
    }

    private func percent(_ v: Double?) -> String {
        guard let v else { return "–" }
        return String(format: "%.1f%%", v)
    }
}

struct StatTile: View {
    let title: String
    let value: String
    var caption: String? = nil
    var prominent = true

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            // Proportional figures: tabular digits look loose at display size.
            Text(value)
                .font(prominent ? .title2.weight(.semibold) : .title3.weight(.semibold))
            if let caption {
                Text(caption)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Rows

struct DayHeader: View {
    let group: DayGroup

    var body: some View {
        HStack {
            Text(group.day, format: .dateTime.weekday(.wide).month(.abbreviated).day())
            Spacer()
            Text(group.frames.count == 1 ? "1 frame" : "\(group.frames.count) frames")
        }
    }
}

struct StoryRow: View {
    let frame: StoryFrame

    var body: some View {
        HStack(spacing: 12) {
            FramePips(index: frame.frameIndex ?? 1, total: frame.framesThatDay ?? 1)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(frame.publishedAt, format: .dateTime.hour().minute())
                        .font(.subheadline.weight(.semibold))
                    if frame.isVideo {
                        Image(systemName: "video.fill")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .accessibilityLabel("Video")
                    }
                    if (frame.linkClicks ?? 0) > 0 {
                        Image(systemName: "link")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .accessibilityLabel("Has link")
                    }
                }
                if frame.suppressedLowVolume {
                    Text("Under 5 viewers. Instagram withholds the numbers.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    Text("\(frame.reach ?? 0) reached · \(frame.views ?? 0) views")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer(minLength: 8)

            if let exit = frame.exitRate {
                VStack(alignment: .trailing, spacing: 1) {
                    // Tabular here: these align down the list.
                    Text("\(exit, specifier: "%.1f")%")
                        .font(.subheadline.monospacedDigit())
                    Text("left")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 2)
    }
}

/// Position within the day's story, echoing the story progress bar.
struct FramePips: View {
    let index: Int
    let total: Int

    var body: some View {
        VStack(spacing: 4) {
            Text("\(index)")
                .font(.headline.monospacedDigit())
            HStack(spacing: 2) {
                ForEach(1...max(total, 1), id: \.self) { i in
                    Capsule()
                        .fill(i == index ? Palette.accent : Palette.deemphasis)
                        .frame(width: total > 5 ? 4 : 6, height: 3)
                }
            }
        }
        .frame(width: 40)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Frame \(index) of \(total)")
    }
}
