package id.homebase.photos.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Neutral Google-Photos palette — Homebase Photos.
 *
 * Surfaces are pure neutral (white in light, black in dark) so photos carry all the color, matching
 * Google Photos' visual language. These objects are the static fallback for SDK < 31; on Android 12+
 * dynamic color supplies the accents while Theme.kt pins surface/background to the same neutrals.
 * The fallback accent is moss green (Batch G, owner-approved); over-photo overlay tokens are
 * theme-agnostic.
 */

/** Light theme — pure white ground, near-black text. */
object PhotosLightColors {
    // Grounds & surfaces
    val Background = Color(0xFFFFFFFF)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceVariant = Color(0xFFF1F3F4)

    // Surface-elevation ladder (container-low → highest)
    val Surface1 = Color(0xFFF8F9FA)
    val Surface2 = Color(0xFFF1F3F4)
    val Surface3 = Color(0xFFEEEEEE)
    val Surface4 = Color(0xFFE8EAED)
    val Surface5 = Color(0xFFE0E0E0)

    // Grid gaps are the background itself — white hairlines between thumbnails.
    val GridGap = Color(0xFFFFFFFF)

    // Accent (moss green) — fallback only; dynamic color replaces it on SDK 31+.
    val Primary = Color(0xFF5E7A52)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFD5E0C7)
    val OnPrimaryContainer = Color(0xFF1B2815)

    val Secondary = Color(0xFF5F6368)
    val OnSecondary = Color(0xFFFFFFFF)
    val SecondaryContainer = Color(0xFFE3E2CE)
    val OnSecondaryContainer = Color(0xFF24251A)

    // Text / icon
    val OnBackground = Color(0xFF1A1A1A)
    val OnSurface = Color(0xFF1A1A1A)
    val OnSurfaceVariant = Color(0xFF5F6368)
    val OnSurfaceVariantDim = Color(0xFF9AA0A6)
    val Outline = Color(0xFFDADCE0)

    // Viewer / over-photo
    val Scrim = Color(0xE6000000) // neutral near-black @ ~90%
    val OverlayChrome = Color(0x61000000) // gradient behind viewer controls @ 38%
    val OnOverlay = Color(0xFFFFFFFF)
    val OnOverlayDim = Color(0xB8FFFFFF) // 72%

    // Status
    val Success = Color(0xFF4F8A5B)
    val Warning = Color(0xFFC2873B)
    val Error = Color(0xFFB0413A)
    val OnError = Color(0xFFFFFFFF)
    val ErrorContainer = Color(0xFFF4D9D4)
    val OnErrorContainer = Color(0xFF3A0F0C)
}

/** Dark theme — pure black ground, near-white text; the viewer's natural home. */
object PhotosDarkColors {
    // Grounds & surfaces
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF000000)
    val SurfaceVariant = Color(0xFF2A2A2A)

    // Surface-elevation ladder
    val Surface1 = Color(0xFF111111)
    val Surface2 = Color(0xFF1A1A1A)
    val Surface3 = Color(0xFF212121)
    val Surface4 = Color(0xFF2A2A2A)
    val Surface5 = Color(0xFF333333)

    // Grid gaps are the background itself — black hairlines between thumbnails.
    val GridGap = Color(0xFF000000)

    // Accent (moss green, lightened for dark-mode contrast) — fallback only.
    val Primary = Color(0xFFA6C394)
    val OnPrimary = Color(0xFF1B2815)
    val PrimaryContainer = Color(0xFF3C4D30)
    val OnPrimaryContainer = Color(0xFFD5E0C7)

    val Secondary = Color(0xFFBDC1C6)
    val OnSecondary = Color(0xFF202124)
    val SecondaryContainer = Color(0xFF3C4043)
    val OnSecondaryContainer = Color(0xFFE8EAED)

    // Text / icon
    val OnBackground = Color(0xFFE8EAED)
    val OnSurface = Color(0xFFE8EAED)
    val OnSurfaceVariant = Color(0xFFBDC1C6)
    val OnSurfaceVariantDim = Color(0xFF80868B)
    val Outline = Color(0xFF3C4043)

    // Viewer / over-photo
    val Scrim = Color(0xF0000000) // near-black @ ~94%
    val OverlayChrome = Color(0x75000000) // @ 46%
    val OnOverlay = Color(0xFFFFFFFF)
    val OnOverlayDim = Color(0xB8FFFFFF)

    // Status
    val Success = Color(0xFF7CB985)
    val Warning = Color(0xFFD6A45C)
    val Error = Color(0xFFE29089)
    val OnError = Color(0xFF3A0F0C)
    val ErrorContainer = Color(0xFF5A211C)
    val OnErrorContainer = Color(0xFFF4D9D4)
}
