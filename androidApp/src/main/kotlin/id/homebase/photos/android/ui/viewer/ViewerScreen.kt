@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.photos.android.ui.viewer

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import id.homebase.core.image.HomebaseImageKeyer
import id.homebase.core.image.ImageSize
import id.homebase.photos.android.ui.homebaseImageData
import id.homebase.photos.android.ui.theme.CaptionOverlayTextStyle
import id.homebase.photos.android.ui.theme.PhotosTheme
import id.homebase.photos.domain.PhotoItem
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.uuid.ExperimentalUuidApi

// Same 225x300 the grid requested — its cached thumbnail seeds frame-0 via the size-independent
// HomebaseImageKeyer key. The 900x1200 hi-res class is the sharp image that crossfades in.
private val GRID_THUMB_SIZE = ImageSize(225, 300)
private val HI_RES_SIZE = ImageSize(900, 1200)

// Chrome auto-hides into the immersive default after this idle window (design-system §5.3).
private const val CHROME_AUTO_HIDE_MS = 3_000L

// Top/bottom chrome gradient bands; the bottom band is reserved-empty (actions land later).
private val CHROME_BAND_HEIGHT: Dp = 120.dp

// Viewer date label ("Jun 21, 2026") — UTC, matching the grid's cell label.
private val VIEWER_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy")

// Deterministic earthy gradient behind a cold page so it never flashes black before bytes arrive
// (parallels the grid's PLACEHOLDER_GRADIENTS).
private val VIEWER_GRADIENTS: List<Pair<Color, Color>> = listOf(
    Color(0xFF3C4D30) to Color(0xFF1B2815),
    Color(0xFF42432F) to Color(0xFF24251A),
    Color(0xFF272A1E) to Color(0xFF14160F),
    Color(0xFF2A2D21) to Color(0xFF191B13),
    Color(0xFF2F3225) to Color(0xFF1D1F16),
    Color(0xFF22241A) to Color(0xFF0E0F0A),
)

/**
 * Full-screen pager viewer (spec §5.3, MVP scope). Renders [items] paged from [initialIndex] over a
 * warm scrim; each page progressively loads — the already-cached grid thumbnail paints frame-0 (via
 * [HomebaseImageKeyer]'s size-independent thumbnail key as `placeholderMemoryCacheKey`) and the sharp
 * 900x1200 class crossfades in. A single tap toggles the chrome (back arrow + date), which auto-hides
 * after 3s. System back and the back arrow both call [onDismiss]. No zoom / share / original streaming
 * yet — those are recorded follow-ups.
 */
@Composable
fun ViewerScreen(
    items: List<PhotoItem>,
    initialIndex: Int,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)

    var chromeVisible by remember { mutableStateOf(true) }
    // Restart the idle timer whenever chrome becomes visible (tap-to-show or first frame).
    LaunchedEffect(chromeVisible) {
        if (chromeVisible) {
            delay(CHROME_AUTO_HIDE_MS)
            chromeVisible = false
        }
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
    ) { items.size }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim) // warm near-black viewer ground (§5.3)
            .testTag("viewer-root"),
    ) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1, // preload neighbours so their hi-res is ready on swipe
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ViewerPage(
                photo = items[page],
                imageLoader = imageLoader,
                onToggleChrome = { chromeVisible = !chromeVisible },
            )
        }

        if (chromeVisible) {
            val current = items.getOrNull(pagerState.currentPage)
            ViewerChrome(
                dateLabel = current?.let { formatDate(it.userDate) }.orEmpty(),
                onBack = onDismiss,
            )
        }
    }
}

/** One page: gradient + blurred preview beneath, progressive [AsyncImage] on top, play glyph for video. */
@Composable
private fun ViewerPage(
    photo: PhotoItem,
    imageLoader: ImageLoader,
    onToggleChrome: () -> Unit,
) {
    val context = LocalContext.current
    val placeholder = remember(photo.fileId) { decodeBlurPlaceholder(photo.previewPlaceholder) }
    val gradient = remember(photo.fileId) {
        VIEWER_GRADIENTS[photo.fileId.hashCode().mod(VIEWER_GRADIENTS.size)]
    }
    val dateLabel = remember(photo.userDate) { formatDate(photo.userDate) }

    // Frame-0 seed: the grid thumb's memory-cache key. Same params the grid used; the keyer is
    // size-independent so this hits the already-cached 225x300 entry while the hi-res loads.
    val seedKey = remember(photo.fileId) {
        HomebaseImageKeyer.thumbnailCacheKey(homebaseImageData(photo, GRID_THUMB_SIZE))
    }
    val request = remember(photo.fileId, context) {
        ImageRequest.Builder(context)
            .data(homebaseImageData(photo, HI_RES_SIZE))
            .placeholderMemoryCacheKey(seedKey)
            .crossfade(200)
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(gradient.first, gradient.second)))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // no ripple over a photo
                onClick = onToggleChrome,
            )
            .testTag("viewer-page"),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            imageLoader = imageLoader,
            contentDescription = if (photo.isVideo) "Video, $dateLabel" else "Photo, $dateLabel",
            contentScale = ContentScale.Fit,
            placeholder = placeholder,
            error = placeholder,
            fallback = placeholder,
            modifier = Modifier.fillMaxSize(),
        )
        if (photo.isVideo) {
            // video playback: T17 — glyph only for now, poster shows through beneath it.
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = PhotosTheme.extended.onOverlay,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

/** Top chrome band: back arrow + date over a fading gradient; bottom band reserved-empty. */
@Composable
private fun ViewerChrome(dateLabel: String, onBack: () -> Unit) {
    val overlay = PhotosTheme.extended.overlayChrome
    val onOverlay = PhotosTheme.extended.onOverlay
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(CHROME_BAND_HEIGHT)
                .background(Brush.verticalGradient(listOf(overlay, Color.Transparent)))
                .statusBarsPadding()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBack,
                    )
                    .testTag("viewer-back"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = onOverlay,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = dateLabel,
                style = CaptionOverlayTextStyle,
                color = onOverlay,
            )
        }
        // Bottom band reserved for the future action bar (share/delete/info — out of MVP scope).
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(CHROME_BAND_HEIGHT)
                .background(Brush.verticalGradient(listOf(Color.Transparent, overlay)))
                .navigationBarsPadding(),
        )
    }
}

private fun formatDate(userDate: Long): String =
    VIEWER_DATE_FORMATTER.format(Instant.ofEpochMilli(userDate).atZone(ZoneOffset.UTC))

/** Decode the inline base64 webp blur placeholder to a painter, or null if absent/undecodable. */
private fun decodeBlurPlaceholder(base64: String?): BitmapPainter? = base64?.let { encoded ->
    runCatching {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.let(::BitmapPainter)
    }.getOrNull()
}
