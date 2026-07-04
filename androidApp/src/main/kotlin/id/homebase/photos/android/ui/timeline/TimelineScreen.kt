@file:OptIn(ExperimentalUuidApi::class, ExperimentalMaterial3Api::class)

package id.homebase.photos.android.ui.timeline

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import id.homebase.core.image.ImageSize
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.timeline.TimelineSection
import id.homebase.photos.timeline.TimelineUiState
import id.homebase.photos.timeline.TimelineViewModel
import id.homebase.photos.android.ui.homebaseImageData
import id.homebase.photos.android.ui.theme.PhotosTheme
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.uuid.ExperimentalUuidApi

// The 225x300 grid thumbnail (design-system §4.4) requested per cell; center-cropped to a square.
private val GRID_THUMB_SIZE = ImageSize(225, 300)

// Hairline warm gap between cells (design-system §4.4). Dense, but the warm mat reads as woven.
private val GRID_GAP: Dp = 1.5.dp

// Vertical space cleared below the grid so the last row is not hidden by the backup status card.
private val CARD_CLEARANCE: Dp = 96.dp

// Day-header label ("Wed, Jun 21") and cell a11y label ("Jun 21, 2026"). UTC to match month bucketing.
private val DAY_FORMATTER = DateTimeFormatter.ofPattern("EEE, MMM d")
private val CELL_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy")

// Deterministic earthy gradient pairs — the fallback behind a cell with no decodable blur placeholder.
private val PLACEHOLDER_GRADIENTS: List<Pair<Color, Color>> = listOf(
    Color(0xFFD5E0C7) to Color(0xFF8FA382),
    Color(0xFFE3E2CE) to Color(0xFFB9B6A6),
    Color(0xFFEAE6DB) to Color(0xFFC9C2AE),
    Color(0xFFDCE5D2) to Color(0xFF9AA08C),
    Color(0xFFE7E3D7) to Color(0xFFAFA893),
    Color(0xFFDFE6D8) to Color(0xFF7E806C),
)

/** Columns for the current viewport width (design-system §4.4 breakpoints). */
private fun columnsFor(widthDp: Float): Int = when {
    widthDp < 360f -> 3
    widthDp < 600f -> 4
    widthDp < 840f -> 6
    widthDp < 1200f -> 8
    else -> 10
}

/**
 * Stateful timeline entry point. Collects the shared [TimelineViewModel]'s [TimelineUiState]
 * and renders the Conservatory grid. The [imageLoader] is the Homebase-wired Coil loader
 * (see CoilSetup.buildHomebaseImageLoader). Thin: it only binds VM callbacks; the Activity owns
 * one-time events / snackbars (see MainActivity).
 */
@Composable
fun TimelineScreen(
    viewModel: TimelineViewModel,
    imageLoader: ImageLoader,
    onPhotoClick: (PhotoItem) -> Unit = {},
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TimelineScreen(
        state = state,
        onPhotoClick = onPhotoClick,
        onLoadMore = viewModel::loadMore,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::refresh,
        onLogout = onLogout,
        imageLoader = imageLoader,
        modifier = modifier,
    )
}

/**
 * Stateless timeline screen. A [Scaffold] carries the "Photos" top bar and a snackbar host; inside
 * it, one of four content branches renders — skeleton (first load), empty, error, or the
 * Conservatory grid — with the [backupCard] pinned above the bottom inset over all of them.
 * Edge-to-edge: the grid draws under the system bars, applying the scaffold insets as content
 * padding so items sit below the app bar (design-system §5.2). [imageLoader] is optional so UI
 * tests can render layout/headers without a Coil graph.
 */
@Composable
fun TimelineScreen(
    state: TimelineUiState,
    onPhotoClick: (PhotoItem) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    onLogout: () -> Unit = {},
    imageLoader: ImageLoader? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    backupCard: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val hasContent = state.sections.isNotEmpty()

    // Ephemeral UI state — the logout confirmation. The actual logout runs via [onLogout] (hoisted
    // to the Activity's lifecycleScope so it survives the recomposition the auth flip triggers).
    var showLogoutDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PhotosTopBar(
                scrolled = gridState.canScrollBackward,
                onAccountClick = { showLogoutDialog = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val columns = columnsFor(maxWidth.value)
                when {
                    state.isLoading && !hasContent ->
                        SkeletonGrid(columns = columns, innerPadding = innerPadding)
                    !state.isLoading && !hasContent && state.error != null ->
                        ErrorState(message = state.error, onRetry = onRetry, innerPadding = innerPadding)
                    !hasContent ->
                        EmptyState(innerPadding = innerPadding)
                    else ->
                        TimelineGrid(
                            state = state,
                            columns = columns,
                            gridState = gridState,
                            innerPadding = innerPadding,
                            imageLoader = imageLoader,
                            onPhotoClick = onPhotoClick,
                            onLoadMore = onLoadMore,
                            onRefresh = onRefresh,
                        )
                }
            }
            // Backup status surface — floats above the bottom system inset over every branch.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = innerPadding.calculateBottomPadding() + 16.dp),
            ) {
                backupCard()
            }
        }
    }

    if (showLogoutDialog) {
        LogoutDialog(
            onConfirm = {
                showLogoutDialog = false
                onLogout()
            },
            onDismiss = { showLogoutDialog = false },
        )
    }
}

/**
 * Log-out confirmation. Minimal design language: a short title + body, a destructive-reading
 * "Log out" confirm and a "Cancel" dismiss. The confirm is tagged for the flow test.
 */
@Composable
private fun LogoutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log out?") },
        text = { Text("You'll need to sign in again to see your photos.") },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("logout-confirm")) {
                Text("Log out")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/**
 * "Photos" top bar over `surface`, with a tappable account action that opens the log-out dialog
 * (via [onAccountClick]). A 1dp `outline` hairline fades in only once the grid has scrolled
 * (design-system §4.3 Level-1).
 */
@Composable
private fun PhotosTopBar(scrolled: Boolean, onAccountClick: () -> Unit) {
    val hairlineAlpha by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        label = "topbar-hairline",
    )
    Column {
        TopAppBar(
            title = { Text(text = "Photos", style = MaterialTheme.typography.titleLarge) },
            actions = {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(role = Role.Button, onClick = onAccountClick)
                        .testTag("account-button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.AccountCircle,
                        contentDescription = "Account",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = hairlineAlpha),
        )
    }
}

/**
 * The Conservatory grid. Builds a [TimelineRenderModel] once per `sections`/`columns` change,
 * pins the enclosing month over the top of the grid, paginates near the end, and supports
 * pull-to-refresh.
 */
@Composable
private fun TimelineGrid(
    state: TimelineUiState,
    columns: Int,
    gridState: LazyGridState,
    innerPadding: PaddingValues,
    imageLoader: ImageLoader?,
    onPhotoClick: (PhotoItem) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
) {
    val model = remember(state.sections, columns) { TimelineRenderModel(state.sections, columns) }

    // Pagination with a prefetch margin of ~4 rows (PERF-11 / AUI-08).
    val shouldLoadMore by remember(model) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= info.totalItemsCount - model.columns * 4
        }
    }
    LaunchedEffect(shouldLoadMore, state.endReached, state.isPaginating) {
        if (shouldLoadMore && !state.endReached && !state.isPaginating) onLoadMore()
    }

    // Sticky overlay: the enclosing month for the first visible item, hidden when that item IS a
    // month header (kills the double-draw at the top — AUI-03).
    val overlayTitle by remember(model) {
        derivedStateOf {
            val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull()
            if (first == null || model.isMonthHeader(first.index)) null else model.titleFor(first.index)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isLoading && state.sections.isNotEmpty(),
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(model.columns),
                state = gridState,
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + CARD_CLEARANCE,
                ),
                horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                verticalArrangement = Arrangement.spacedBy(GRID_GAP),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    count = model.entries.size,
                    key = { index -> model.keyAt(index) },
                    span = { index -> if (model.isFullSpan(index)) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                    contentType = { index -> model.contentTypeAt(index) },
                ) { index ->
                    when (val entry = model.entries[index]) {
                        is GridEntry.MonthHeaderEntry ->
                            MonthHeader(title = entry.title, modifier = Modifier.semantics { heading() })
                        is GridEntry.DayHeaderEntry ->
                            DaySubhead(label = entry.label)
                        is GridEntry.CellEntry ->
                            PhotoCell(
                                photo = entry.photo,
                                imageLoader = imageLoader,
                                onClick = { onPhotoClick(entry.photo) },
                            )
                    }
                }
                if (state.isPaginating) {
                    item(
                        key = "footer",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "footer",
                    ) {
                        FooterLoading()
                    }
                }
            }
        }

        overlayTitle?.let { title ->
            MonthHeader(
                title = title,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = innerPadding.calculateTopPadding()),
                testTag = "timeline-month-overlay",
            )
        }
    }
}

/** Photos-shaped skeleton for the first load — no per-cell spinner (design-system §5.2). */
@Composable
private fun SkeletonGrid(columns: Int, innerPadding: PaddingValues) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + CARD_CLEARANCE,
        ),
        horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
        verticalArrangement = Arrangement.spacedBy(GRID_GAP),
        userScrollEnabled = false,
        modifier = Modifier
            .fillMaxSize()
            .testTag("timeline-skeleton"),
    ) {
        items(count = columns * 12) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(PhotosTheme.extended.gridGap),
            )
        }
    }
}

/** No-photos-yet state (design-system §5.2). The backup card below carries the enable action. */
@Composable
private fun EmptyState(innerPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 32.dp)
            .testTag("timeline-empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No photos yet",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Turn on backup to see your camera roll here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Failed-first-load state (design-system §5.2). */
@Composable
private fun ErrorState(message: String?, onRetry: () -> Unit, innerPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 32.dp)
            .testTag("timeline-error"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Couldn't load photos",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message ?: "Please check your connection and try again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text("Try again")
        }
    }
}

/** Footer row shown while the next page loads (AUI-08). */
@Composable
private fun FooterLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("timeline-footer-loading"),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Month header — `surface` @ ~92%, semibold `monthHeader` type, 16dp h / 12dp v (§4.4). */
@Composable
private fun MonthHeader(
    title: String,
    modifier: Modifier = Modifier,
    testTag: String = "timeline-month-header",
) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall, // monthHeader slot (Theme.kt)
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(testTag),
    )
}

/** Day sub-header inside a month — `dateSubhead` type, `onSurfaceVariant` (§4.4 / AUI-10). */
@Composable
private fun DaySubhead(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall, // dateSubhead slot (Theme.kt)
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            .testTag("timeline-day-header"),
    )
}

/**
 * A single square thumbnail cell. A deterministic earthy gradient (or the decoded blur placeholder)
 * always sits behind the image so a cell is never a bare flat square (AUI-06). The cell carries a
 * TalkBack label; the image and badge stay decorative (AUI-07).
 */
@Composable
private fun PhotoCell(
    photo: PhotoItem,
    imageLoader: ImageLoader?,
    onClick: () -> Unit,
) {
    val placeholder = remember(photo.fileId) { decodeBlurPlaceholder(photo.previewPlaceholder) }
    val gradient = remember(photo.fileId) {
        PLACEHOLDER_GRADIENTS[photo.fileId.hashCode().mod(PLACEHOLDER_GRADIENTS.size)]
    }
    val dateLabel = remember(photo.userDate) {
        CELL_DATE_FORMATTER.format(Instant.ofEpochMilli(photo.userDate).atZone(ZoneOffset.UTC))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f) // square cell; thumbnails are radiusNone (design-system §4.2)
            .background(Brush.verticalGradient(listOf(gradient.first, gradient.second)))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = if (photo.isVideo) "Video, $dateLabel" else "Photo, $dateLabel"
                role = Role.Button
            }
            .testTag("timeline-cell"),
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
            VideoBadge(modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}

/** Small play badge over a faint scrim, drawn with over-photo `onOverlay` tokens. */
@Composable
private fun VideoBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .background(PhotosTheme.extended.overlayChrome, MaterialTheme.shapes.small)
            .padding(2.dp)
            .testTag("timeline-video-badge"),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = PhotosTheme.extended.onOverlay,
            modifier = Modifier.size(14.dp),
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

/** One grid slot in emission order — headers span all columns, cells occupy one. */
private sealed interface GridEntry {
    data class MonthHeaderEntry(val title: String) : GridEntry
    data class DayHeaderEntry(val key: String, val label: String) : GridEntry
    data class CellEntry(val photo: PhotoItem) : GridEntry
}

/**
 * Flattened grid entries + a prefix index of month-header positions. Built once per
 * `sections`/`columns` change so the sticky overlay maps a visible grid index to its owning
 * month in O(log n), instead of a per-frame linear scan (PERF-06).
 */
private class TimelineRenderModel(
    sections: List<TimelineSection>,
    val columns: Int,
) {
    val entries: List<GridEntry>
    private val monthIndices: IntArray // ascending indices into [entries] that are month headers
    private val monthTitles: Array<String> // parallel to [monthIndices]

    init {
        val list = ArrayList<GridEntry>()
        val indices = ArrayList<Int>()
        val titles = ArrayList<String>()
        for (section in sections) {
            indices.add(list.size)
            titles.add(section.title)
            list.add(GridEntry.MonthHeaderEntry(section.title))
            var currentDay = Long.MIN_VALUE
            for (photo in section.items) {
                val zoned = Instant.ofEpochMilli(photo.userDate).atZone(ZoneOffset.UTC)
                val epochDay = zoned.toLocalDate().toEpochDay()
                if (epochDay != currentDay) {
                    currentDay = epochDay
                    list.add(
                        GridEntry.DayHeaderEntry(
                            key = "day-$epochDay-${section.title}",
                            label = DAY_FORMATTER.format(zoned),
                        )
                    )
                }
                list.add(GridEntry.CellEntry(photo))
            }
        }
        entries = list
        monthIndices = indices.toIntArray()
        monthTitles = titles.toTypedArray()
    }

    fun keyAt(index: Int): Any = when (val entry = entries[index]) {
        is GridEntry.MonthHeaderEntry -> "month-${entry.title}"
        is GridEntry.DayHeaderEntry -> entry.key
        is GridEntry.CellEntry -> entry.photo.fileId
    }

    fun isFullSpan(index: Int): Boolean = entries[index] !is GridEntry.CellEntry

    fun contentTypeAt(index: Int): String = when (entries[index]) {
        is GridEntry.MonthHeaderEntry -> "month-header"
        is GridEntry.DayHeaderEntry -> "day-header"
        is GridEntry.CellEntry -> "cell"
    }

    fun isMonthHeader(index: Int): Boolean =
        index in entries.indices && entries[index] is GridEntry.MonthHeaderEntry

    /** Title of the month that owns [index] — floor search over [monthIndices]. */
    fun titleFor(index: Int): String? {
        if (monthIndices.isEmpty()) return null
        var lo = 0
        var hi = monthIndices.size - 1
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (monthIndices[mid] <= index) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return monthTitles[found]
    }
}
