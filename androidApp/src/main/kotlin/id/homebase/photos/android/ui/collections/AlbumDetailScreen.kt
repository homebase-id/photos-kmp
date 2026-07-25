@file:OptIn(ExperimentalUuidApi::class, ExperimentalMaterial3Api::class)

package id.homebase.photos.android.ui.collections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import id.homebase.photos.albumDetailViewModel
import id.homebase.photos.albums.AlbumDetailUiState
import id.homebase.photos.albums.AlbumDetailViewModel
import id.homebase.photos.albums.AlbumsViewModel
import id.homebase.photos.android.ui.components.AlbumOverflowMenu
import id.homebase.photos.android.ui.components.DAY_FORMATTER
import id.homebase.photos.android.ui.components.DaySubhead
import id.homebase.photos.android.ui.components.DeleteConfirmDialog
import id.homebase.photos.android.ui.components.EmptyState
import id.homebase.photos.android.ui.components.ErrorState
import id.homebase.photos.android.ui.components.GRID_GAP
import id.homebase.photos.android.ui.components.MonthHeader
import id.homebase.photos.android.ui.components.NameInputDialog
import id.homebase.photos.android.ui.components.PhotoGridCell
import id.homebase.photos.android.ui.components.SelectionTopBar
import id.homebase.photos.android.ui.components.SkeletonGrid
import id.homebase.photos.android.ui.components.gridColumnsFor
import id.homebase.photos.domain.AlbumItem
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.timeline.TimelineSection
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.ExperimentalUuidApi

/**
 * Stateful album detail: owns the per-album shared ViewModel (selection + remove-from-album) and
 * maps a photo tap to an index into the album's flat list for the viewer pager. Album-level writes
 * (rename / delete / set-cover) live on [albumsViewModel] — the shell's instance, so a rename
 * reaches the grid AND flows back into [album], keeping this bar's title honest.
 */
@Composable
fun AlbumDetailScreen(
    album: AlbumItem,
    albumsViewModel: AlbumsViewModel,
    onBack: () -> Unit,
    onOpenViewer: (photos: List<PhotoItem>, index: Int, refreshOnDelete: () -> Unit) -> Unit,
    imageLoader: ImageLoader? = null,
    modifier: Modifier = Modifier,
    // ponytail: raw factory VM remembered per album, never store-cleared — fine for one open album.
    viewModel: AlbumDetailViewModel = remember(album.fileId) { albumDetailViewModel(album) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AlbumDetailScreen(
        state = state,
        title = album.name.ifBlank { state.title },
        onBack = onBack,
        onPhotoClick = { photo ->
            state.photos.indexOfFirst { it.fileId == photo.fileId }
                .takeIf { it >= 0 }
                ?.let { index -> onOpenViewer(state.photos, index, viewModel::refresh) }
        },
        onRetry = viewModel::refresh,
        onToggleSelection = viewModel::toggleSelection,
        onClearSelection = viewModel::clearSelection,
        onRemoveSelected = viewModel::removeSelected,
        onRename = { newName -> albumsViewModel.rename(album, newName) },
        onDeleteAlbum = {
            albumsViewModel.delete(album)
            onBack() // the album is gone; nothing left to show here
        },
        onSetCover = { photo ->
            albumsViewModel.setCover(album, photo.fileId)
            viewModel.clearSelection()
        },
        imageLoader = imageLoader,
        modifier = modifier,
    )
}

/**
 * Stateless album detail (C2): back arrow + album name + the album overflow menu over the
 * month/day photo grid. Long-press enters selection mode, which swaps in the shared
 * [SelectionTopBar] with remove-from-album as its primary action (the photos themselves survive).
 * Reuses the timeline's header and cell components for side-by-side parity.
 */
@Composable
fun AlbumDetailScreen(
    state: AlbumDetailUiState,
    onBack: () -> Unit,
    title: String = state.title,
    onPhotoClick: (PhotoItem) -> Unit = {},
    onRetry: () -> Unit = {},
    onToggleSelection: (PhotoItem) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onRemoveSelected: () -> Unit = {},
    onRename: (String) -> Unit = {},
    onDeleteAlbum: () -> Unit = {},
    onSetCover: (PhotoItem) -> Unit = {},
    imageLoader: ImageLoader? = null,
    modifier: Modifier = Modifier,
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // System back exits selection mode before it pops the screen (parity with the timeline).
    BackHandler(enabled = state.inSelectionMode) { onClearSelection() }

    val coverCandidate = state.selectedPhotos.singleOrNull()
    val menu: @Composable () -> Unit = {
        AlbumOverflowMenu(
            onRename = { showRenameDialog = true },
            onSetCover = { coverCandidate?.let(onSetCover) },
            onDelete = { showDeleteDialog = true },
            setCoverEnabled = coverCandidate != null,
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (state.inSelectionMode) {
                SelectionTopBar(
                    count = state.selectedIds.size,
                    onClose = onClearSelection,
                    onAction = onRemoveSelected,
                    actionIcon = Icons.Outlined.RemoveCircleOutline,
                    actionLabel = "Remove from album",
                    actionTag = "album-remove",
                    extraActions = { menu() },
                )
            } else {
                AlbumDetailTopBar(title = title, onBack = onBack, menu = menu)
            }
        },
    ) { innerPadding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val columns = gridColumnsFor(maxWidth.value)
            when {
                state.isLoading && state.sections.isEmpty() ->
                    SkeletonGrid(columns = columns, innerPadding = innerPadding, testTag = "album-detail-skeleton")
                state.error != null && state.sections.isEmpty() ->
                    ErrorState(
                        message = state.error,
                        onRetry = onRetry,
                        innerPadding = innerPadding,
                        title = "Couldn't load album",
                        testTag = "album-detail-error",
                    )
                state.sections.isEmpty() ->
                    EmptyState(
                        title = "Nothing here yet",
                        message = "Photos added to this album will show up here.",
                        innerPadding = innerPadding,
                        testTag = "album-detail-empty",
                    )
                else ->
                    AlbumPhotoGrid(
                        state = state,
                        columns = columns,
                        innerPadding = innerPadding,
                        imageLoader = imageLoader,
                        onPhotoClick = onPhotoClick,
                        onToggleSelection = onToggleSelection,
                    )
            }
        }
    }

    if (showRenameDialog) {
        NameInputDialog(
            title = "Rename album",
            confirmLabel = "Rename",
            testTag = "album-rename-dialog",
            initialName = title,
            onConfirm = { newName ->
                showRenameDialog = false
                onRename(newName)
            },
            onDismiss = { showRenameDialog = false },
        )
    }
    if (showDeleteDialog) {
        DeleteConfirmDialog(
            count = 1,
            title = "Delete this album?",
            message = "The album goes away. Its photos stay in your library.",
            onConfirm = {
                showDeleteDialog = false
                onDeleteAlbum()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

/** Back arrow (`album-back`) + album name + overflow menu, over `surface`. */
@Composable
private fun AlbumDetailTopBar(title: String, onBack: () -> Unit, menu: @Composable () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("album-title"),
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("album-back")) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = { menu() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/**
 * Month/day grid over the album's sections — the timeline look without pagination or the sticky
 * overlay (albums load fully; the shared repository caps them well under a page). In selection
 * mode a tap toggles instead of opening the viewer.
 */
@Composable
private fun AlbumPhotoGrid(
    state: AlbumDetailUiState,
    columns: Int,
    innerPadding: PaddingValues,
    imageLoader: ImageLoader?,
    onPhotoClick: (PhotoItem) -> Unit,
    onToggleSelection: (PhotoItem) -> Unit,
) {
    val model = remember(state.sections) { state.sections.map { it to dayGroupsOf(it) } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
        verticalArrangement = Arrangement.spacedBy(GRID_GAP),
        modifier = Modifier
            .fillMaxSize()
            .testTag("album-detail-grid"),
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
    }
}

/** One day's run inside a month section, label preformatted for [DaySubhead]. */
private data class DayGroup(val key: String, val label: String, val photos: List<PhotoItem>)

private fun dayGroupsOf(section: TimelineSection): List<DayGroup> {
    val buckets = LinkedHashMap<Long, MutableList<PhotoItem>>() // keeps newest-first item order
    for (photo in section.items) {
        val epochDay = Instant.ofEpochMilli(photo.userDate).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
        buckets.getOrPut(epochDay) { mutableListOf() }.add(photo)
    }
    return buckets.map { (epochDay, photos) ->
        DayGroup(
            key = "day-$epochDay-${section.title}",
            label = DAY_FORMATTER.format(Instant.ofEpochMilli(photos.first().userDate).atZone(ZoneOffset.UTC)),
            photos = photos,
        )
    }
}
