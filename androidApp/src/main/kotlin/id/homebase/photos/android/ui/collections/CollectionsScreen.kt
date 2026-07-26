@file:OptIn(ExperimentalUuidApi::class, ExperimentalMaterial3Api::class)

package id.homebase.photos.android.ui.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import id.homebase.photos.albums.AlbumsUiState
import id.homebase.photos.albums.AlbumsViewModel
import id.homebase.photos.android.ui.components.ALBUM_COVER_RADIUS
import id.homebase.photos.android.ui.components.AlbumCard
import id.homebase.photos.android.ui.components.EmptyState
import id.homebase.photos.android.ui.components.ErrorState
import id.homebase.photos.android.ui.components.LibraryRow
import id.homebase.photos.android.ui.components.NameInputDialog
import id.homebase.photos.android.ui.components.SkeletonGrid
import id.homebase.photos.domain.AlbumItem
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi

/** Stateful Collections tab: renders the shared [AlbumsViewModel]'s album grid. */
@Composable
fun CollectionsScreen(
    viewModel: AlbumsViewModel,
    onAlbumClick: (AlbumItem) -> Unit,
    onFavoritesClick: () -> Unit = {},
    onArchiveClick: () -> Unit = {},
    onTrashClick: () -> Unit = {},
    imageLoader: ImageLoader? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    CollectionsScreen(
        state = state,
        onAlbumClick = onAlbumClick,
        onFavoritesClick = onFavoritesClick,
        onArchiveClick = onArchiveClick,
        onTrashClick = onTrashClick,
        onRetry = viewModel::refresh,
        // C3: a fresh album opens straight away, the way Google Photos does.
        onCreateAlbum = { name ->
            scope.launch { viewModel.createAlbumAndWait(name)?.let(onAlbumClick) }
        },
        imageLoader = imageLoader,
        modifier = modifier,
    )
}

/**
 * Stateless Collections hub (C1): a "Collections" top bar with a `+` create action, the library
 * section rows (Favorites / Archive / Trash open Batch D screens; Utilities stays "Soon"), and
 * below them the 2-column album grid with its skeleton / empty / error branches. [imageLoader]
 * optional so UI tests render without a Coil graph.
 */
@Composable
fun CollectionsScreen(
    state: AlbumsUiState,
    onAlbumClick: (AlbumItem) -> Unit,
    onFavoritesClick: () -> Unit = {},
    onArchiveClick: () -> Unit = {},
    onTrashClick: () -> Unit = {},
    onRetry: () -> Unit = {},
    onCreateAlbum: (String) -> Unit = {},
    imageLoader: ImageLoader? = null,
    modifier: Modifier = Modifier,
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CollectionsTopBar(onCreate = { showCreateDialog = true }) },
    ) { innerPadding ->
        // The library rows sit above the grid, so only the bottom inset flows into the branches.
        val contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            LibrarySection(
                onFavoritesClick = onFavoritesClick,
                onArchiveClick = onArchiveClick,
                onTrashClick = onTrashClick,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoading && state.albums.isEmpty() ->
                        SkeletonGrid(
                            columns = 2,
                            innerPadding = contentPadding,
                            cellCount = 6,
                            gap = 12.dp,
                            cellShape = RoundedCornerShape(ALBUM_COVER_RADIUS),
                            testTag = "collections-skeleton",
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    state.error != null && state.albums.isEmpty() ->
                        ErrorState(
                            message = state.error,
                            onRetry = onRetry,
                            innerPadding = contentPadding,
                            title = "Couldn't load albums",
                            testTag = "collections-error",
                        )
                    state.albums.isEmpty() ->
                        EmptyState(
                            title = "No albums yet",
                            message = "Tap + to make one, or add photos to an album from the timeline.",
                            innerPadding = contentPadding,
                            testTag = "collections-empty",
                        )
                    else ->
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 8.dp,
                                bottom = contentPadding.calculateBottomPadding() + 16.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("collections-grid"),
                        ) {
                            items(
                                items = state.albums,
                                key = { it.album.fileId.toString() },
                                contentType = { "album" },
                            ) { summary ->
                                AlbumCard(
                                    summary = summary,
                                    onClick = { onAlbumClick(summary.album) },
                                    imageLoader = imageLoader,
                                )
                            }
                        }
                }
            }
        }
    }

    if (showCreateDialog) {
        NameInputDialog(
            title = "New album",
            confirmLabel = "Create",
            testTag = "create-album-dialog",
            onConfirm = { name ->
                showCreateDialog = false
                onCreateAlbum(name)
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

/**
 * The library destinations above the album grid. Favorites / Archive / Trash open their Batch D
 * screens; Utilities has none yet, so it stays dimmed with a "Soon" note rather than a dead button.
 */
@Composable
private fun LibrarySection(
    onFavoritesClick: () -> Unit,
    onArchiveClick: () -> Unit,
    onTrashClick: () -> Unit,
) {
    Column {
        LibraryRow(
            icon = Icons.Outlined.FavoriteBorder,
            label = "Favorites",
            testTag = "collections-library-row-favorites",
            onClick = onFavoritesClick,
        )
        LibraryRow(
            icon = Icons.Outlined.Archive,
            label = "Archive",
            testTag = "collections-library-row-archive",
            onClick = onArchiveClick,
        )
        LibraryRow(
            icon = Icons.Outlined.Delete,
            label = "Trash",
            testTag = "collections-library-row-trash",
            onClick = onTrashClick,
        )
        LibraryRow(
            icon = Icons.Outlined.Build,
            label = "Utilities",
            testTag = "collections-library-row-utilities",
            enabled = false,
            trailingLabel = SOON,
        )
    }
}

private const val SOON = "Soon"

/** "Collections" top bar — same minimal surface treatment as the Photos bar, plus `+` create. */
@Composable
private fun CollectionsTopBar(onCreate: () -> Unit) {
    TopAppBar(
        title = { Text(text = "Collections", style = MaterialTheme.typography.titleMedium) },
        actions = {
            IconButton(onClick = onCreate, modifier = Modifier.testTag("collections-create")) {
                Icon(imageVector = Icons.Rounded.Add, contentDescription = "New album")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}
