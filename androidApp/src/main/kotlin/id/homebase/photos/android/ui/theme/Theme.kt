package id.homebase.photos.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Conservatory theme — Homebase Photos.
 *
 * Standalone and compile-ready once Compose is wired into the Gradle build (Batch-0 Task 5). Do NOT
 * edit build.gradle for this; it is groundwork. Mirrors iosApp/Theme/Theme.swift exactly on color
 * and type-role intent.
 */

private val LightColorScheme =
        lightColorScheme(
                primary = PhotosLightColors.Primary,
                onPrimary = PhotosLightColors.OnPrimary,
                primaryContainer = PhotosLightColors.PrimaryContainer,
                onPrimaryContainer = PhotosLightColors.OnPrimaryContainer,
                secondary = PhotosLightColors.Secondary,
                onSecondary = PhotosLightColors.OnSecondary,
                secondaryContainer = PhotosLightColors.SecondaryContainer,
                onSecondaryContainer = PhotosLightColors.OnSecondaryContainer,
                background = PhotosLightColors.Background,
                onBackground = PhotosLightColors.OnBackground,
                surface = PhotosLightColors.Surface,
                onSurface = PhotosLightColors.OnSurface,
                surfaceVariant = PhotosLightColors.SurfaceVariant,
                onSurfaceVariant = PhotosLightColors.OnSurfaceVariant,
                error = PhotosLightColors.Error,
                onError = PhotosLightColors.OnError,
                errorContainer = PhotosLightColors.ErrorContainer,
                onErrorContainer = PhotosLightColors.OnErrorContainer,
                outline = PhotosLightColors.Outline,
                scrim = PhotosLightColors.Scrim,
                surfaceContainerLowest = PhotosLightColors.Surface,
                surfaceContainerLow = PhotosLightColors.Surface1,
                surfaceContainer = PhotosLightColors.Surface2,
                surfaceContainerHigh = PhotosLightColors.Surface3,
                surfaceContainerHighest = PhotosLightColors.Surface4,
        )

private val DarkColorScheme =
        darkColorScheme(
                primary = PhotosDarkColors.Primary,
                onPrimary = PhotosDarkColors.OnPrimary,
                primaryContainer = PhotosDarkColors.PrimaryContainer,
                onPrimaryContainer = PhotosDarkColors.OnPrimaryContainer,
                secondary = PhotosDarkColors.Secondary,
                onSecondary = PhotosDarkColors.OnSecondary,
                secondaryContainer = PhotosDarkColors.SecondaryContainer,
                onSecondaryContainer = PhotosDarkColors.OnSecondaryContainer,
                background = PhotosDarkColors.Background,
                onBackground = PhotosDarkColors.OnBackground,
                surface = PhotosDarkColors.Surface,
                onSurface = PhotosDarkColors.OnSurface,
                surfaceVariant = PhotosDarkColors.SurfaceVariant,
                onSurfaceVariant = PhotosDarkColors.OnSurfaceVariant,
                error = PhotosDarkColors.Error,
                onError = PhotosDarkColors.OnError,
                errorContainer = PhotosDarkColors.ErrorContainer,
                onErrorContainer = PhotosDarkColors.OnErrorContainer,
                outline = PhotosDarkColors.Outline,
                scrim = PhotosDarkColors.Scrim,
                surfaceContainerLowest = PhotosDarkColors.Surface,
                surfaceContainerLow = PhotosDarkColors.Surface1,
                surfaceContainer = PhotosDarkColors.Surface2,
                surfaceContainerHigh = PhotosDarkColors.Surface3,
                surfaceContainerHighest = PhotosDarkColors.Surface4,
        )

/** Photo-specific tokens M3's ColorScheme doesn't model. Access via PhotosTheme.extended. */
data class PhotosExtendedColors(
        val gridGap: Color,
        val surface1: Color,
        val surface2: Color,
        val surface3: Color,
        val surface4: Color,
        val surface5: Color,
        val onSurfaceVariantDim: Color,
        val overlayChrome: Color,
        val onOverlay: Color,
        val onOverlayDim: Color,
        val success: Color,
        val warning: Color,
)

private val LightExtendedColors =
        PhotosExtendedColors(
                gridGap = PhotosLightColors.GridGap,
                surface1 = PhotosLightColors.Surface1,
                surface2 = PhotosLightColors.Surface2,
                surface3 = PhotosLightColors.Surface3,
                surface4 = PhotosLightColors.Surface4,
                surface5 = PhotosLightColors.Surface5,
                onSurfaceVariantDim = PhotosLightColors.OnSurfaceVariantDim,
                overlayChrome = PhotosLightColors.OverlayChrome,
                onOverlay = PhotosLightColors.OnOverlay,
                onOverlayDim = PhotosLightColors.OnOverlayDim,
                success = PhotosLightColors.Success,
                warning = PhotosLightColors.Warning,
        )

private val DarkExtendedColors =
        PhotosExtendedColors(
                gridGap = PhotosDarkColors.GridGap,
                surface1 = PhotosDarkColors.Surface1,
                surface2 = PhotosDarkColors.Surface2,
                surface3 = PhotosDarkColors.Surface3,
                surface4 = PhotosDarkColors.Surface4,
                surface5 = PhotosDarkColors.Surface5,
                onSurfaceVariantDim = PhotosDarkColors.OnSurfaceVariantDim,
                overlayChrome = PhotosDarkColors.OverlayChrome,
                onOverlay = PhotosDarkColors.OnOverlay,
                onOverlayDim = PhotosDarkColors.OnOverlayDim,
                success = PhotosDarkColors.Success,
                warning = PhotosDarkColors.Warning,
        )

val LocalPhotosExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/**
 * Typography — platform-native Roboto, restrained. monthHeader (Semibold) is the timeline's
 * structural anchor; the rest stays quiet so photos carry the personality. Roles map to M3 slots:
 *   display→displaySmall, titleLarge→titleLarge, monthHeader→headlineSmall,
 *   dateSubhead→titleSmall, body→bodyLarge, bodyMedium→bodyMedium, label→labelLarge,
 *   caption→bodySmall, captionOverlay→labelLarge variant used over photos.
 */
val PhotosTypography =
        Typography(
                displaySmall =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Normal,
                                fontSize = 34.sp,
                                lineHeight = 41.sp,
                                letterSpacing = (-0.4).sp,
                        ),
                titleLarge =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Normal,
                                fontSize = 22.sp,
                                lineHeight = 28.sp,
                                letterSpacing = 0.sp,
                        ),
                // monthHeader — the one place type carries structure.
                headlineSmall =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 20.sp,
                                lineHeight = 26.sp,
                                letterSpacing = (-0.2).sp,
                        ),
                // dateSubhead — day group inside a month.
                titleSmall =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Medium,
                                fontSize = 15.sp,
                                lineHeight = 20.sp,
                                letterSpacing = 0.sp,
                        ),
                bodyLarge =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Normal,
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                                letterSpacing = 0.sp,
                        ),
                bodyMedium =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Normal,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                letterSpacing = 0.1.sp,
                        ),
                labelLarge =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                letterSpacing = 0.1.sp,
                        ),
                // caption — metadata, EXIF, timestamps, counts.
                bodySmall =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                letterSpacing = 0.2.sp,
                        ),
        )

/** Text style for chrome drawn over a photo/scrim (video duration, viewer date). */
val CaptionOverlayTextStyle =
        TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                letterSpacing = 0.1.sp,
        )

/** Corner radii — grid thumbnails are square (radiusNone) for Google-Photos-class density. */
val PhotosShapes =
        Shapes(
                extraSmall = RoundedCornerShape(8.dp), // chips, small buttons, pills
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp), // cards, inputs, album covers
                large = RoundedCornerShape(18.dp), // sheets, dialogs
                extraLarge = RoundedCornerShape(28.dp), // FAB, backup pill
        )

@Composable
fun PhotosTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extended = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalPhotosExtendedColors provides extended) {
        MaterialTheme(
                colorScheme = colorScheme,
                typography = PhotosTypography,
                shapes = PhotosShapes,
                content = content,
        )
    }
}

/**
 * Access photo-specific tokens not in M3's ColorScheme.
 *
 * Usage: PhotosTheme.extended.gridGap
 */
object PhotosTheme {
    val extended: PhotosExtendedColors
        @Composable get() = LocalPhotosExtendedColors.current
}
