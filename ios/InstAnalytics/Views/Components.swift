import SwiftUI

/// A headline number with its change against the previous period.
struct KPITile: View {
    let title: String
    let value: String
    let change: Double?
    var caption: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.title2.weight(.semibold))
            if let change {
                DeltaLabel(change: change)
            }
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

/// Direction carried by the arrow and the words, not just the color.
struct DeltaLabel: View {
    let change: Double

    var body: some View {
        let up = change >= 0
        Label {
            Text("\(abs(change) * 100, specifier: "%.1f")%")
        } icon: {
            Image(systemName: up ? "arrow.up.right" : "arrow.down.right")
        }
        .labelStyle(.titleAndIcon)
        .font(.caption.weight(.medium))
        .foregroundStyle(up ? Palette.goodText : Color.secondary)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(up ? "Up" : "Down") \(Int((abs(change) * 100).rounded())) percent")
    }
}

struct DemoBadge: View {
    var body: some View {
        Text("DEMO")
            .font(.caption2.weight(.bold))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Palette.accent.opacity(0.15), in: Capsule())
            .foregroundStyle(Palette.accent)
    }
}

/// Post preview: the real thumbnail when there is one, a symbol otherwise.
struct Thumbnail: View {
    let item: MediaItem

    var body: some View {
        ZStack {
            Palette.accent.opacity(0.12)
            if let url = item.previewURL {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    ProgressView()
                }
            } else {
                Image(systemName: item.isReel ? "play.rectangle.fill" : "photo.fill")
                    .font(.title3)
                    .foregroundStyle(Palette.accent)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(alignment: .bottomTrailing) {
            if item.isReel && item.previewURL != nil {
                Image(systemName: "play.fill")
                    .font(.caption2)
                    .foregroundStyle(.white)
                    .padding(4)
                    .shadow(radius: 2)
            }
        }
        .accessibilityHidden(true)
    }
}

enum Fmt {
    /// Full digits below 100k, compact above - "48,210" but "1.2M".
    static func count(_ v: Double?) -> String {
        guard let v else { return "–" }
        if abs(v) >= 100_000 {
            return Int(v.rounded()).formatted(.number.notation(.compactName))
        }
        return Int(v.rounded()).formatted()
    }

    static func count(_ v: Int?) -> String {
        count(v.map(Double.init))
    }

    static func percent(_ v: Double?, digits: Int = 1) -> String {
        guard let v else { return "–" }
        return String(format: "%.\(digits)f%%", v)
    }

    static func signed(_ v: Int?) -> String {
        guard let v else { return "–" }
        return v > 0 ? "+\(v.formatted())" : v.formatted()
    }
}
