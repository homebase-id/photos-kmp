package id.homebase.photos.android.ui.components

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The official Homebase Photos gradient, navy → crimson, as carried by the launcher icon and the
 * photo-app web logo. Used as the ground for the brand surfaces (splash, login) where the white
 * aperture mark sits on top.
 */
val BrandGradient: Brush = Brush.linearGradient(
    colors = listOf(Color(0xFF191272), Color(0xFFED0342)),
)
