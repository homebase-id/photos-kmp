import SwiftUI

// Conservatory — earthy green pastel palette for Homebase Photos.
//
// Hex values are the cross-platform contract: they MUST stay identical to
// androidApp/.../ui/theme/Color.kt. Chrome recedes (low-chroma warm neutrals); the moss accent is
// rationed to where the user acts. No pure black/white anywhere in the chrome.

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

// MARK: - Raw palette (hex parity with Color.kt)

/// Conservatory Light — warm linen ground.
private enum LightPalette {
    static let background = Color(hex: 0xF4F1EA)
    static let surface = Color(hex: 0xFBFAF5)
    static let surfaceVariant = Color(hex: 0xE7E4D8)

    static let surface1 = Color(hex: 0xF1EEE6)
    static let surface2 = Color(hex: 0xEEEAE0)
    static let surface3 = Color(hex: 0xEAE6DB)
    static let surface4 = Color(hex: 0xE7E3D7)
    static let surface5 = Color(hex: 0xE3DED1)

    static let gridGap = Color(hex: 0xE9E5DB)

    static let primary = Color(hex: 0x5E7A52)
    static let onPrimary = Color(hex: 0xFFFFFF)
    static let primaryContainer = Color(hex: 0xD5E0C7)
    static let onPrimaryContainer = Color(hex: 0x1B2815)

    static let secondary = Color(hex: 0x7A7C5E)
    static let onSecondary = Color(hex: 0xFFFFFF)
    static let secondaryContainer = Color(hex: 0xE3E2CE)
    static let onSecondaryContainer = Color(hex: 0x24251A)

    static let onBackground = Color(hex: 0x23271F)
    static let onSurface = Color(hex: 0x23271F)
    static let onSurfaceVariant = Color(hex: 0x5A604F)
    static let onSurfaceVariantDim = Color(hex: 0x9AA08C)
    static let outline = Color(hex: 0xB9B6A6)

    static let scrim = Color(hex: 0xE6_1A1C16)
    static let overlayChrome = Color(hex: 0x61_000000)
    static let onOverlay = Color(hex: 0xFFFFFF)
    static let onOverlayDim = Color(hex: 0xB8_FFFFFF)

    static let success = Color(hex: 0x4F8A5B)
    static let warning = Color(hex: 0xC2873B)
    static let error = Color(hex: 0xB0413A)
    static let onError = Color(hex: 0xFFFFFF)
    static let errorContainer = Color(hex: 0xF4D9D4)
    static let onErrorContainer = Color(hex: 0x3A0F0C)
}

/// Conservatory Dark — deep green-charcoal ground (the viewer's natural home).
private enum DarkPalette {
    static let background = Color(hex: 0x14160F)
    static let surface = Color(hex: 0x191B13)
    static let surfaceVariant = Color(hex: 0x2B2E22)

    static let surface1 = Color(hex: 0x1D1F16)
    static let surface2 = Color(hex: 0x22241A)
    static let surface3 = Color(hex: 0x272A1E)
    static let surface4 = Color(hex: 0x2A2D21)
    static let surface5 = Color(hex: 0x2F3225)

    static let gridGap = Color(hex: 0x0E0F0A)

    static let primary = Color(hex: 0xA6C394)
    static let onPrimary = Color(hex: 0x1B2815)
    static let primaryContainer = Color(hex: 0x3C4D30)
    static let onPrimaryContainer = Color(hex: 0xD5E0C7)

    static let secondary = Color(hex: 0xC3C4A4)
    static let onSecondary = Color(hex: 0x2C2D1E)
    static let secondaryContainer = Color(hex: 0x42432F)
    static let onSecondaryContainer = Color(hex: 0xE0DFC9)

    static let onBackground = Color(hex: 0xE5E4D6)
    static let onSurface = Color(hex: 0xE5E4D6)
    static let onSurfaceVariant = Color(hex: 0xBCBCA6)
    static let onSurfaceVariantDim = Color(hex: 0x7E806C)
    static let outline = Color(hex: 0x4A4C3D)

    static let scrim = Color(hex: 0xF0_000000)
    static let overlayChrome = Color(hex: 0x75_000000)
    static let onOverlay = Color(hex: 0xFFFFFF)
    static let onOverlayDim = Color(hex: 0xB8_FFFFFF)

    static let success = Color(hex: 0x7CB985)
    static let warning = Color(hex: 0xD6A45C)
    static let error = Color(hex: 0xE29089)
    static let onError = Color(hex: 0x3A0F0C)
    static let errorContainer = Color(hex: 0x5A211C)
    static let onErrorContainer = Color(hex: 0xF4D9D4)
}

// MARK: - Semantic accessors

/// Semantic color tokens for Homebase Photos. Each resolves to light/dark via the active
/// color scheme. Use these in views — never the raw palettes.
///
/// Usage: `PhotosColor.primary(scheme)` where `@Environment(\.colorScheme) var scheme`.
/// Convenience dynamic `Color`s (auto light/dark) are also provided as `PhotosColor.<name>`.
enum PhotosColor {
    static func background(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.background : LightPalette.background }
    static func surface(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.surface : LightPalette.surface }
    static func surfaceVariant(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.surfaceVariant : LightPalette.surfaceVariant }

    static func surface1(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.surface1 : LightPalette.surface1 }
    static func surface2(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.surface2 : LightPalette.surface2 }
    static func surface3(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.surface3 : LightPalette.surface3 }
    static func surface4(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.surface4 : LightPalette.surface4 }
    static func surface5(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.surface5 : LightPalette.surface5 }

    /// The woven mat between grid thumbnails — its own token, distinct from surface/background.
    static func gridGap(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.gridGap : LightPalette.gridGap }

    static func primary(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.primary : LightPalette.primary }
    static func onPrimary(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.onPrimary : LightPalette.onPrimary }
    static func primaryContainer(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.primaryContainer : LightPalette.primaryContainer }
    static func onPrimaryContainer(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.onPrimaryContainer : LightPalette.onPrimaryContainer }

    static func secondary(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.secondary : LightPalette.secondary }
    static func onSecondary(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.onSecondary : LightPalette.onSecondary }
    static func secondaryContainer(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.secondaryContainer : LightPalette.secondaryContainer }
    static func onSecondaryContainer(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.onSecondaryContainer : LightPalette.onSecondaryContainer }

    static func onBackground(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.onBackground : LightPalette.onBackground }
    static func onSurface(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.onSurface : LightPalette.onSurface }
    static func onSurfaceVariant(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.onSurfaceVariant : LightPalette.onSurfaceVariant }
    static func onSurfaceVariantDim(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.onSurfaceVariantDim : LightPalette.onSurfaceVariantDim }
    static func outline(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.outline : LightPalette.outline }

    // Viewer / over-photo. Scrim & on-overlay are intentionally near-identical across schemes
    // because the fullscreen viewer is dark in both.
    static func scrim(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.scrim : LightPalette.scrim }
    static func overlayChrome(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.overlayChrome : LightPalette.overlayChrome }
    static func onOverlay(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.onOverlay : LightPalette.onOverlay }
    static func onOverlayDim(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.onOverlayDim : LightPalette.onOverlayDim }

    static func success(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.success : LightPalette.success }
    static func warning(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.warning : LightPalette.warning }
    static func error(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.error : LightPalette.error }
    static func onError(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.onError : LightPalette.onError }
    static func errorContainer(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.errorContainer : LightPalette.errorContainer }
    static func onErrorContainer(_ s: ColorScheme) -> Color { s == .dark ? DarkPalette.onErrorContainer : LightPalette.onErrorContainer }
}

// MARK: - Dynamic (UITraitCollection-resolving) accessors

/// Auto light/dark `Color`s for use without an explicit `ColorScheme` (e.g. in `Color`-typed
/// modifiers outside a view that reads the environment). Resolves per the system trait collection.
extension PhotosColor {
    private static func dynamic(light: Color, dark: Color) -> Color {
        #if canImport(UIKit)
        return Color(UIColor { trait in
            UIColor(trait.userInterfaceStyle == .dark ? dark : light)
        })
        #else
        return light
        #endif
    }

    static var background: Color { dynamic(light: LightPalette.background, dark: DarkPalette.background) }
    static var surface: Color { dynamic(light: LightPalette.surface, dark: DarkPalette.surface) }
    static var surfaceVariant: Color { dynamic(light: LightPalette.surfaceVariant, dark: DarkPalette.surfaceVariant) }
    static var gridGap: Color { dynamic(light: LightPalette.gridGap, dark: DarkPalette.gridGap) }
    static var primary: Color { dynamic(light: LightPalette.primary, dark: DarkPalette.primary) }
    static var onPrimary: Color { dynamic(light: LightPalette.onPrimary, dark: DarkPalette.onPrimary) }
    static var primaryContainer: Color { dynamic(light: LightPalette.primaryContainer, dark: DarkPalette.primaryContainer) }
    static var onPrimaryContainer: Color { dynamic(light: LightPalette.onPrimaryContainer, dark: DarkPalette.onPrimaryContainer) }
    static var onSurface: Color { dynamic(light: LightPalette.onSurface, dark: DarkPalette.onSurface) }
    static var onSurfaceVariant: Color { dynamic(light: LightPalette.onSurfaceVariant, dark: DarkPalette.onSurfaceVariant) }
    static var onSurfaceVariantDim: Color { dynamic(light: LightPalette.onSurfaceVariantDim, dark: DarkPalette.onSurfaceVariantDim) }
    static var outline: Color { dynamic(light: LightPalette.outline, dark: DarkPalette.outline) }
    static var scrim: Color { dynamic(light: LightPalette.scrim, dark: DarkPalette.scrim) }
    static var overlayChrome: Color { dynamic(light: LightPalette.overlayChrome, dark: DarkPalette.overlayChrome) }
    static var onOverlay: Color { dynamic(light: LightPalette.onOverlay, dark: DarkPalette.onOverlay) }
    static var onOverlayDim: Color { dynamic(light: LightPalette.onOverlayDim, dark: DarkPalette.onOverlayDim) }
    static var success: Color { dynamic(light: LightPalette.success, dark: DarkPalette.success) }
    static var warning: Color { dynamic(light: LightPalette.warning, dark: DarkPalette.warning) }
    static var error: Color { dynamic(light: LightPalette.error, dark: DarkPalette.error) }
}

// MARK: - Typography

/// Type roles for Homebase Photos. Platform-native SF Pro, restrained. `monthHeader` (Semibold) is
/// the timeline's structural anchor; everything else stays quiet so photos carry the personality.
/// Mirrors the Android PhotosTypography roles.
enum PhotosFont {
    static let display = Font.system(size: 34, weight: .regular, design: .default)
    static let titleLarge = Font.system(size: 22, weight: .regular, design: .default)
    /// Sticky timeline month/section header — the one place type carries structure.
    static let monthHeader = Font.system(size: 20, weight: .semibold, design: .default)
    /// Day group sub-header inside a month.
    static let dateSubhead = Font.system(size: 15, weight: .medium, design: .default)
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
    static let radiusMd: CGFloat = 12 // cards, inputs, album covers
    static let radiusLg: CGFloat = 18 // sheets, dialogs
    static let radiusXl: CGFloat = 28 // FAB, backup pill

    // Grid
    /// Hairline mat between thumbnails — dense, but the warm gridGap keeps it off "contact sheet."
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
