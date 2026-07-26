package id.homebase.photos.android.ui.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import id.homebase.photos.android.ui.components.SelectionTopBar
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.library.ArchiveUiState
import id.homebase.photos.library.ArchiveViewModel

/** Stateful Archive grid: renders the shared [ArchiveViewModel] over [LibraryStateScreen]. */
@Composable
fun ArchiveScreen(
    viewModel: ArchiveViewModel,
    onBack: () -> Unit,
    onPhotoClick: (PhotoItem) -> Unit = {},
    imageLoader: ImageLoader? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ArchiveScreen(
        state = state,
        onBack = onBack,
        onPhotoClick = onPhotoClick,
        onToggleSelection = viewModel::toggleSelection,
        onClearSelection = viewModel::clearSelection,
        onLoadMore = viewModel::loadMore,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::refresh,
        onUnarchiveSelected = viewModel::unarchiveSelected,
        imageLoader = imageLoader,
        modifier = modifier,
    )
}

/** Stateless Archive grid (`archive-grid`) — selection's one action is unarchive. */
@Composable
fun ArchiveScreen(
    state: ArchiveUiState,
    onBack: () -> Unit,
    onPhotoClick: (PhotoItem) -> Unit = {},
    onToggleSelection: (PhotoItem) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    onUnarchiveSelected: () -> Unit = {},
    imageLoader: ImageLoader? = null,
    modifier: Modifier = Modifier,
) {
    LibraryStateScreen(
        title = "Archive",
        gridTestTag = "archive-grid",
        isLoading = state.isLoading,
        isPaginating = state.isPaginating,
        sections = state.sections,
        endReached = state.endReached,
        error = state.error,
        inSelectionMode = state.inSelectionMode,
        selectedCount = state.selectedIds.size,
        isSelected = state::isSelected,
        emptyTitle = "No archived photos",
        emptyMessage = "Photos you archive leave the main timeline and show up here.",
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
                onAction = onUnarchiveSelected,
                actionIcon = Icons.Outlined.Unarchive,
                actionLabel = "Unarchive",
                actionTag = "archive-unarchive",
            )
        },
        imageLoader = imageLoader,
        modifier = modifier,
    )
}
