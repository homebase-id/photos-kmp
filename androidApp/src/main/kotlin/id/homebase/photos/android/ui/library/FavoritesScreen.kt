package id.homebase.photos.android.ui.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HeartBroken
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import id.homebase.photos.android.ui.components.SelectionTopBar
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.library.FavoritesUiState
import id.homebase.photos.library.FavoritesViewModel

/** Stateful Favorites grid: renders the shared [FavoritesViewModel] over [LibraryStateScreen]. */
@Composable
fun FavoritesScreen(
    viewModel: FavoritesViewModel,
    onBack: () -> Unit,
    onPhotoClick: (PhotoItem) -> Unit = {},
    imageLoader: ImageLoader? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FavoritesScreen(
        state = state,
        onBack = onBack,
        onPhotoClick = onPhotoClick,
        onToggleSelection = viewModel::toggleSelection,
        onClearSelection = viewModel::clearSelection,
        onLoadMore = viewModel::loadMore,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::refresh,
        onUnfavoriteSelected = viewModel::unfavoriteSelected,
        imageLoader = imageLoader,
        modifier = modifier,
    )
}

/** Stateless Favorites grid (`favorites-grid`) — selection's one action is unfavorite. */
@Composable
fun FavoritesScreen(
    state: FavoritesUiState,
    onBack: () -> Unit,
    onPhotoClick: (PhotoItem) -> Unit = {},
    onToggleSelection: (PhotoItem) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    onUnfavoriteSelected: () -> Unit = {},
    imageLoader: ImageLoader? = null,
    modifier: Modifier = Modifier,
) {
    LibraryStateScreen(
        title = "Favorites",
        gridTestTag = "favorites-grid",
        isLoading = state.isLoading,
        isPaginating = state.isPaginating,
        sections = state.sections,
        endReached = state.endReached,
        error = state.error,
        inSelectionMode = state.inSelectionMode,
        selectedCount = state.selectedIds.size,
        isSelected = state::isSelected,
        emptyTitle = "No favorites yet",
        emptyMessage = "Tap the heart on a photo to add it here.",
        onBack = onBack,
        onPhotoClick = onPhotoClick,
        onToggleSelection = onToggleSelection,
        onClearSelection = onClearSelection,
        onLoadMore = onLoadMore,
        onRefresh = onRefresh,
        onRetry = onRetry,
        selectionTopBar = { count, onClose ->
            SelectionTopBar(
                count = count,
                onClose = onClose,
                onAction = onUnfavoriteSelected,
                actionIcon = Icons.Outlined.HeartBroken,
                actionLabel = "Remove from favorites",
                actionTag = "favorites-unfavorite",
            )
        },
        imageLoader = imageLoader,
        modifier = modifier,
    )
}
