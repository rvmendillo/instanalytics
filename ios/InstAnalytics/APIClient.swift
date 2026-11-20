import Foundation

/// Talks to your own backend. Never to Instagram directly - the backend holds
/// the token, and this app holds nothing worth stealing.
struct APIClient: Sendable {
    let baseURL: URL

    enum APIError: LocalizedError {
        case badResponse
        case status(Int)

        var errorDescription: String? {
            switch self {
            case .badResponse: return "The server sent something that wasn't HTTP."
            case .status(let code): return "Server returned HTTP \(code)."
            }
        }
    }

    func stories(accountId: Int, limit: Int = 200) async throws -> [StoryFrame] {
        try await get("api/stories", [
            URLQueryItem(name: "accountId", value: String(accountId)),
            URLQueryItem(name: "limit", value: String(limit)),
        ])
    }

    func summary(accountId: Int, days: Int) async throws -> StorySummary {
        try await get("api/stories/summary", [
            URLQueryItem(name: "accountId", value: String(accountId)),
            URLQueryItem(name: "days", value: String(days)),
        ])
    }

    func retention(accountId: Int, days: Int) async throws -> [RetentionPoint] {
        try await get("api/stories/retention-curve", [
            URLQueryItem(name: "accountId", value: String(accountId)),
            URLQueryItem(name: "days", value: String(days)),
        ])
    }

    func timeline(frameId: Int) async throws -> [TimelinePoint] {
        try await get("api/stories/\(frameId)/timeline", [])
    }

    func health() async throws -> Bool {
        var req = URLRequest(url: baseURL.appendingPathComponent("actuator/health"))
        req.timeoutInterval = 5
        let (_, resp) = try await URLSession.shared.data(for: req)
        return (resp as? HTTPURLResponse)?.statusCode == 200
    }

    // MARK: -

    private func get<T: Decodable>(_ path: String, _ query: [URLQueryItem]) async throws -> T {
        guard var comps = URLComponents(url: baseURL.appendingPathComponent(path),
                                        resolvingAgainstBaseURL: false) else {
            throw URLError(.badURL)
        }
        if !query.isEmpty { comps.queryItems = query }
        guard let url = comps.url else { throw URLError(.badURL) }

        var req = URLRequest(url: url)
        req.timeoutInterval = 15
        req.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw APIError.badResponse }
        guard (200..<300).contains(http.statusCode) else { throw APIError.status(http.statusCode) }
        return try Self.decoder.decode(T.self, from: data)
    }

    /// Jackson can emit dates as ISO strings (with or without fractional
    /// seconds) or as epoch millis depending on configuration. Accept all three
    /// rather than coupling this app to one backend setting.
    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        d.dateDecodingStrategy = .custom { decoder in
            let c = try decoder.singleValueContainer()
            if let millis = try? c.decode(Double.self) {
                return Date(timeIntervalSince1970: millis / 1000)
            }
            let s = try c.decode(String.self)
            if let date = APIClient.parseISO(s) { return date }
            throw DecodingError.dataCorruptedError(in: c, debugDescription: "Unparseable date: \(s)")
        }
        return d
    }()

    private static func parseISO(_ s: String) -> Date? {
        let frac = ISO8601DateFormatter()
        frac.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = frac.date(from: s) { return d }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: s)
    }
}
