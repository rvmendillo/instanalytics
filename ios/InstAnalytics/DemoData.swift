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
