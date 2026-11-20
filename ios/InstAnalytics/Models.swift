import Foundation

/// One story frame from the archive. Field names mirror the backend's
/// snake_case columns; the decoder converts them.
struct StoryFrame: Codable, Identifiable, Hashable {
    let id: Int
    let igMediaId: String
    let mediaType: String?
    let permalink: String?
    let publishedAt: Date
    let expiresAt: Date
    let frameIndex: Int?
    let framesThatDay: Int?

    let views: Int?
    let reach: Int?
    let replies: Int?
    let shares: Int?
    let reposts: Int?
    let totalInteractions: Int?
    let follows: Int?
    let profileVisits: Int?
    let linkClicks: Int?

    let navTapForward: Int?
    let navTapBack: Int?
    let navSwipeForward: Int?
    let navTapExit: Int?

    let suppressedLowVolume: Bool
    let captureCount: Int
    let complete: Bool

    let exitRate: Double?
    let rewatchRate: Double?
    let retention: Double?

    /// Viewers who abandoned your stories at this frame.
    var leftCount: Int { (navTapExit ?? 0) + (navSwipeForward ?? 0) }

    var isVideo: Bool { mediaType == "VIDEO" }
}

struct StorySummary: Codable {
    let frames: Int
    let storyDays: Int
    let totalViews: Int
    let avgReach: Double?
    let totalReplies: Int
    let followsFromStories: Int
    let linkClicks: Int
    let avgExitRate: Double?
    let avgRewatchRate: Double?
    let underFiveViewers: Int
    let missed: Int
}

struct RetentionPoint: Codable, Identifiable {
    let position: Int
    let samples: Int
    let avgRetentionPct: Double?

    var id: Int { position }
    var pct: Double { avgRetentionPct ?? 0 }
}

struct TimelinePoint: Codable, Identifiable {
    let capturedAt: Date
    let metric: String
    let dimension: String
    let value: Int

    var id: String { "\(capturedAt.timeIntervalSince1970)|\(metric)|\(dimension)" }
}

/// One bar in the "what viewers did" chart.
struct NavAction: Identifiable {
    enum Group { case stayed, left }

    let label: String
    let value: Int
    let group: Group

    var id: String { label }
}

/// Stories bucketed by local day, for the archive list.
struct DayGroup: Identifiable {
    let day: Date
    let frames: [StoryFrame]

    var id: Date { day }
}
