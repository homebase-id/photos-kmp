@file:OptIn(ExperimentalUuidApi::class, ExperimentalFoundationApi::class)

package id.homebase.photos.android.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import id.homebase.core.image.ImageSize
import id.homebase.photos.android.ui.homebaseImageData
import id.homebase.photos.android.ui.theme.PhotosTheme
import id.homebase.photos.domain.PhotoItem
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.uuid.ExperimentalUuidApi

// The 225x300 grid thumbnail (design-system §4.4) requested per cell; center-cropped to a square.
internal val GRID_THUMB_SIZE = ImageSize(225, 300)

// Cell a11y label ("Jun 21, 2026"). UTC to match month bucketing.
private val CELL_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy")

/** Flat neutral fallback behind a cell/cover with no decodable placeholder — never a gradient. */
@Composable
internal fun placeholderColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF2A2A2A)
    else MaterialTheme.colorScheme.surfaceContainerHighest

/**
 * A single square thumbnail cell. A flat neutral fill (or the decoded blur placeholder) sits behind
 * the image while it loads. The cell carries a TalkBack label; the image and badge stay decorative
 * (AUI-07).
 *
 * Selection (contract C5): when [selected], the image insets to ~82% behind an 8dp-rounded clip and
 * a filled primary check badge appears top-start. Unselected cells render unchanged even in
 * [selectionMode]. [onLongPress] is how callers enter selection mode.
 */
@Composable
fun PhotoGridCell(
    photo: PhotoItem,
    imageLoader: ImageLoader?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongPress: () -> Unit = {},
) {
    val placeholder = remember(photo.fileId) { decodeBlurPlaceholder(photo.previewPlaceholder) }
    val fallbackColor = placeholderColor()
    val dateLabel = remember(photo.userDate) {
        CELL_DATE_FORMATTER.format(Instant.ofEpochMilli(photo.userDate).atZone(ZoneOffset.UTC))
    }
    val imageFraction by animateFloatAsState(
        targetValue = if (selected) 0.82f else 1f,
        label = "cell-selection-inset",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f) // square cell; thumbnails are radiusNone at rest (design-system §4.2)
            .combinedClickable(onLongClick = onLongPress, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = if (photo.isVideo) "Video, $dateLabel" else "Photo, $dateLabel"
                role = Role.Button
                if (selectionMode) this.selected = selected
            }
            .testTag("timeline-cell"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(imageFraction)
                .clip(RoundedCornerShape(if (selected) 8.dp else 0.dp))
                .background(fallbackColor),
            contentAlignment = Alignment.Center,
        ) {
            if (imageLoader != null) {
                AsyncImage(
                    model = homebaseImageData(photo = photo, requestedSize = GRID_THUMB_SIZE),
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    placeholder = placeholder,
                    error = placeholder,
                    fallback = placeholder,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (photo.isVideo) {
                VideoBadge(modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        if (selected) {
            SelectionCheckBadge(modifier = Modifier.align(Alignment.TopStart))
        }
    }
}

/** Filled accent-color circle with a white check so it reads on any photo (C5). */
@Composable
private fun SelectionCheckBadge(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.CheckCircle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(6.dp)
            .size(20.dp)
            .background(Color.White, CircleShape) // the icon's cut-out check reads white
            .testTag("timeline-cell-check"),
    )
}

/** White play glyph top-right over a subtle dark corner scrim, Google-Photos style. */
@Composable
private fun VideoBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    listOf(PhotosTheme.extended.overlayChrome, Color.Transparent),
                ),
            )
            .padding(4.dp)
            .testTag("timeline-video-badge"),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = PhotosTheme.extended.onOverlay,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Decode the inline base64 webp blur placeholder to a painter, or null if absent/undecodable. */
private fun decodeBlurPlaceholder(base64: String?): BitmapPainter? = base64?.let { encoded ->
    runCatching {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.let(::BitmapPainter)
    }.getOrNull()
}
