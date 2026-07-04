package id.homebase.photos.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Conservatory — earthy green pastel palette for Homebase Photos.
 *
 * Hex values are the cross-platform contract: they MUST stay identical to
 * iosApp/Theme/Theme.swift. Chrome recedes (low-chroma warm neutrals); the moss accent is rationed
 * to where the user acts. No pure black/white anywhere in the chrome.
 */

/** Light theme — Conservatory Light (warm linen ground). */
object PhotosLightColors {
    // Grounds & surfaces
    val Background = Color(0xFFF4F1EA)
    val Surface = Color(0xFFFBFAF5)
    val SurfaceVariant = Color(0xFFE7E4D8)

    // Surface-elevation ladder (container-low → highest)
    val Surface1 = Color(0xFFF1EEE6)
    val Surface2 = Color(0xFFEEEAE0)
    val Surface3 = Color(0xFFEAE6DB)
    val Surface4 = Color(0xFFE7E3D7)
    val Surface5 = Color(0xFFE3DED1)

    // The woven mat between grid thumbnails — its own token, distinct from surface/background.
    val GridGap = Color(0xFFE9E5DB)

    // Accent (moss) — only where the user can act.
    val Primary = Color(0xFF5E7A52)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFD5E0C7)
    val OnPrimaryContainer = Color(0xFF1B2815)

    val Secondary = Color(0xFF7A7C5E)
    val OnSecondary = Color(0xFFFFFFFF)
    val SecondaryContainer = Color(0xFFE3E2CE)
    val OnSecondaryContainer = Color(0xFF24251A)

    // Text / icon
    val OnBackground = Color(0xFF23271F)
    val OnSurface = Color(0xFF23271F)
    val OnSurfaceVariant = Color(0xFF5A604F)
    val OnSurfaceVariantDim = Color(0xFF9AA08C)
    val Outline = Color(0xFFB9B6A6)

    // Viewer / over-photo
    val Scrim = Color(0xE61A1C16) // warm near-black @ ~90%
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

/** Dark theme — Conservatory Dark (deep green-charcoal ground; the viewer's natural home). */
object PhotosDarkColors {
    // Grounds & surfaces
    val Background = Color(0xFF14160F)
    val Surface = Color(0xFF191B13)
    val SurfaceVariant = Color(0xFF2B2E22)

    // Surface-elevation ladder
    val Surface1 = Color(0xFF1D1F16)
    val Surface2 = Color(0xFF22241A)
    val Surface3 = Color(0xFF272A1E)
    val Surface4 = Color(0xFF2A2D21)
    val Surface5 = Color(0xFF2F3225)

    // Darker than background — thumbnails float on near-black, photo-first.
    val GridGap = Color(0xFF0E0F0A)

    // Accent (lightened moss for dark-mode contrast)
    val Primary = Color(0xFFA6C394)
    val OnPrimary = Color(0xFF1B2815)
    val PrimaryContainer = Color(0xFF3C4D30)
    val OnPrimaryContainer = Color(0xFFD5E0C7)

    val Secondary = Color(0xFFC3C4A4)
    val OnSecondary = Color(0xFF2C2D1E)
    val SecondaryContainer = Color(0xFF42432F)
    val OnSecondaryContainer = Color(0xFFE0DFC9)

    // Text / icon
    val OnBackground = Color(0xFFE5E4D6)
    val OnSurface = Color(0xFFE5E4D6)
    val OnSurfaceVariant = Color(0xFFBCBCA6)
    val OnSurfaceVariantDim = Color(0xFF7E806C)
    val Outline = Color(0xFF4A4C3D)

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
