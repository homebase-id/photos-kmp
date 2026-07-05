@file:OptIn(ExperimentalUuidApi::class, ExperimentalMaterial3Api::class)

package id.homebase.photos.android.ui.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import id.homebase.photos.android.ui.components.SkeletonGrid
import id.homebase.photos.domain.AlbumItem
import kotlin.uuid.ExperimentalUuidApi

/** Stateful Collections tab: renders the shared [AlbumsViewModel]'s album grid. */
@Composable
fun CollectionsScreen(
    viewModel: AlbumsViewModel,
    onAlbumClick: (AlbumItem) -> Unit,
    imageLoader: ImageLoader? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectionsScreen(
        state = state,
        onAlbumClick = onAlbumClick,
        onRetry = viewModel::refresh,
        imageLoader = imageLoader,
        modifier = modifier,
    )
}

/**
 * Stateless Collections tab: a "Collections" top bar over a 2-column album grid, with skeleton /
 * empty / error branches reusing the shared grid-state components. [imageLoader] optional so UI
 * tests render without a Coil graph.
 */
@Composable
fun CollectionsScreen(
    state: AlbumsUiState,
    onAlbumClick: (AlbumItem) -> Unit,
    onRetry: () -> Unit = {},
    imageLoader: ImageLoader? = null,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CollectionsTopBar() },
    ) { innerPadding ->
        when {
            state.isLoading && state.albums.isEmpty() ->
                SkeletonGrid(
                    columns = 2,
                    innerPadding = innerPadding,
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
                    innerPadding = innerPadding,
                    title = "Couldn't load albums",
                    testTag = "collections-error",
                )
            state.albums.isEmpty() ->
                EmptyState(
                    title = "No albums yet",
                    message = "Albums in your Homebase library will show up here.",
                    innerPadding = innerPadding,
                    testTag = "collections-empty",
                )
            else ->
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        bottom = innerPadding.calculateBottomPadding() + 16.dp,
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

/** "Collections" top bar — same minimal surface treatment as the Photos bar. */
@Composable
private fun CollectionsTopBar() {
    TopAppBar(
        title = { Text(text = "Collections", style = MaterialTheme.typography.titleMedium) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}
