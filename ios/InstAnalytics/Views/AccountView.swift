import Charts
import SwiftUI

struct AccountView: View {
    @Environment(AppModel.self) private var model

    @State private var audienceBreakdown = "age"
    @State private var audience: Audience?
    @State private var timeMode = TimeMode.weekday
    @State private var selectedFollowerDate: Date?
    @State private var selectedViewsDate: Date?

    enum TimeMode: String, CaseIterable, Identifiable {
        case weekday = "Weekday"
        case timeOfDay = "Time of day"
        var id: String { rawValue }
    }

    var body: some View {
        List {
            if let o = model.overview {
                Section { profileHeader(o) }

                Section {
                    kpiGrid(o)
                } header: {
                    Text("Last \(o.days) days vs the \(o.days) before")
                } footer: {
                    Text("Reach and engaged are per-day averages. They count unique accounts, so adding days together would count the same person again each day.")
                }
            }

            if let error = model.lastError {
                Section {
                    Label(error, systemImage: "wifi.exclamationmark").foregroundStyle(.orange)
                }
            }

            Section {
                followerChart
            } header: {
                Text("Followers")
            } footer: {
                Text("Instagram only reports today's count. This history exists because the server snapshots it every day.")
            }

            Section {
                viewsChart
            } header: {
                Text("Views per day")
            }

            Section {
                Picker("Group by", selection: $timeMode) {
                    ForEach(TimeMode.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                bestTimesChart
            } header: {
                Text("When your posts reach most people")
            } footer: {
                Text(bestTimesFooter)
            }

            Section {
                Picker("Audience", selection: $audienceBreakdown) {
                    Text("Age").tag("age")
                    Text("Gender").tag("gender")
                    Text("Country").tag("country")
                    Text("City").tag("city")
                }
                .pickerStyle(.segmented)
                audienceChart
            } header: {
                Text("Your followers")
            } footer: {
                if let date = audience?.snapshotOn {
                    Text("Snapshot from \(date.formatted(date: .abbreviated, time: .omitted)). Instagram shares this once you pass 100 followers.")
                } else {
                    Text("Instagram shares audience data once you pass 100 followers.")
                }
            }
        }
        .navigationTitle("Account")
        .refreshable { await model.refresh() }
        .task(id: "\(audienceBreakdown)|\(model.useDemoData)") {
            audience = await model.audience(breakdown: audienceBreakdown)
        }
        .toolbar {
            if model.useDemoData {
                ToolbarItem(placement: .topBarLeading) { DemoBadge() }
            }
        }
    }

    // MARK: - Header and tiles

    private func profileHeader(_ o: AccountOverview) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                avatar(o.profile)
                VStack(alignment: .leading, spacing: 2) {
                    Text("@\(o.profile.username ?? "account")")
                        .font(.headline)
                    Text(o.profile.accountType == "BUSINESS" ? "Business account" : "Creator account")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            Grid(alignment: .leading, horizontalSpacing: 16) {
                GridRow {
                    countCell(o.profile.followersCount, "followers")
                    countCell(o.profile.followsCount, "following")
                    countCell(o.profile.mediaCount, "posts")
                }
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func avatar(_ p: AccountOverview.Profile) -> some View {
        let initial = String((p.username ?? "?").prefix(1)).uppercased()
        ZStack {
            Circle().fill(Palette.accent.opacity(0.15))
            if let url = p.profilePictureUrl.flatMap({ URL(string: $0) }) {
                AsyncImage(url: url) { $0.resizable().scaledToFill() } placeholder: { Text(initial) }
            } else {
                Text(initial).font(.title3.weight(.semibold)).foregroundStyle(Palette.accent)
            }
        }
        .frame(width: 48, height: 48)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }

    private func countCell(_ value: Int?, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(Fmt.count(value)).font(.headline)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func kpiGrid(_ o: AccountOverview) -> some View {
        let views = o.metric("views")
        let reach = o.metric("reach")
        let engaged = o.metric("accounts_engaged")
        let interactions = o.metric("total_interactions")
        let shares = o.metric("shares")
        let saves = o.metric("saves")

        return Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 16) {
            GridRow {
                KPITile(title: "Views", value: Fmt.count(views?.current), change: views?.change)
                KPITile(title: "New followers", value: Fmt.signed(o.followers.gained), change: nil,
                        caption: "\(Fmt.count(o.followers.current)) now")
            }
            GridRow {
                KPITile(title: "Reach / day", value: Fmt.count(reach?.current), change: reach?.change)
                KPITile(title: "Engaged / day", value: Fmt.count(engaged?.current), change: engaged?.change)
            }
            GridRow {
                KPITile(title: "Interactions", value: Fmt.count(interactions?.current), change: interactions?.change)
                KPITile(title: "Shares", value: Fmt.count(shares?.current), change: shares?.change,
                        caption: saves.map { "\(Fmt.count($0.current)) saves" })
            }
        }
        .padding(.vertical, 6)
    }

    // MARK: - Followers

    private var followerPoints: [SeriesPoint] {
        model.followerSeries.filter { $0.value != nil }
    }

    private var selectedFollowerPoint: SeriesPoint? {
        nearest(in: followerPoints, to: selectedFollowerDate)
    }

    @ViewBuilder
    private var followerChart: some View {
        if followerPoints.count > 1 {
            Chart {
                ForEach(followerPoints) { p in
                    LineMark(x: .value("Date", p.date, unit: .day), y: .value("Followers", p.value ?? 0))
                        .lineStyle(StrokeStyle(lineWidth: 2))
                        .interpolationMethod(.monotone)
                        .foregroundStyle(Palette.accent)
                }
                if let p = selectedFollowerPoint {
                    RuleMark(x: .value("Date", p.date, unit: .day))
                        .foregroundStyle(Palette.muted)
                        .lineStyle(StrokeStyle(lineWidth: 1))
                        .annotation(position: .top, spacing: 4,
                                    overflowResolution: .init(x: .fit(to: .chart), y: .disabled)) {
                            Callout(title: Fmt.count(p.value),
                                    detail: p.date.formatted(.dateTime.month(.abbreviated).day()),
                                    extra: p.delta.map { "\(Fmt.signed($0)) that day" })
                        }
                    PointMark(x: .value("Date", p.date, unit: .day), y: .value("Followers", p.value ?? 0))
                        .symbolSize(64)
                        .foregroundStyle(Palette.accent)
                }
            }
            // A level, not a magnitude: zoom to the range so growth is visible.
            .chartYScale(domain: .automatic(includesZero: false))
            .chartXSelection(value: $selectedFollowerDate)
            .chartYAxis {
                AxisMarks(position: .leading) { _ in
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5)).foregroundStyle(Palette.gridline)
                    AxisValueLabel()
                }
            }
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                    AxisValueLabel(format: .dateTime.month(.abbreviated).day())
                }
            }
            .frame(height: 200)
            .padding(.top, 30)
            .padding(.bottom, 4)
        } else {
            Text("Follower history builds one day at a time, from the day you connect.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Views

    private var viewPoints: [SeriesPoint] {
        model.viewsSeries.filter { $0.value != nil }
    }

    private var selectedViewsPoint: SeriesPoint? {
        nearest(in: viewPoints, to: selectedViewsDate)
    }

    @ViewBuilder
    private var viewsChart: some View {
        if viewPoints.isEmpty {
            Text("Daily views appear after the first nightly collection.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } else {
            Chart {
                ForEach(viewPoints) { p in
                    BarMark(x: .value("Day", p.date, unit: .day), y: .value("Views", p.value ?? 0))
                        .foregroundStyle(Palette.accent)
                        .cornerRadius(2)
                }
                if let p = selectedViewsPoint {
                    RuleMark(x: .value("Day", p.date, unit: .day))
                        .foregroundStyle(Palette.muted.opacity(0.4))
                        .lineStyle(StrokeStyle(lineWidth: 1))
                        .annotation(position: .top, spacing: 4,
                                    overflowResolution: .init(x: .fit(to: .chart), y: .disabled)) {
                            Callout(title: "\(Fmt.count(p.value)) views",
                                    detail: p.date.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day()),
                                    extra: nil)
                        }
                }
            }
            .chartXSelection(value: $selectedViewsDate)
            .chartYAxis {
                AxisMarks(position: .leading) { _ in
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5)).foregroundStyle(Palette.gridline)
                    AxisValueLabel()
                }
            }
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                    AxisValueLabel(format: .dateTime.month(.abbreviated).day())
                }
            }
            .frame(height: 190)
            .padding(.top, 30)
            .padding(.bottom, 4)
        }
    }

    // MARK: - Best times

    private static let weekdayNames = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]

    /// Every slot present, empty ones included, so the axis never hides a gap.
    private var timeSlots: [TimeSlot] {
        guard let bt = model.bestTimes else { return [] }
        let source = timeMode == .weekday ? bt.byWeekday : bt.byTimeOfDay
        let keys = timeMode == .weekday ? Array(1...7) : [0, 4, 8, 12, 16, 20]
        return keys.map { k in source.first { $0.slot == k } ?? TimeSlot(slot: k, posts: 0, avgReach: nil) }
    }

    private func slotLabel(_ slot: Int) -> String {
        if timeMode == .weekday {
            return Self.weekdayNames[max(0, min(6, slot - 1))]
        }
        switch slot {
        case 0: return "12a"
        case 12: return "12p"
        default: return slot < 12 ? "\(slot)a" : "\(slot - 12)p"
        }
    }

    /// The best slot with enough posts to mean something.
    private var bestSlot: TimeSlot? {
        timeSlots.filter { $0.posts >= 3 && $0.avgReach != nil }.max { ($0.avgReach ?? 0) < ($1.avgReach ?? 0) }
    }

    @ViewBuilder
    private var bestTimesChart: some View {
        if timeSlots.allSatisfy({ $0.posts == 0 }) {
            Text("Needs posts that are at least a week old.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } else {
            Chart(timeSlots) { s in
                BarMark(x: .value("When", slotLabel(s.slot)), y: .value("Avg reach", s.avgReach ?? 0),
                        width: .ratio(0.6))
                    .cornerRadius(4)
                    // Thin evidence recedes: under 3 posts is an anecdote.
                    .foregroundStyle(s.posts >= 3 ? Palette.accent : Palette.deemphasis)
                    .accessibilityLabel(slotLabel(s.slot))
                    .accessibilityValue("\(Fmt.count(s.avgReach)) average reach over \(s.posts) posts")
            }
            .chartYAxis {
                AxisMarks(position: .leading) { _ in
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5)).foregroundStyle(Palette.gridline)
                    AxisValueLabel()
                }
            }
            .frame(height: 190)
            .padding(.vertical, 6)

            if let best = bestSlot {
                LabeledContent("Best", value: "\(slotLabel(best.slot)) · \(Fmt.count(best.avgReach)) avg reach")
                    .font(.subheadline)
            }

            DisclosureGroup("Every slot") {
                ForEach(timeSlots) { s in
                    LabeledContent(slotLabel(s.slot)) {
                        Text(s.posts == 0 ? "no posts" : "\(Fmt.count(s.avgReach)) · \(s.posts) posts")
                            .monospacedDigit()
                    }
                }
            }
            .font(.subheadline)
        }
    }

    private var bestTimesFooter: String {
        let zone = model.bestTimes?.zone ?? TimeZone.current.identifier
        return "Average reach per post, in \(zone) time. Only posts at least a week old count, since newer ones are still gaining reach. Faded bars have fewer than 3 posts."
    }

    // MARK: - Audience

    private var slices: [AudienceSlice] {
        guard let rows = audience?.rows else { return [] }
        if audienceBreakdown == "age" {
            // Age bands have a natural order; keep it rather than ranking.
            return rows.sorted { ageStart($0.dimension) < ageStart($1.dimension) }
        }
        return rows
    }

    private func ageStart(_ band: String) -> Int {
        Int(band.prefix { $0.isNumber }) ?? 999
    }

    private func displayName(_ dimension: String) -> String {
        switch audienceBreakdown {
        case "gender":
            switch dimension.uppercased() {
            case "F": return "Women"
            case "M": return "Men"
            default: return "Not specified"
            }
        case "country":
            return Locale.current.localizedString(forRegionCode: dimension) ?? dimension
        case "city":
            return dimension.split(separator: ",").first.map(String.init) ?? dimension
        default:
            return dimension
        }
    }

    @ViewBuilder
    private var audienceChart: some View {
        if slices.isEmpty {
            Text("No audience data yet.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } else {
            let top = slices.compactMap(\.share).max() ?? 100
            Chart(slices) { s in
                BarMark(x: .value("Share", s.share ?? 0), y: .value("Group", displayName(s.dimension)),
                        height: .ratio(0.6))
                    .cornerRadius(4)
                    .foregroundStyle(Palette.accent)
                    .annotation(position: .trailing, alignment: .leading, spacing: 6) {
                        Text(Fmt.percent(s.share, digits: 0))
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    .accessibilityLabel(displayName(s.dimension))
                    .accessibilityValue(Fmt.percent(s.share))
            }
            .chartXAxis(.hidden)
            .chartXScale(domain: 0...(top * 1.2))
            .chartLegend(.hidden)
            .frame(height: CGFloat(slices.count) * 30 + 8)
            .padding(.vertical, 6)
        }
    }

    // MARK: -

    private func nearest(in points: [SeriesPoint], to date: Date?) -> SeriesPoint? {
        guard let date else { return nil }
        return points.min { abs($0.date.timeIntervalSince(date)) < abs($1.date.timeIntervalSince(date)) }
    }
}

/// Floating readout for a scrubbed chart point.
struct Callout: View {
    let title: String
    let detail: String
    let extra: String?

    var body: some View {
        VStack(spacing: 2) {
            Text(title).font(.caption.weight(.semibold))
            Text(detail).font(.caption2).foregroundStyle(.secondary)
            if let extra {
                Text(extra).font(.caption2).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 6))
    }
}
