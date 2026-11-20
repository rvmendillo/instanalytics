import SwiftUI

struct PostsView: View {
    @Environment(AppModel.self) private var model

    private struct SortOption: Identifiable {
        let key: String
        let label: String
        var id: String { key }
    }

    private static let sorts = [
        SortOption(key: "views", label: "Views"),
        SortOption(key: "reach", label: "Reach"),
        SortOption(key: "engagement", label: "Engagement rate"),
        SortOption(key: "shares", label: "Shares"),
        SortOption(key: "saved", label: "Saves"),
        SortOption(key: "recent", label: "Newest"),
    ]

    var body: some View {
        @Bindable var settings = model

        List {
            Section {
                Picker("Show", selection: $settings.mediaType) {
                    Text("All").tag(String?.none)
                    Text("Posts").tag(String?.some("FEED"))
                    Text("Reels").tag(String?.some("REELS"))
                }
                .pickerStyle(.segmented)

                Picker("Rank by", selection: $settings.mediaSort) {
                    ForEach(Self.sorts) { sort in
                        Text(sort.label).tag(sort.key)
                    }
                }
            }

            Section {
                ForEach(model.media) { item in
                    NavigationLink(value: item) {
                        PostRow(rank: rank(of: item), item: item, sort: model.mediaSort)
                    }
                }
            } footer: {
                if !model.media.isEmpty {
                    Text("Engagement rate is interactions divided by reach: of the people who saw it, how many did something.")
                }
            }
        }
        .navigationTitle("Posts")
        .navigationDestination(for: MediaItem.self) { item in
            PostDetailView(item: item)
        }
        .overlay {
            if model.media.isEmpty && !model.isLoading {
                ContentUnavailableView(
                    "No posts yet",
                    systemImage: "square.grid.2x2",
                    description: Text("Posts and reels appear after the first sync, which runs every 6 hours."))
            }
        }
        .refreshable { await model.loadMedia() }
        .onChange(of: model.mediaSort) { reload() }
        .onChange(of: model.mediaType) { reload() }
        .toolbar {
            if model.useDemoData {
                ToolbarItem(placement: .topBarLeading) { DemoBadge() }
            }
        }
    }

    private func reload() {
        Task { await model.loadMedia() }
    }

    private func rank(of item: MediaItem) -> Int {
        (model.media.firstIndex { $0.id == item.id } ?? 0) + 1
    }
}

struct PostRow: View {
    let rank: Int
    let item: MediaItem
    let sort: String

    /// The number the list is ranked by, so the order explains itself.
    private var primary: (value: String, label: String) {
        switch sort {
        case "reach": return (Fmt.count(item.reach), "reach")
        case "engagement": return (Fmt.percent(item.engagementRate), "engaged")
        case "shares": return (Fmt.count(item.shares), "shares")
        case "saved": return (Fmt.count(item.saved), "saves")
        default: return (Fmt.count(item.views), "views")
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            if sort != "recent" {
                Text("\(rank)")
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(width: 22, alignment: .trailing)
            }

            Thumbnail(item: item)
                .frame(width: 52, height: 52)

            VStack(alignment: .leading, spacing: 3) {
                Text(item.title)
                    .font(.subheadline)
                    .lineLimit(2)
                HStack(spacing: 4) {
                    Text(item.isReel ? "Reel" : (item.mediaType == "CAROUSEL_ALBUM" ? "Carousel" : "Post"))
                    Text("·")
                    Text(item.publishedAt, format: .dateTime.month(.abbreviated).day())
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer(minLength: 6)

            VStack(alignment: .trailing, spacing: 1) {
                Text(primary.value)
                    .font(.subheadline.monospacedDigit().weight(.semibold))
                Text(primary.label)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }
}

struct PostDetailView: View {
    let item: MediaItem

    var body: some View {
        List {
            Section {
                Thumbnail(item: item)
                    .frame(maxWidth: .infinity)
                    .frame(height: 240)
                    .listRowInsets(EdgeInsets())
                if let caption = item.caption, !caption.isEmpty {
                    Text(caption).font(.subheadline)
                }
            }

            Section("Performance") {
                Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 12) {
                    GridRow {
                        StatTile(title: "Views", value: Fmt.count(item.views), prominent: false)
                        StatTile(title: "Reach", value: Fmt.count(item.reach), prominent: false)
                        StatTile(title: "Engagement", value: Fmt.percent(item.engagementRate), prominent: false)
                    }
                    GridRow {
                        StatTile(title: "Likes", value: Fmt.count(item.likes ?? item.likeCount), prominent: false)
                        StatTile(title: "Comments", value: Fmt.count(item.comments ?? item.commentsCount), prominent: false)
                        StatTile(title: "Saves", value: Fmt.count(item.saved), prominent: false)
                    }
                    GridRow {
                        StatTile(title: "Shares", value: Fmt.count(item.shares), prominent: false)
                        StatTile(title: "Follows", value: Fmt.count(item.follows), prominent: false)
                        StatTile(title: "Profile visits", value: Fmt.count(item.profileVisits), prominent: false)
                    }
                }
                .padding(.vertical, 4)
            }

            if item.isReel {
                Section {
                    LabeledContent("Average watch time", value: watchTime)
                    LabeledContent("Skip rate", value: item.skipRate.map { "\($0)%" } ?? "–")
                } header: {
                    Text("Watching")
                } footer: {
                    Text("Skip rate is an estimate Meta still marks as in development.")
                }
            }

            Section {
                LabeledContent("Published") {
                    Text(item.publishedAt.formatted(date: .abbreviated, time: .shortened))
                }
                if let at = item.insightsAt {
                    LabeledContent("Numbers as of") {
                        Text(at, style: .relative) + Text(" ago")
                    }
                }
                if let link = item.permalink, let url = URL(string: link) {
                    Link(destination: url) {
                        Label("Open on Instagram", systemImage: "arrow.up.right.square")
                    }
                }
            }
        }
        .navigationTitle(item.isReel ? "Reel" : "Post")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var watchTime: String {
        guard let ms = item.avgWatchMs else { return "–" }
        return String(format: "%.1fs", Double(ms) / 1000)
    }
}
