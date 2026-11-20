import Foundation

/// Two weeks of plausible stories, so the app is worth opening before the
/// backend runs. Seeded, so the numbers are the same on every launch.
enum DemoData {

    static let stories: [StoryFrame] = build()

    static var summary: StorySummary {
        let s = stories
        let reaches = s.compactMap(\.reach)
        let totalReach = reaches.reduce(0, +)
        let left = s.map(\.leftCount).reduce(0, +)
        let back = s.compactMap(\.navTapBack).reduce(0, +)
        let days = Set(s.map { Calendar.current.startOfDay(for: $0.publishedAt) }).count

        return StorySummary(
            frames: s.count,
            storyDays: days,
            totalViews: s.compactMap(\.views).reduce(0, +),
            avgReach: reaches.isEmpty ? nil : Double(totalReach) / Double(reaches.count),
            totalReplies: s.compactMap(\.replies).reduce(0, +),
            followsFromStories: s.compactMap(\.follows).reduce(0, +),
            linkClicks: s.compactMap(\.linkClicks).reduce(0, +),
            avgExitRate: totalReach == 0 ? nil : 100 * Double(left) / Double(totalReach),
            avgRewatchRate: totalReach == 0 ? nil : 100 * Double(back) / Double(totalReach),
            underFiveViewers: 0,
            missed: 0)
    }

    static var retention: [RetentionPoint] {
        let byPosition = Dictionary(grouping: stories.filter { $0.retention != nil }) { $0.frameIndex ?? 1 }
        return byPosition.keys.sorted().map { position in
            let values = (byPosition[position] ?? []).compactMap(\.retention)
            let avg = values.isEmpty ? 0 : values.reduce(0, +) / Double(values.count)
            return RetentionPoint(position: position, samples: values.count, avgRetentionPct: avg)
        }
    }

    /// Views accumulating over the first hours, captured every 30 minutes.
    static func timeline(for frame: StoryFrame) -> [TimelinePoint] {
        guard let final = frame.views, final > 0 else { return [] }
        let n = 20
        let norm = 1 - exp(-Double(n) / 3.5)
        return (1...n).map { k in
            let share = (1 - exp(-Double(k) / 3.5)) / norm
            return TimelinePoint(
                capturedAt: frame.publishedAt.addingTimeInterval(Double(k) * 1800),
                metric: "views",
                dimension: "",
                value: Int((Double(final) * share).rounded()))
        }
    }

    // MARK: - Account

    /// The post a few weeks back that took off. Followers and views both jump
    /// on its day, so the demo tells one consistent story across tabs.
    private static let breakoutDaysAgo = 23

    static let followerSeries: [SeriesPoint] = {
        var rng = SeededRandom(seed: 9001)
        let cal = Calendar.current
        let today = cal.startOfDay(for: Date())
        var followers = 1_050.0
        var out: [SeriesPoint] = []
        for daysAgo in stride(from: 89, through: 0, by: -1) {
            let gain = rng.range(-2, 5.5) + (daysAgo == DemoData.breakoutDaysAgo ? 41 : 0)
                + (daysAgo == DemoData.breakoutDaysAgo - 1 ? 17 : 0)
            let previous = Int(followers.rounded())
            followers += gain
            let date = cal.date(byAdding: .day, value: -daysAgo, to: today) ?? today
            let value = Int(followers.rounded())
            out.append(SeriesPoint(date: date, value: value, delta: value - previous))
        }
        return out
    }()

    static let viewsSeries: [SeriesPoint] = {
        var rng = SeededRandom(seed: 4242)
        let cal = Calendar.current
        let today = cal.startOfDay(for: Date())
        return stride(from: 30, through: 1, by: -1).map { daysAgo in
            let date = cal.date(byAdding: .day, value: -daysAgo, to: today) ?? today
            let weekday = cal.component(.weekday, from: date)
            let weekend = (weekday == 1 || weekday == 7) ? 1.18 : 1.0
            var v = rng.range(1_150, 1_750) * weekend
            if daysAgo == DemoData.breakoutDaysAgo { v = 9_640 }
            if daysAgo == DemoData.breakoutDaysAgo - 1 { v = 5_210 }
            if daysAgo == DemoData.breakoutDaysAgo - 2 { v = 2_880 }
            return SeriesPoint(date: date, value: Int(v), delta: nil)
        }
    }()

    static var overview: AccountOverview {
        let views = Double(viewsSeries.compactMap(\.value).reduce(0, +))
        let current = followerSeries.last?.value ?? 1_284
        let start = followerSeries[max(0, followerSeries.count - 31)].value

        func m(_ name: String, _ agg: String, _ cur: Double, _ prev: Double) -> MetricTotal {
            MetricTotal(metric: name, aggregation: agg, current: cur, previous: prev, daysWithData: 30)
        }

        return AccountOverview(
            profile: .init(username: "your.account", name: "Demo account", accountType: "MEDIA_CREATOR",
                           followersCount: current, followsCount: 412, mediaCount: 187,
                           profilePictureUrl: nil, lastSyncedAt: Date().addingTimeInterval(-1_800)),
            metrics: [
                m("views", "sum", views, (views * 0.86).rounded()),
                m("reach", "daily_avg", 1_640, 1_512),
                m("accounts_engaged", "daily_avg", 212, 231),
                m("total_interactions", "sum", 3_905, 3_410),
                m("likes", "sum", 3_120, 2_790),
                m("comments", "sum", 284, 301),
                m("saves", "sum", 211, 160),
                m("shares", "sum", 290, 159),
                m("replies", "sum", 96, 88),
            ],
            followers: .init(current: current, atWindowStart: start, daysTracked: followerSeries.count),
            days: 30)
    }

    // MARK: - Posts

    static let allMedia: [MediaItem] = {
        var rng = SeededRandom(seed: 777)
        let captions = [
            "Weekend build log: shipped the dashboard",
            "Desk setup, version three",
            "Five things I wish I knew about Spring Boot",
            "Morning coffee and a long backlog",
            "Behind the scenes of a release day",
            "Small wins this week",
            "Refactoring an old side project",
            "A quiet Sunday",
            "What I'm reading this month",
            "Testing a new camera angle",
            "Before and after: the new UI",
            "Late-night debugging session",
        ]
        let now = Date()
        var out: [MediaItem] = []

        for i in 0..<26 {
            let daysAgo = 2 + Double(i) * 3.4 + rng.range(0, 1.4)
            let published = now.addingTimeInterval(-daysAgo * 86_400)
            let isReel = rng.next() < 0.42 || Int(daysAgo) == DemoData.breakoutDaysAgo
            let breakout = abs(daysAgo - Double(DemoData.breakoutDaysAgo)) < 1.7

            var reach = rng.range(700, 1_900) * (isReel ? rng.range(1.6, 3.2) : 1)
            if breakout { reach = 11_400 }
            // Young posts haven't finished accruing.
            if daysAgo < 7 { reach *= 0.55 + daysAgo / 7 * 0.45 }

            let r = Int(reach)
            let views = Int(Double(r) * (isReel ? rng.range(1.25, 1.6) : rng.range(1.1, 1.35)))
            let likes = Int(Double(r) * (isReel ? rng.range(0.035, 0.07) : rng.range(0.06, 0.11)))
            let comments = Int(Double(likes) * rng.range(0.04, 0.12))
            let saved = Int(Double(r) * rng.range(0.004, 0.02))
            let shares = Int(Double(r) * (isReel ? rng.range(0.006, 0.02) : rng.range(0.002, 0.008)))
            let interactions = likes + comments + saved + shares

            out.append(MediaItem(
                id: 1_000 + i,
                igMediaId: "17980000000\(1_000 + i)",
                mediaProductType: isReel ? "REELS" : "FEED",
                mediaType: isReel ? "VIDEO" : (rng.next() < 0.3 ? "CAROUSEL_ALBUM" : "IMAGE"),
                caption: breakout ? "Five things I wish I knew about Spring Boot" : captions[i % captions.count],
                permalink: nil,
                thumbnailUrl: nil,
                mediaUrl: nil,
                publishedAt: published,
                likeCount: likes,
                commentsCount: comments,
                views: views,
                reach: r,
                likes: likes,
                comments: comments,
                saved: saved,
                shares: shares,
                reposts: Int(rng.range(0, 3)),
                follows: Int(rng.range(0, isReel ? 9 : 4)) + (breakout ? 52 : 0),
                profileVisits: Int(Double(r) * rng.range(0.01, 0.03)),
                avgWatchMs: isReel ? Int(rng.range(4_200, 13_800)) : nil,
                skipRate: isReel ? Int(rng.range(28, 61)) : nil,
                interactions: interactions,
                engagementRate: 100 * Double(interactions) / Double(max(r, 1)),
                insightsAt: now.addingTimeInterval(-3_600)))
        }
        return out
    }()

    static func media(type: String?, sort: String) -> [MediaItem] {
        let items = allMedia.filter { type == nil || $0.mediaProductType == type }
        switch sort {
        case "reach": return items.sorted { ($0.reach ?? 0) > ($1.reach ?? 0) }
        case "engagement": return items.sorted { ($0.engagementRate ?? 0) > ($1.engagementRate ?? 0) }
        case "shares": return items.sorted { ($0.shares ?? 0) > ($1.shares ?? 0) }
        case "saved": return items.sorted { ($0.saved ?? 0) > ($1.saved ?? 0) }
        case "recent": return items.sorted { $0.publishedAt > $1.publishedAt }
        default: return items.sorted { ($0.views ?? 0) > ($1.views ?? 0) }
        }
    }

    /// Computed from the demo posts the same way the backend computes it:
    /// settled posts only (7+ days old), by local weekday and 4-hour block.
    static var bestTimes: BestTimes {
        let cal = Calendar.current
        let settled = allMedia.filter { $0.publishedAt < Date().addingTimeInterval(-7 * 86_400) }

        func slots(_ key: (MediaItem) -> Int) -> [TimeSlot] {
            let groups = Dictionary(grouping: settled, by: key)
            return groups.keys.sorted().map { k in
                let posts = groups[k] ?? []
                let reaches = posts.compactMap(\.reach)
                let avg = reaches.isEmpty ? nil : Double(reaches.reduce(0, +)) / Double(reaches.count)
                return TimeSlot(slot: k, posts: posts.count, avgReach: avg)
            }
        }

        return BestTimes(
            byWeekday: slots { ((cal.component(.weekday, from: $0.publishedAt) + 5) % 7) + 1 },
            byTimeOfDay: slots { (cal.component(.hour, from: $0.publishedAt) / 4) * 4 },
            zone: TimeZone.current.identifier,
            note: "Posts older than 7 days only.")
    }

    // MARK: - Audience

    static func audience(_ breakdown: String) -> Audience {
        let pairs: [(String, Int)]
        switch breakdown {
        case "gender":
            pairs = [("F", 652), ("M", 598), ("U", 34)]
        case "country":
            pairs = [("PH", 1_001), ("US", 77), ("SG", 41), ("AE", 36), ("JP", 29), ("CA", 26), ("AU", 22)]
        case "city":
            pairs = [("Quezon City, Metro Manila", 214), ("Manila, Metro Manila", 162),
                     ("Makati, Metro Manila", 88), ("Cebu City, Cebu", 61),
                     ("Davao City, Davao del Sur", 47), ("Pasig, Metro Manila", 44),
                     ("Taguig, Metro Manila", 39)]
        default:
            pairs = [("13-17", 26), ("18-24", 398), ("25-34", 565), ("35-44", 180),
                     ("45-54", 77), ("55-64", 26), ("65+", 12)]
        }
        let total = Double(pairs.map { $0.1 }.reduce(0, +))
        return Audience(
            metric: "follower_demographics",
            breakdown: breakdown,
            rows: pairs.map { AudienceSlice(dimension: $0.0, value: $0.1, share: 100 * Double($0.1) / total) },
            snapshotOn: Date())
    }

    // MARK: -

    private static func build() -> [StoryFrame] {
        var rng = SeededRandom(seed: 20_261_120)
        var frames: [StoryFrame] = []
        var nextId = 1

        let cal = Calendar.current
        let yesterday = cal.date(byAdding: .day, value: -1, to: cal.startOfDay(for: Date())) ?? Date()
        let framesPerDay = [4, 2, 5, 3, 1, 6, 3, 4, 2, 3, 5, 2, 4, 3]

        for (offset, count) in framesPerDay.enumerated() {
            guard let day = cal.date(byAdding: .day, value: -offset, to: yesterday) else { continue }

            let firstReach = Int(rng.range(240, 470))
            var reach = Double(firstReach)
            var minute = Int(rng.range(8 * 60, 12 * 60))

            for i in 1...count {
                if i > 1 {
                    reach *= rng.range(0.74, 0.93)
                    minute += Int(rng.range(25, 190))
                }

                let published = cal.date(byAdding: .minute, value: min(minute, 23 * 60 + 50), to: day) ?? day
                let r = max(Int(reach.rounded()), 6)
                let views = Int(Double(r) * rng.range(1.06, 1.24))

                let leftTotal = Double(r) * rng.range(0.05, 0.17)
                let exit = Int((leftTotal * 0.42).rounded())
                let swipe = Int((leftTotal * 0.58).rounded())
                let back = Int(Double(r) * rng.range(0.015, 0.07))
                let forward = Int(Double(r) * rng.range(0.32, 0.58))

                let replies = Int(Double(r) * rng.range(0.003, 0.016))
                let shares = Int(Double(r) * rng.range(0.001, 0.009))
                let reposts = Int(rng.range(0, 2.2))
                let hasLink = rng.next() < 0.18
                let isVideo = rng.next() < 0.35

                frames.append(StoryFrame(
                    id: nextId,
                    igMediaId: "179000000000\(nextId)",
                    mediaType: isVideo ? "VIDEO" : "IMAGE",
                    permalink: nil,
                    publishedAt: published,
                    expiresAt: published.addingTimeInterval(86_400),
                    frameIndex: i,
                    framesThatDay: count,
                    views: views,
                    reach: r,
                    replies: replies,
                    shares: shares,
                    reposts: reposts,
                    totalInteractions: replies + shares + reposts,
                    follows: Int(rng.range(0, 3.6)),
                    profileVisits: Int(Double(r) * rng.range(0.01, 0.045)),
                    linkClicks: hasLink ? Int(rng.range(3, 19)) : 0,
                    navTapForward: forward,
                    navTapBack: back,
                    navSwipeForward: swipe,
                    navTapExit: exit,
                    suppressedLowVolume: false,
                    captureCount: 48,
                    complete: true,
                    exitRate: 100 * Double(exit + swipe) / Double(r),
                    rewatchRate: 100 * Double(back) / Double(r),
                    retention: 100 * Double(r) / Double(firstReach)))
                nextId += 1
            }
        }
        return frames.sorted { $0.publishedAt > $1.publishedAt }
    }
}

/// SplitMix64. Deterministic, which Swift's SystemRandomNumberGenerator isn't.
struct SeededRandom {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed
    }

    mutating func next() -> Double {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        z = z ^ (z >> 31)
        return Double(z >> 11) / Double(UInt64(1) << 53)
    }

    mutating func range(_ lo: Double, _ hi: Double) -> Double {
        lo + (hi - lo) * next()
    }
}
