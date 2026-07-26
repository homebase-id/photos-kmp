@file:OptIn(ExperimentalMaterial3Api::class)

package id.homebase.photos.android.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import id.homebase.photos.android.ui.components.DAY_FORMATTER
import id.homebase.photos.android.ui.components.DaySubhead
import id.homebase.photos.android.ui.components.EmptyState
import id.homebase.photos.android.ui.components.ErrorState
import id.homebase.photos.android.ui.components.FooterLoading
import id.homebase.photos.android.ui.components.GRID_GAP
import id.homebase.photos.android.ui.components.MonthHeader
import id.homebase.photos.android.ui.components.PhotoGridCell
import id.homebase.photos.android.ui.components.SkeletonGrid
import id.homebase.photos.android.ui.components.gridColumnsFor
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.timeline.TimelineSection
import java.time.Instant
import java.time.ZoneOffset

/**
 * Shared scaffold for the three library destinations (Favorites / Archive / Trash — Batch D): a
 * back-arrow top bar swapped for [selectionTopBar] while photos are selected, an optional
 * [headerContent] banner (Trash's bin note), and a month/day photo grid with pagination and
 * pull-to-refresh. Mirrors the timeline's grid mechanics; callers own their own selection-bar
 * actions and copy (DRY, owner directive — one scaffold, not three near-identical screens).
 */
@Composable
fun LibraryStateScreen(
    title: String,
    gridTestTag: String,
    isLoading: Boolean,
    isPaginating: Boolean,
    sections: List<TimelineSection>,
    endReached: Boolean,
    error: String?,
    inSelectionMode: Boolean,
    selectedCount: Int,
    isSelected: (PhotoItem) -> Boolean,
    emptyTitle: String,
    emptyMessage: String,
    onBack: () -> Unit,
    onPhotoClick: (PhotoItem) -> Unit,
    onToggleSelection: (PhotoItem) -> Unit,
    onClearSelection: () -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    selectionTopBar: @Composable (count: Int, onClose: () -> Unit) -> Unit,
    imageLoader: ImageLoader?,
    modifier: Modifier = Modifier,
    headerContent: (@Composable () -> Unit)? = null,
) {
    // System back exits selection mode before it pops the screen (parity with the timeline).
    BackHandler(enabled = inSelectionMode) { onClearSelection() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (inSelectionMode) selectionTopBar(selectedCount, onClearSelection)
            else LibraryTopBar(title = title, onBack = onBack)
        },
    ) { innerPadding ->
        // The header banner (if any) sits above the grid, so only the bottom inset flows down.
        val contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            headerContent?.invoke()
            Box(modifier = Modifier.weight(1f)) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val columns = gridColumnsFor(maxWidth.value)
                    when {
                        isLoading && sections.isEmpty() ->
                            SkeletonGrid(
                                columns = columns,
                                innerPadding = contentPadding,
                                testTag = "$gridTestTag-skeleton",
                            )
                        error != null && sections.isEmpty() ->
                            ErrorState(
                                message = error,
                                onRetry = onRetry,
                                innerPadding = contentPadding,
                                title = "Couldn't load $title",
                                testTag = "$gridTestTag-error",
                            )
                        sections.isEmpty() ->
                            EmptyState(
                                title = emptyTitle,
                                message = emptyMessage,
                                innerPadding = contentPadding,
                                testTag = "$gridTestTag-empty",
                            )
                        else ->
                            LibraryGrid(
                                sections = sections,
                                columns = columns,
                                gridTestTag = gridTestTag,
                                innerPadding = contentPadding,
                                imageLoader = imageLoader,
                                isSelected = isSelected,
                                inSelectionMode = inSelectionMode,
                                isLoading = isLoading,
                                isPaginating = isPaginating,
                                endReached = endReached,
                                onPhotoClick = onPhotoClick,
                                onToggleSelection = onToggleSelection,
                                onLoadMore = onLoadMore,
                                onRefresh = onRefresh,
                            )
                    }
                }
            }
        }
    }
}

/** Back arrow (`library-back`) + destination name, over `surface` — same treatment as album detail. */
@Composable
private fun LibraryTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("library-back")) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/**
 * The month/day photo grid over [sections] — paginates near the end and supports pull-to-refresh.
 * In selection mode a tap toggles instead of opening the viewer (parity with the timeline).
 */
@Composable
private fun LibraryGrid(
    sections: List<TimelineSection>,
    columns: Int,
    gridTestTag: String,
    innerPadding: PaddingValues,
    imageLoader: ImageLoader?,
    isSelected: (PhotoItem) -> Boolean,
    inSelectionMode: Boolean,
    isLoading: Boolean,
    isPaginating: Boolean,
    endReached: Boolean,
    onPhotoClick: (PhotoItem) -> Unit,
    onToggleSelection: (PhotoItem) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    val model = remember(sections) { sections.map { it to dayGroupsOf(it) } }

    // Pagination with a prefetch margin of ~4 rows (parity with the timeline's PERF-11 budget).
    val shouldLoadMore by remember(model, columns) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= info.totalItemsCount - columns * 4
        }
    }
    LaunchedEffect(shouldLoadMore, endReached, isPaginating) {
        if (shouldLoadMore && !endReached && !isPaginating) onLoadMore()
    }

    PullToRefreshBox(
        isRefreshing = isLoading && sections.isNotEmpty(),
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding() + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
            verticalArrangement = Arrangement.spacedBy(GRID_GAP),
            modifier = Modifier
                .fillMaxSize()
                .testTag(gridTestTag),
        ) {
            model.forEach { (section, days) ->
                item(
                    key = "month-${section.title}",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "month-header",
                ) {
                    MonthHeader(title = section.title, modifier = Modifier.semantics { heading() })
                }
                days.forEach { day ->
                    item(
                        key = day.key,
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "day-header",
                    ) {
                        DaySubhead(label = day.label)
                    }
                    items(
                        items = day.photos,
                        key = { it.fileId.toString() },
                        contentType = { "cell" },
                    ) { photo ->
                        PhotoGridCell(
                            photo = photo,
                            imageLoader = imageLoader,
                            selected = isSelected(photo),
                            selectionMode = inSelectionMode,
                            onClick = {
                                if (inSelectionMode) onToggleSelection(photo) else onPhotoClick(photo)
                            },
                            onLongPress = { onToggleSelection(photo) },
                        )
                    }
                }
            }
            if (isPaginating) {
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
}

/** One day's run inside a month section, label preformatted for [DaySubhead]. */
private data class LibraryDayGroup(val key: String, val label: String, val photos: List<PhotoItem>)

private fun dayGroupsOf(section: TimelineSection): List<LibraryDayGroup> {
    val buckets = LinkedHashMap<Long, MutableList<PhotoItem>>() // keeps newest-first item order
    for (photo in section.items) {
        val epochDay = Instant.ofEpochMilli(photo.userDate).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
        buckets.getOrPut(epochDay) { mutableListOf() }.add(photo)
    }
    return buckets.map { (epochDay, photos) ->
        LibraryDayGroup(
            key = "day-$epochDay-${section.title}",
            label = DAY_FORMATTER.format(Instant.ofEpochMilli(photos.first().userDate).atZone(ZoneOffset.UTC)),
            photos = photos,
        )
    }
}
