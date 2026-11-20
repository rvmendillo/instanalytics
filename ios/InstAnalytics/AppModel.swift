import SwiftUI

@MainActor
@Observable
final class AppModel {

    var serverURL: String

    /// On by default so the app is worth opening before the backend exists.
    var useDemoData: Bool

    var accountId = 1

    private(set) var overview: AccountOverview?
    private(set) var followerSeries: [SeriesPoint] = []
    private(set) var viewsSeries: [SeriesPoint] = []
    private(set) var bestTimes: BestTimes?
    private(set) var media: [MediaItem] = []

    /// Posts list controls. Changing either reloads the list.
    var mediaSort = "views"
    var mediaType: String?

    private(set) var stories: [StoryFrame] = []
    private(set) var summary: StorySummary?
    private(set) var retention: [RetentionPoint] = []
    private(set) var isLoading = false
    private(set) var lastError: String?
    private(set) var lastUpdated: Date?

    private enum Keys {
        static let server = "serverURL"
        static let demo = "useDemoData"
    }

    init() {
        let defaults = UserDefaults.standard
        serverURL = defaults.string(forKey: Keys.server) ?? "http://192.168.1.10:8080"
        useDemoData = defaults.object(forKey: Keys.demo) as? Bool ?? true
    }

    /// Settings writes through here. Kept explicit rather than as didSet on
    /// the observed properties, which interacts badly with the macro.
    func persist() {
        UserDefaults.standard.set(serverURL, forKey: Keys.server)
        UserDefaults.standard.set(useDemoData, forKey: Keys.demo)
    }

    var api: APIClient? {
        guard let url = URL(string: serverURL), url.scheme != nil, url.host != nil else { return nil }
        return APIClient(baseURL: url)
    }

    /// Stories grouped by local day, newest day first, frames in posting order.
    var storiesByDay: [DayGroup] {
        let cal = Calendar.current
        let groups = Dictionary(grouping: stories) { cal.startOfDay(for: $0.publishedAt) }
        return groups.keys.sorted(by: >).map { day in
            DayGroup(day: day, frames: (groups[day] ?? []).sorted { $0.publishedAt < $1.publishedAt })
        }
    }

    /// Everything, in turn. Each loader keeps its old data if its own request
    /// fails, so one broken endpoint doesn't blank the other tabs.
    func refresh() async {
        isLoading = true
        lastError = nil
        defer { isLoading = false }
        await loadStories()
        await loadAccount()
        await loadMedia()
    }

    func loadAccount() async {
        if useDemoData {
            overview = DemoData.overview
            followerSeries = DemoData.followerSeries
            viewsSeries = DemoData.viewsSeries
            bestTimes = DemoData.bestTimes
            return
        }
        guard let api else { return }
        let id = accountId
        do {
            async let o = api.overview(accountId: id, days: 30)
            async let f = api.series(accountId: id, metric: "followers", days: 90)
            async let v = api.series(accountId: id, metric: "views", days: 30)
            async let b = api.bestTimes(accountId: id, days: 180)
            let (loadedOverview, loadedFollowers, loadedViews, loadedBest) = try await (o, f, v, b)
            overview = loadedOverview
            followerSeries = loadedFollowers
            viewsSeries = loadedViews
            bestTimes = loadedBest
        } catch {
            lastError = error.localizedDescription
        }
    }

    func loadMedia() async {
        if useDemoData {
            media = DemoData.media(type: mediaType, sort: mediaSort)
            return
        }
        guard let api else { return }
        let id = accountId
        let type = mediaType
        let sort = mediaSort
        do {
            media = try await api.media(accountId: id, type: type, sort: sort)
        } catch {
            lastError = error.localizedDescription
        }
    }

    func audience(breakdown: String) async -> Audience? {
        if useDemoData { return DemoData.audience(breakdown) }
        guard let api else { return nil }
        let id = accountId
        return try? await api.audience(accountId: id, metric: "follower_demographics", breakdown: breakdown)
    }

    private func loadStories() async {
        if useDemoData {
            stories = DemoData.stories
            summary = DemoData.summary
            retention = DemoData.retention
            lastUpdated = Date()
            return
        }

        guard let api else {
            lastError = "Server URL isn't valid. Set it in Settings."
            return
        }

        let id = accountId
        do {
            async let s = api.stories(accountId: id)
            async let sum = api.summary(accountId: id, days: 30)
            async let r = api.retention(accountId: id, days: 90)
            let (loadedStories, loadedSummary, loadedRetention) = try await (s, sum, r)
            stories = loadedStories
            summary = loadedSummary
            retention = loadedRetention
            lastUpdated = Date()
        } catch {
            // Keep whatever was showing - a failed refresh shouldn't blank the screen.
            lastError = error.localizedDescription
        }
    }

    func timeline(for frame: StoryFrame) async -> [TimelinePoint] {
        if useDemoData { return DemoData.timeline(for: frame) }
        guard let api else { return [] }
        return (try? await api.timeline(frameId: frame.id)) ?? []
    }

    func testConnection() async -> String {
        guard let api else { return "That URL isn't valid." }
        do {
            return try await api.health() ? "Connected." : "Server answered, but not healthy."
        } catch {
            return "Couldn't reach it: \(error.localizedDescription)"
        }
    }
}
