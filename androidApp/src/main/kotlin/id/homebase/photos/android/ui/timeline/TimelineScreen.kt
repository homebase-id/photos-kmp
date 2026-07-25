@file:OptIn(ExperimentalUuidApi::class, ExperimentalMaterial3Api::class)

package id.homebase.photos.android.ui.timeline

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import id.homebase.photos.android.ui.components.CARD_CLEARANCE
import id.homebase.photos.android.ui.components.DAY_FORMATTER
import id.homebase.photos.android.ui.components.DaySubhead
import id.homebase.photos.android.ui.components.EmptyState
import id.homebase.photos.android.ui.components.ErrorState
import id.homebase.photos.android.ui.components.FooterLoading
import id.homebase.photos.android.ui.components.GRID_GAP
import id.homebase.photos.android.ui.components.MonthHeader
import id.homebase.photos.android.ui.components.PhotoGridCell
import id.homebase.photos.android.ui.components.PhotosTopBar
import id.homebase.photos.android.ui.components.SelectionTopBar
import id.homebase.photos.android.ui.components.SkeletonGrid
import id.homebase.photos.android.ui.components.gridColumnsFor
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.timeline.TimelineSection
import id.homebase.photos.timeline.TimelineUiState
import id.homebase.photos.timeline.TimelineViewModel
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.ExperimentalUuidApi

/**
 * Stateful timeline entry point. Collects the shared [TimelineViewModel]'s [TimelineUiState]
 * and renders the photo grid. The [imageLoader] is the Homebase-wired Coil loader
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
        onToggleSelection = viewModel::toggleSelection,
        onClearSelection = viewModel::clearSelection,
        onDeleteSelected = viewModel::deleteSelected,
        imageLoader = imageLoader,
        modifier = modifier,
    )
}

/**
 * Stateless timeline screen. A [Scaffold] carries the "Photos" top bar (swapped for the selection
 * bar while photos are selected — contract C5) and a snackbar host; inside it, one of four content
 * branches renders — skeleton (first load), empty, error, or the photo grid. Edge-to-edge: the grid
 * draws under the system bars, applying the scaffold insets as content padding so items sit below the app bar
 * (design-system §5.2). [imageLoader] is optional so UI tests can render layout/headers without a
 * Coil graph.
 */
@Composable
fun TimelineScreen(
    state: TimelineUiState,
    onPhotoClick: (PhotoItem) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    onLogout: () -> Unit = {},
    onToggleSelection: (PhotoItem) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    imageLoader: ImageLoader? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val hasContent = state.sections.isNotEmpty()

    // Ephemeral UI state — the two confirmations. Logout runs via [onLogout] (hoisted to the
    // Activity's lifecycleScope); delete runs via [onDeleteSelected] (shared VM).
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // System back exits selection mode before anything else (C5).
    BackHandler(enabled = state.inSelectionMode) { onClearSelection() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (state.inSelectionMode) {
                SelectionTopBar(
                    count = state.selectedIds.size,
                    onClose = onClearSelection,
                    onDelete = { showDeleteDialog = true },
                )
            } else {
                PhotosTopBar(
                    scrolled = gridState.canScrollBackward,
                    onAccountClick = { showLogoutDialog = true },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val columns = gridColumnsFor(maxWidth.value)
            when {
                state.isLoading && !hasContent ->
                    SkeletonGrid(columns = columns, innerPadding = innerPadding)
                !state.isLoading && !hasContent && state.error != null ->
                    ErrorState(message = state.error, onRetry = onRetry, innerPadding = innerPadding)
                !hasContent ->
                    EmptyState(
                        title = "No photos yet",
                        message = "Your photos will appear here once they sync.",
                        innerPadding = innerPadding,
                    )
                else ->
                    TimelineGrid(
                        state = state,
                        columns = columns,
                        gridState = gridState,
                        innerPadding = innerPadding,
                        imageLoader = imageLoader,
                        onPhotoClick = onPhotoClick,
                        onToggleSelection = onToggleSelection,
                        onLoadMore = onLoadMore,
                        onRefresh = onRefresh,
                    )
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
    if (showDeleteDialog) {
        DeleteConfirmDialog(
            count = state.selectedIds.size,
            onConfirm = {
                showDeleteDialog = false
                onDeleteSelected()
            },
            onDismiss = { showDeleteDialog = false },
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

/** Delete confirmation per contract C5 — destructive confirm tagged `delete-confirm`. */
@Composable
private fun DeleteConfirmDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Delete 1 item?" else "Delete $count items?") },
        text = { Text("They'll be removed from your Homebase photo library.") },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("delete-confirm")) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * The timeline grid. Builds a [TimelineRenderModel] once per `sections`/`columns` change,
 * pins the enclosing month over the top of the grid, paginates near the end, and supports
 * pull-to-refresh. In selection mode a cell tap toggles instead of opening the viewer (C5).
 */
@Composable
private fun TimelineGrid(
    state: TimelineUiState,
    columns: Int,
    gridState: LazyGridState,
    innerPadding: PaddingValues,
    imageLoader: ImageLoader?,
    onPhotoClick: (PhotoItem) -> Unit,
    onToggleSelection: (PhotoItem) -> Unit,
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
                        is GridEntry.CellEntry -> {
                            val photo = entry.photo
                            PhotoGridCell(
                                photo = photo,
                                imageLoader = imageLoader,
                                selected = state.isSelected(photo),
                                selectionMode = state.inSelectionMode,
                                onClick = {
                                    if (state.inSelectionMode) onToggleSelection(photo) else onPhotoClick(photo)
                                },
                                onLongPress = { onToggleSelection(photo) },
                            )
                        }
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

        // Google-Photos floating date chip — visible only while no month header is at the top.
        overlayTitle?.let { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = innerPadding.calculateTopPadding() + 8.dp, start = 12.dp)
                    .shadow(2.dp, CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("timeline-month-overlay"),
            )
        }
    }
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
