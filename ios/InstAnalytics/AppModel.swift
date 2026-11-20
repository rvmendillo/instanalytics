import SwiftUI

@MainActor
@Observable
final class AppModel {

    var serverURL: String

    /// On by default so the app is worth opening before the backend exists.
    var useDemoData: Bool

    var accountId = 1

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

    func refresh() async {
        isLoading = true
        lastError = nil
        defer { isLoading = false }

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
