package id.homebase.photos.android.ui.library

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import id.homebase.photos.android.ui.components.DeleteConfirmDialog
import id.homebase.photos.android.ui.components.SelectionTopBar
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.library.TrashEvent
import id.homebase.photos.library.TrashUiState
import id.homebase.photos.library.TrashViewModel

/**
 * Stateful Trash grid: renders the shared [TrashViewModel] over [LibraryStateScreen] and turns its
 * one-time [TrashEvent]s into snackbars (errors + the restore/permanent-delete counts).
 */
@Composable
fun TrashScreen(
    viewModel: TrashViewModel,
    onBack: () -> Unit,
    onPhotoClick: (PhotoItem) -> Unit = {},
    imageLoader: ImageLoader? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TrashEvent.Error -> snackbarHostState.showSnackbar(event.message)
                is TrashEvent.Restored -> snackbarHostState.showSnackbar("${event.succeeded} restored")
                is TrashEvent.PermanentlyDeleted ->
                    snackbarHostState.showSnackbar("${event.count} deleted forever")
            }
        }
    }

    TrashScreen(
        state = state,
        onBack = onBack,
        onPhotoClick = onPhotoClick,
        onToggleSelection = viewModel::toggleSelection,
        onClearSelection = viewModel::clearSelection,
        onLoadMore = viewModel::loadMore,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::refresh,
        onRestoreSelected = viewModel::restoreSelected,
        onPermanentDeleteSelected = viewModel::permanentDeleteSelected,
        imageLoader = imageLoader,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/**
 * Stateless Trash grid (`trash-grid`) — a bin-note banner above the grid, and a selection bar
 * carrying both Restore (`trash-restore`) and Delete forever (`trash-delete-forever`, confirmed
 * via [DeleteConfirmDialog] before it fires).
 */
@Composable
fun TrashScreen(
    state: TrashUiState,
    onBack: () -> Unit,
    onPhotoClick: (PhotoItem) -> Unit = {},
    onToggleSelection: (PhotoItem) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    onRestoreSelected: () -> Unit = {},
    onPermanentDeleteSelected: () -> Unit = {},
    imageLoader: ImageLoader? = null,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var showDeleteForeverDialog by remember { mutableStateOf(false) }
    val selectedCount = state.selectedIds.size

    LibraryStateScreen(
        title = "Trash",
        gridTestTag = "trash-grid",
        isLoading = state.isLoading,
        isPaginating = state.isPaginating,
        sections = state.sections,
        endReached = state.endReached,
        error = state.error,
        inSelectionMode = state.inSelectionMode,
        selectedCount = selectedCount,
        isSelected = state::isSelected,
        emptyTitle = "Trash is empty",
        emptyMessage = "Items you delete stay here before they're gone for good.",
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
                onAction = { showDeleteForeverDialog = true },
                actionIcon = Icons.Outlined.DeleteForever,
                actionLabel = "Delete forever",
                actionTag = "trash-delete-forever",
                extraActions = {
                    IconButton(onClick = onRestoreSelected, modifier = Modifier.testTag("trash-restore")) {
                        Icon(imageVector = Icons.Outlined.Restore, contentDescription = "Restore")
                    }
                },
            )
        },
        headerContent = { TrashHeaderNote() },
        imageLoader = imageLoader,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )

    if (showDeleteForeverDialog) {
        DeleteConfirmDialog(
            count = selectedCount,
            title = if (selectedCount == 1) "Delete forever?" else "Delete $selectedCount items forever?",
            message = "This can't be undone.",
            confirmLabel = "Delete forever",
            onConfirm = {
                showDeleteForeverDialog = false
                onPermanentDeleteSelected()
            },
            onDismiss = { showDeleteForeverDialog = false },
        )
    }
}

/** The bin note above the grid — verbatim copy per the Batch D plan. */
@Composable
private fun TrashHeaderNote() {
    Text(
        text = "Items stay in the bin until you delete them permanently.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("trash-header-note"),
    )
}
