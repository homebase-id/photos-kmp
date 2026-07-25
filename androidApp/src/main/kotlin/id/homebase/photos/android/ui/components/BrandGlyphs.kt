package id.homebase.photos.android.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.unit.dp

/**
 * Abstract moss leaf-pair wordmark — two mirrored leaves meeting at a base point. Filled white so an
 * [androidx.compose.material3.Icon] tint paints the whole mark. Shared by the login screen, the splash,
 * and (mirrored as a VectorDrawable) the launcher icon so the brand reads identically everywhere.
 */
val LeafGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "LeafGlyph",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathData {
                moveTo(12f, 21f)
                curveTo(6f, 18f, 4f, 12f, 7f, 5f)
                curveTo(13f, 7f, 15f, 13f, 12f, 21f)
                close()
            },
            fill = SolidColor(Color.White),
        )
        addPath(
            pathData = PathData {
                moveTo(12f, 21f)
                curveTo(18f, 18f, 20f, 12f, 17f, 5f)
                curveTo(11f, 7f, 9f, 13f, 12f, 21f)
                close()
            },
            fill = SolidColor(Color.White),
        )
    }.build()
}
