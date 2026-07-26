import SwiftUI
import UIKit

// Google Photos visual language: pure neutral system surfaces (white in light, black in dark),
// near-black/near-white text, moss-green accent rationed to where the user acts. Tokens resolve
// through UIKit dynamic colors so light/dark tracks the trait collection automatically.

// MARK: - Hex initializer

extension Color {
    /// 0xRRGGBB (opaque) or 0xAARRGGBB (with alpha), matching the Android Color(0x..) literals.
    init(hex: UInt32) {
        let hasAlpha = hex > 0xFF_FF_FF
        let a = hasAlpha ? Double((hex >> 24) & 0xFF) / 255.0 : 1.0
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}

// MARK: - Semantic accessors

/// Semantic color tokens for Homebase Photos, mapped onto the iOS system palette. Use these in
/// views — never raw colors.
///
/// Both forms resolve per the trait collection; the `(_ s: ColorScheme)` accessors are kept so
/// existing call sites (`PhotosColor.primary(scheme)`) compile unchanged.
enum PhotosColor {
    private static func dynamic(light: UIColor, dark: UIColor) -> Color {
        Color(UIColor { trait in
            trait.userInterfaceStyle == .dark ? dark : light
        })
    }

    // Surfaces — pure neutral; the grid gap is the background itself.
    static var background: Color { Color(uiColor: .systemBackground) }
    static var surface: Color { Color(uiColor: .systemBackground) }
    static var surfaceVariant: Color { Color(uiColor: .systemGray5) }
    static var gridGap: Color { Color(uiColor: .systemBackground) }

    static var surface1: Color { Color(uiColor: .secondarySystemBackground) }
    static var surface2: Color { Color(uiColor: .secondarySystemBackground) }
    static var surface3: Color { Color(uiColor: .secondarySystemBackground) }
    static var surface4: Color { Color(uiColor: .secondarySystemBackground) }
    static var surface5: Color { Color(uiColor: .secondarySystemBackground) }

    // Accent — moss green (Batch G, owner-approved), mirroring the Android tokens. The scheme-less
    // vars carry the LIGHT values; scheme-aware call sites use the `(_ s:)` accessors below.
    static var primary: Color { Color(red: 0.369, green: 0.478, blue: 0.322) }            // 5E7A52
    static var onPrimary: Color { .white }
    static var primaryContainer: Color { Color(red: 0.835, green: 0.878, blue: 0.780) }   // D5E0C7
    static var onPrimaryContainer: Color { Color(red: 0.106, green: 0.157, blue: 0.082) } // 1B2815

    static var secondary: Color { Color(uiColor: .secondaryLabel) }
    static var onSecondary: Color { Color(uiColor: .systemBackground) }
    static var secondaryContainer: Color { Color(uiColor: .secondarySystemBackground) }
    static var onSecondaryContainer: Color { Color(uiColor: .label) }

    // Content
    static var onBackground: Color { Color(uiColor: .label) }
    static var onSurface: Color { Color(uiColor: .label) }
    static var onSurfaceVariant: Color { Color(uiColor: .secondaryLabel) }
    static var onSurfaceVariantDim: Color { Color(uiColor: .tertiaryLabel) }
    static var outline: Color { Color(uiColor: .systemGray3) }

    // Viewer / over-photo. Scrim & on-overlay are intentionally near-identical across schemes
    // because the fullscreen viewer is dark in both.
    static var scrim: Color { dynamic(light: UIColor(white: 0, alpha: 0.90), dark: UIColor(white: 0, alpha: 0.94)) }
    static var overlayChrome: Color { dynamic(light: UIColor(white: 0, alpha: 0.38), dark: UIColor(white: 0, alpha: 0.46)) }
    static var onOverlay: Color { .white }
    static var onOverlayDim: Color { Color(.sRGB, white: 1, opacity: 0.72) }

    // Status
    static var success: Color { Color(uiColor: .systemGreen) }
    static var warning: Color { Color(uiColor: .systemOrange) }
    static var error: Color { Color(uiColor: .systemRed) }
    static var onError: Color { .white }
    static var errorContainer: Color { Color(uiColor: .systemRed).opacity(0.15) }
    static var onErrorContainer: Color { Color(uiColor: .systemRed) }
}

// MARK: - ColorScheme-parameterized accessors (call-site compatibility)

extension PhotosColor {
    static func background(_ s: ColorScheme) -> Color { background }
    static func surface(_ s: ColorScheme) -> Color { surface }
    static func surfaceVariant(_ s: ColorScheme) -> Color { surfaceVariant }
    static func gridGap(_ s: ColorScheme) -> Color { gridGap }

    static func surface1(_ s: ColorScheme) -> Color { surface1 }
    static func surface2(_ s: ColorScheme) -> Color { surface2 }
    static func surface3(_ s: ColorScheme) -> Color { surface3 }
    static func surface4(_ s: ColorScheme) -> Color { surface4 }
    static func surface5(_ s: ColorScheme) -> Color { surface5 }

    // Moss accent is the one token family that branches per scheme explicitly (Android parity).
    static func primary(_ s: ColorScheme) -> Color { s == .dark ? Color(red: 0.651, green: 0.765, blue: 0.580) : Color(red: 0.369, green: 0.478, blue: 0.322) }  // A6C394 / 5E7A52
    static func onPrimary(_ s: ColorScheme) -> Color { s == .dark ? Color(red: 0.106, green: 0.157, blue: 0.082) : .white }                                       // 1B2815 / white
    static func primaryContainer(_ s: ColorScheme) -> Color { s == .dark ? Color(red: 0.235, green: 0.302, blue: 0.188) : Color(red: 0.835, green: 0.878, blue: 0.780) } // 3C4D30 / D5E0C7
    static func onPrimaryContainer(_ s: ColorScheme) -> Color { s == .dark ? Color(red: 0.835, green: 0.878, blue: 0.780) : Color(red: 0.106, green: 0.157, blue: 0.082) }

    static func secondary(_ s: ColorScheme) -> Color { secondary }
    static func onSecondary(_ s: ColorScheme) -> Color { onSecondary }
    static func secondaryContainer(_ s: ColorScheme) -> Color { secondaryContainer }
    static func onSecondaryContainer(_ s: ColorScheme) -> Color { onSecondaryContainer }

    static func onBackground(_ s: ColorScheme) -> Color { onBackground }
    static func onSurface(_ s: ColorScheme) -> Color { onSurface }
    static func onSurfaceVariant(_ s: ColorScheme) -> Color { onSurfaceVariant }
    static func onSurfaceVariantDim(_ s: ColorScheme) -> Color { onSurfaceVariantDim }
    static func outline(_ s: ColorScheme) -> Color { outline }

    static func scrim(_ s: ColorScheme) -> Color { scrim }
    static func overlayChrome(_ s: ColorScheme) -> Color { overlayChrome }
    static func onOverlay(_ s: ColorScheme) -> Color { onOverlay }
    static func onOverlayDim(_ s: ColorScheme) -> Color { onOverlayDim }

    static func success(_ s: ColorScheme) -> Color { success }
    static func warning(_ s: ColorScheme) -> Color { warning }
    static func error(_ s: ColorScheme) -> Color { error }
    static func onError(_ s: ColorScheme) -> Color { onError }
    static func errorContainer(_ s: ColorScheme) -> Color { errorContainer }
    static func onErrorContainer(_ s: ColorScheme) -> Color { onErrorContainer }
}

// MARK: - Typography

/// Type roles for Homebase Photos, following Google Photos' timeline hierarchy: the day header
/// (`dateSubhead`, semibold 15) is the primary structural anchor; the month-boundary header
/// (`monthHeader`, bold 22) marks month breaks. Everything else stays quiet.
enum PhotosFont {
    static let display = Font.system(size: 34, weight: .regular, design: .default)
    static let titleLarge = Font.system(size: 22, weight: .regular, design: .default)
    /// Month-boundary header ("March 2022") — plain bold text on the background.
    static let monthHeader = Font.system(size: 22, weight: .bold, design: .default)
    /// Day header ("Wed, Mar 30") — the primary timeline header.
    static let dateSubhead = Font.system(size: 15, weight: .semibold, design: .default)
    static let body = Font.system(size: 16, weight: .regular, design: .default)
    static let bodyMedium = Font.system(size: 14, weight: .regular, design: .default)
    static let label = Font.system(size: 14, weight: .medium, design: .default)
    /// Photo metadata, EXIF, timestamps, counts.
    static let caption = Font.system(size: 12, weight: .regular, design: .default)
    /// Text drawn over a photo/scrim (video duration, viewer date).
    static let captionOverlay = Font.system(size: 13, weight: .medium, design: .default)
}

// MARK: - Spacing, radii, grid metrics

/// 4-pt spacing scale + corner radii. Mirrors the Android design tokens.
enum PhotosMetrics {
    // Spacing
    static let space2: CGFloat = 2
    static let space4: CGFloat = 4
    static let space8: CGFloat = 8
    static let space12: CGFloat = 12
    static let space16: CGFloat = 16
    static let space20: CGFloat = 20
    static let space24: CGFloat = 24
    static let space32: CGFloat = 32
    static let space40: CGFloat = 40
    static let space48: CGFloat = 48

    /// Screen edge padding for non-grid content. The timeline grid is edge-to-edge (0).
    static let screenEdge: CGFloat = 16

    // Corner radii
    static let radiusNone: CGFloat = 0 // grid thumbnails — square for density
    static let radiusSm: CGFloat = 8 // chips, small buttons, pills
    static let radiusMd: CGFloat = 12 // cards, inputs
    static let radiusAlbumCover: CGFloat = 14 // album covers (Google Photos rounding)
    static let radiusLg: CGFloat = 18 // sheets, dialogs
    static let radiusXl: CGFloat = 28 // FAB, backup pill

    // Grid
    /// Hairline gap between thumbnails — the background shows through, like Google Photos.
    static let gridGapWidth: CGFloat = 1.5

    /// Columns for the edge-to-edge timeline grid at a given available width (pt).
    static func timelineColumns(forWidth width: CGFloat) -> Int {
        switch width {
        case ..<360: return 3
        case 360..<600: return 4
        case 600..<840: return 6
        case 840..<1200: return 8
        default: return 10
        }
    }
}
