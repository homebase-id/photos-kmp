@file:OptIn(ExperimentalUuidApi::class, ExperimentalLayoutApi::class)

package id.homebase.photos.android.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import id.homebase.photos.albumsViewModel
import id.homebase.photos.android.ui.collections.AlbumDetailScreen
import id.homebase.photos.android.ui.collections.CollectionsScreen
import id.homebase.photos.android.ui.components.HomeBottomBar
import id.homebase.photos.android.ui.components.HomeTab
import id.homebase.photos.android.ui.timeline.TimelineScreen
import id.homebase.photos.domain.AlbumItem
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.timeline.TimelineViewModel
import kotlin.uuid.ExperimentalUuidApi

/**
 * Two-tab home scaffold. Photos hosts the existing timeline (backup card slot intact); Collections
 * hosts the albums grid and drills into album detail. The bottom bar hides while the timeline is
 * in selection mode (contract C5) and while an album is open, so both read as focused screens.
 */
@Composable
fun HomeScreen(
    timelineViewModel: TimelineViewModel,
    imageLoader: ImageLoader,
    snackbarHostState: SnackbarHostState,
    onPhotoClick: (PhotoItem) -> Unit,
    onOpenAlbumPhoto: (photos: List<PhotoItem>, index: Int) -> Unit,
    onLogout: () -> Unit,
    backupCard: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.Photos) }
    // ponytail: plain state, not saveable — a config change re-lands on the Collections list.
    var openAlbum by remember { mutableStateOf<AlbumItem?>(null) }

    val timelineState by timelineViewModel.state.collectAsStateWithLifecycle()
    // One albums VM for the screen's lifetime so Collections state survives tab switches.
    val albumsVm = remember { albumsViewModel() }

    BackHandler(enabled = openAlbum != null) { openAlbum = null }

    val bottomBarVisible = !timelineState.inSelectionMode && openAlbum == null
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // Inner screens keep owning the system insets — the outer scaffold only adds the bar.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (bottomBarVisible) {
                HomeBottomBar(selectedTab = tab, onTabSelected = { tab = it })
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            when (tab) {
                HomeTab.Photos ->
                    TimelineScreen(
                        state = timelineState,
                        onPhotoClick = onPhotoClick,
                        onLoadMore = timelineViewModel::loadMore,
                        onRefresh = timelineViewModel::refresh,
                        onRetry = timelineViewModel::refresh,
                        onLogout = onLogout,
                        onToggleSelection = timelineViewModel::toggleSelection,
                        onClearSelection = timelineViewModel::clearSelection,
                        onDeleteSelected = timelineViewModel::deleteSelected,
                        imageLoader = imageLoader,
                        snackbarHostState = snackbarHostState,
                        backupCard = backupCard,
                    )
                HomeTab.Collections -> {
                    val album = openAlbum
                    if (album == null) {
                        CollectionsScreen(
                            viewModel = albumsVm,
                            onAlbumClick = { openAlbum = it },
                            imageLoader = imageLoader,
                        )
                    } else {
                        AlbumDetailScreen(
                            album = album,
                            onBack = { openAlbum = null },
                            onOpenViewer = onOpenAlbumPhoto,
                            imageLoader = imageLoader,
                        )
                    }
                }
            }
        }
    }
}
