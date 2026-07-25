@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.photos.android.ui.viewer

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import id.homebase.core.image.HomebaseImageKeyer
import id.homebase.core.image.ImageSize
import id.homebase.photos.android.ui.components.DeleteConfirmDialog
import id.homebase.photos.android.ui.homebaseImageData
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import id.homebase.photos.android.ui.theme.CaptionOverlayTextStyle
import id.homebase.photos.android.ui.theme.PhotosTheme
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.viewer.ViewerEvent
import id.homebase.photos.viewer.ViewerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
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

// Top/bottom chrome gradient bands; the bottom one carries the action bar.
private val CHROME_BAND_HEIGHT: Dp = 120.dp

// Viewer date label ("Jun 21, 2026") — UTC, matching the grid's cell label.
private val VIEWER_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy")

// Flat neutral behind a cold page so it reads as loading, not a hard black flash.
private val VIEWER_PLACEHOLDER = Color(0xFF2A2A2A)

/**
 * Full-screen VM-driven viewer (Batch B). A [HorizontalPager] over [ViewerViewModel]'s items:
 * stills page progressively (cached grid thumb seeds frame-0, sharp 900x1200 crossfades in) with
 * pinch/double-tap zoom; videos decrypt-to-temp and play via ExoPlayer. Chrome (back + date on top,
 * Share · Delete · Info action bar on the bottom) toggles on tap and auto-hides after 3s.
 * Delete runs through [DeleteConfirmDialog] → `vm.deleteCurrent()`; deleting the last item emits
 * `Closed` → dismiss. Every dismissal reports `state.deletedAny` so hosts can refresh their grids.
 */
@Composable
fun ViewerScreen(
    items: List<PhotoItem>,
    initialIndex: Int,
    imageLoader: ImageLoader,
    onDismiss: (deletedAny: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ViewerViewModel = viewModel(
        initializer = { ViewerViewModel(items, initialIndex, GlobalContext.get().get()) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    fun dismiss() = currentOnDismiss(state.deletedAny)

    BackHandler(onBack = ::dismiss)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ViewerEvent.Closed -> currentOnDismiss(true) // only delete empties the list
                is ViewerEvent.Error -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    var chromeVisible by remember { mutableStateOf(true) }
    // Restart the idle timer whenever chrome becomes visible (tap-to-show or first frame).
    LaunchedEffect(chromeVisible) {
        if (chromeVisible) {
            delay(CHROME_AUTO_HIDE_MS)
            chromeVisible = false
        }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(initialPage = state.index) { state.items.size }
    // Pager is the gesture source of truth; the VM clamps and mirrors it back.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            viewModel.setIndex(page)
        }
    }
    // VM-driven jumps (delete clamped the index) snap the pager without animation.
    LaunchedEffect(state.index, state.items.size) {
        if (state.items.isNotEmpty() && pagerState.currentPage != state.index) {
            pagerState.scrollToPage(state.index)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim) // neutral near-black viewer ground (§5.3)
            .testTag("viewer-root"),
    ) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1, // preload neighbours so their hi-res is ready on swipe
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val photo = state.items.getOrNull(page) ?: return@HorizontalPager
            if (photo.isVideo) {
                VideoPlayerPage(
                    photo = photo,
                    isActive = page == state.index,
                    chromeVisible = chromeVisible,
                    onChromeVisibleChange = { chromeVisible = it },
                )
            } else {
                // telephoto arbitrates pan-vs-page itself: zoomed drags pan, edge drags page.
                ViewerPage(
                    photo = photo,
                    imageLoader = imageLoader,
                    isActive = page == state.index,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                )
            }
        }

        if (chromeVisible) {
            val current = state.current
            ViewerChrome(
                dateLabel = current?.let { formatDate(it.userDate) }.orEmpty(),
                onBack = ::dismiss,
                onShare = {
                    current?.let { photo ->
                        scope.launch {
                            if (!sharePhoto(context, photo)) snackbarHostState.showSnackbar("Couldn't share")
                        }
                    }
                },
                onDelete = { showDeleteDialog = true },
                onInfo = { showInfoSheet = true },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }

    if (showDeleteDialog) {
        DeleteConfirmDialog(
            count = 1,
            onConfirm = {
                showDeleteDialog = false
                viewModel.deleteCurrent()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
    if (showInfoSheet) {
        state.current?.let { photo ->
            ViewerInfoSheet(photo = photo, onDismiss = { showInfoSheet = false })
        }
    }
}

/** One still page: flat fill + blur placeholder beneath, progressive telephoto zoomable on top. */
@Composable
private fun ViewerPage(
    photo: PhotoItem,
    imageLoader: ImageLoader,
    isActive: Boolean,
    onToggleChrome: () -> Unit,
) {
    val context = LocalContext.current
    val placeholder = remember(photo.fileId) { decodeBlurPlaceholder(photo.previewPlaceholder) }
    val dateLabel = remember(photo.userDate) { formatDate(photo.userDate) }
    val zoomableState = rememberZoomableState(ZoomSpec(maxZoomFactor = 5f))

    // Leaving the page (swipe past it) resets zoom so a return starts at 1x.
    LaunchedEffect(isActive) {
        if (!isActive) zoomableState.resetZoom()
    }

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
            .background(VIEWER_PLACEHOLDER)
            .testTag("viewer-page"),
        contentAlignment = Alignment.Center,
    ) {
        // Static blur underlay: telephoto owns the top layer, so error/loading show the blur.
        placeholder?.let {
            Image(
                painter = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ZoomableAsyncImage(
            model = request,
            imageLoader = imageLoader,
            state = rememberZoomableImageState(zoomableState),
            contentDescription = "Photo, $dateLabel",
            contentScale = ContentScale.Fit,
            onClick = { onToggleChrome() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Top chrome band: back arrow + date; bottom band: the Share · Delete · Info action bar. */
@Composable
private fun ViewerChrome(
    dateLabel: String,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit,
) {
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
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(CHROME_BAND_HEIGHT)
                .background(Brush.verticalGradient(listOf(Color.Transparent, overlay)))
                .navigationBarsPadding()
                .testTag("viewer-actionbar"),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            // NO favorite / add-to-album — deferred to Batches D/C; don't ship dead buttons.
            ViewerAction(Icons.Outlined.Share, "Share", "viewer-share", onShare)
            ViewerAction(Icons.Outlined.Delete, "Delete", "viewer-delete", onDelete)
            ViewerAction(Icons.Outlined.Info, "Info", "viewer-info", onInfo)
        }
    }
}

/** One action-bar entry: icon over a small label, Google-Photos style, tinted for the scrim. */
@Composable
private fun ViewerAction(
    icon: ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    val onOverlay = PhotosTheme.extended.onOverlay
    Column(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // no ripple over a photo
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = onOverlay,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            style = CaptionOverlayTextStyle,
            color = onOverlay,
            modifier = Modifier.padding(top = 4.dp),
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
