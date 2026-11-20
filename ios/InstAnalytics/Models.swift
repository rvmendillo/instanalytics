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

// MARK: - Account

struct AccountOverview: Codable {
    let profile: Profile
    let metrics: [MetricTotal]
    let followers: FollowerSummary
    let days: Int

    struct Profile: Codable {
        let username: String?
        let name: String?
        let accountType: String?
        let followersCount: Int?
        let followsCount: Int?
        let mediaCount: Int?
        let profilePictureUrl: String?
        let lastSyncedAt: Date?
    }

    func metric(_ name: String) -> MetricTotal? {
        metrics.first { $0.metric == name }
    }
}

struct MetricTotal: Codable, Identifiable {
    let metric: String
    /// "sum", or "daily_avg" for unique-account counts that can't be summed.
    let aggregation: String
    let current: Double?
    let previous: Double?
    let daysWithData: Int

    var id: String { metric }

    /// Fractional change against the previous window; nil without a baseline.
    var change: Double? {
        guard let c = current, let p = previous, p > 0 else { return nil }
        return (c - p) / p
    }
}

struct FollowerSummary: Codable {
    let current: Int?
    let atWindowStart: Int?
    let daysTracked: Int

    var gained: Int? {
        guard let c = current, let s = atWindowStart else { return nil }
        return c - s
    }
}

struct SeriesPoint: Codable, Identifiable {
    let date: Date
    let value: Int?
    let delta: Int?

    var id: Date { date }
}

// MARK: - Posts

struct MediaItem: Codable, Identifiable, Hashable {
    let id: Int
    let igMediaId: String
    let mediaProductType: String
    let mediaType: String?
    let caption: String?
    let permalink: String?
    let thumbnailUrl: String?
    let mediaUrl: String?
    let publishedAt: Date
    let likeCount: Int?
    let commentsCount: Int?
    let views: Int?
    let reach: Int?
    let likes: Int?
    let comments: Int?
    let saved: Int?
    let shares: Int?
    let reposts: Int?
    let follows: Int?
    let profileVisits: Int?
    let avgWatchMs: Int?
    let skipRate: Int?
    let interactions: Int?
    let engagementRate: Double?
    let insightsAt: Date?

    var isReel: Bool { mediaProductType == "REELS" }

    /// Video media_url is a video file; only use it as a preview for images.
    var previewURL: URL? {
        let raw = thumbnailUrl ?? (mediaType == "VIDEO" ? nil : mediaUrl)
        return raw.flatMap { URL(string: $0) }
    }

    var title: String {
        let line = caption?.split(separator: "\n").first.map(String.init) ?? ""
        return line.isEmpty ? (isReel ? "Untitled reel" : "Untitled post") : line
    }
}

struct BestTimes: Codable {
    let byWeekday: [TimeSlot]
    let byTimeOfDay: [TimeSlot]
    let zone: String
    let note: String
}

struct TimeSlot: Codable, Identifiable {
    /// ISO weekday 1 (Mon) - 7 (Sun), or the starting hour of a 4-hour block.
    let slot: Int
    let posts: Int
    let avgReach: Double?

    var id: Int { slot }
}

// MARK: - Audience

struct Audience: Codable {
    let metric: String
    let breakdown: String
    let rows: [AudienceSlice]
    let snapshotOn: Date?
}

struct AudienceSlice: Codable, Identifiable {
    let dimension: String
    let value: Int
    let share: Double?

    var id: String { dimension }
}
