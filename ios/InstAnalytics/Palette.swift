import SwiftUI
import UIKit

/// Chart colors, light and dark stepped separately rather than auto-inverted.
///
/// One hue carries every chart in this app. The story navigation chart uses
/// emphasis rather than red/green: the two "left your stories" actions take
/// the accent, the two "kept watching" actions recede to gray. Exits are not a
/// status, they're the part of the chart worth looking at.
enum Palette {
    static let accent = dynamic(light: 0x2A78D6, dark: 0x3987E5)
    static let deemphasis = dynamic(light: 0xC3C2B7, dark: 0x52514E)
    static let gridline = dynamic(light: 0xE1E0D9, dark: 0x2C2C2A)
    static let muted = Color(hex: 0x898781)
    /// "Up and that's good" text. Always paired with an arrow, never color alone.
    static let goodText = dynamic(light: 0x006300, dark: 0x0CA30C)

    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            UIColor(hex: traits.userInterfaceStyle == .dark ? dark : light)
        })
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(uiColor: UIColor(hex: hex))
    }
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1)
    }
}
