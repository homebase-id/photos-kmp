@file:OptIn(ExperimentalUuidApi::class, ExperimentalMaterial3Api::class)

package id.homebase.photos.android.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil3.ImageLoader
import id.homebase.photos.albums.AlbumsEvent
import id.homebase.photos.albumsViewModel
import id.homebase.photos.android.ui.collections.AlbumDetailScreen
import id.homebase.photos.android.ui.collections.CollectionsScreen
import id.homebase.photos.android.ui.components.AlbumPickerSheet
import id.homebase.photos.android.ui.components.FloatingNavBar
import id.homebase.photos.android.ui.components.NameInputDialog
import id.homebase.photos.android.ui.create.CreateSheet
import id.homebase.photos.android.ui.library.ArchiveScreen
import id.homebase.photos.android.ui.library.FavoritesScreen
import id.homebase.photos.android.ui.library.TrashScreen
import id.homebase.photos.android.ui.nav.Route
import id.homebase.photos.android.ui.search.SearchScreen
import id.homebase.photos.android.ui.timeline.TimelineScreen
import id.homebase.photos.android.ui.viewer.ViewerScreen
import id.homebase.photos.archiveViewModel
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.favoritesViewModel
import id.homebase.photos.timeline.TimelineViewModel
import id.homebase.photos.trashViewModel
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Carries the viewer's photo list across a nav hop — too large/complex for a Bundle arg. */
private class ViewerBridge {
    var items: List<PhotoItem> = emptyList()
    // Host grid's close hook — refreshes its VM when the viewer deleted anything (contract B).
    var onClosed: (deletedAny: Boolean) -> Unit = {}
}

/** An in-flight "add these photos to an album" request; [onDone] lets the source clear its selection. */
private data class AddToAlbumRequest(val fileIds: List<Uuid>, val onDone: () -> Unit)

/**
 * App shell for the authenticated session. A real [NavHost] back-stack: Photos and Collections are
 * the two top-level feeds; AlbumDetail, Viewer and Search are push destinations. The Google-Photos
 * floating pill + Search button hover over the two feeds and auto-hide during timeline selection
 * and on pushed screens. Back closes the viewer, then the album, then returns to Photos.
 *
 * The shell also owns the two album flows that cut across screens (C3): the create dialog behind
 * the Create sheet, and the add-to-album picker opened from a Timeline selection or the Viewer.
 */
@Composable
fun AppShell(
    timelineViewModel: TimelineViewModel,
    imageLoader: ImageLoader,
    snackbarHostState: SnackbarHostState,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    // One albums VM for the shell's lifetime so Collections state survives tab switches — and so
    // the album detail screen renames/deletes through the same instance the grid renders.
    val albumsVm = remember { albumsViewModel() }
    val albumsState by albumsVm.state.collectAsStateWithLifecycle()
    val timelineState by timelineViewModel.state.collectAsStateWithLifecycle()
    val viewerBridge = remember { ViewerBridge() }
    val scope = rememberCoroutineScope()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val onTopLevel = currentRoute == Route.Photos.path || currentRoute == Route.Collections.path
    // Pill hides during selection (contract C5) and on every pushed screen.
    val shellVisible = onTopLevel && !timelineState.inSelectionMode

    var showCreateSheet by remember { mutableStateOf(false) }
    val createSheetState = rememberModalBottomSheetState()
    var showCreateAlbumDialog by remember { mutableStateOf(false) }

    var addRequest by remember { mutableStateOf<AddToAlbumRequest?>(null) }
    var showNewAlbumForAdd by remember { mutableStateOf(false) }
    val pickerSheetState = rememberModalBottomSheetState()

    // Album writes report through their own host so a snackbar shows on any destination.
    val albumSnackbar = remember { SnackbarHostState() }
    LaunchedEffect(albumsVm) {
        albumsVm.events.collect { event ->
            when (event) {
                is AlbumsEvent.Error -> albumSnackbar.showSnackbar(event.message)
                is AlbumsEvent.Busy ->
                    albumSnackbar.showSnackbar("Another album change is still finishing")
                is AlbumsEvent.Deleted -> albumSnackbar.showSnackbar("Album deleted")
                is AlbumsEvent.CoverSet -> albumSnackbar.showSnackbar("Cover updated")
                is AlbumsEvent.PhotosAdded -> albumSnackbar.showSnackbar(
                    if (event.failed > 0) "Added ${event.added}, ${event.failed} failed"
                    else "Added ${event.added} to album",
                )
                // Created/Renamed are already visible on screen — no snackbar noise.
                is AlbumsEvent.Created, is AlbumsEvent.Renamed -> Unit
            }
        }
    }

    fun openAlbum(albumId: Uuid) = navController.navigate(Route.AlbumDetail.path(albumId.toString()))

    // Feed switch uses the bottom-nav pop pattern so back from any feed lands on Photos.
    fun switchFeed(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NavHost(
            navController = navController,
            startDestination = Route.Photos.path,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Route.Photos.path) {
                TimelineScreen(
                    state = timelineState,
                    onPhotoClick = { photo ->
                        val idx = timelineState.pagedItems.indexOfFirst { it.fileId == photo.fileId }
                        if (idx >= 0) {
                            viewerBridge.items = timelineState.pagedItems
                            viewerBridge.onClosed = { deletedAny ->
                                if (deletedAny) timelineViewModel.refresh()
                            }
                            navController.navigate(Route.Viewer.path(idx))
                        }
                    },
                    onLoadMore = timelineViewModel::loadMore,
                    onRefresh = timelineViewModel::refresh,
                    onRetry = timelineViewModel::refresh,
                    onLogout = onLogout,
                    onToggleSelection = timelineViewModel::toggleSelection,
                    onClearSelection = timelineViewModel::clearSelection,
                    onDeleteSelected = timelineViewModel::deleteSelected,
                    onFavoriteSelected = timelineViewModel::favoriteSelected,
                    onArchiveSelected = timelineViewModel::archiveSelected,
                    onAddToAlbum = {
                        addRequest = AddToAlbumRequest(
                            fileIds = timelineState.selectedPhotos.map { it.fileId },
                            onDone = timelineViewModel::clearSelection,
                        )
                    },
                    imageLoader = imageLoader,
                    snackbarHostState = snackbarHostState,
                )
            }

            composable(Route.Collections.path) {
                CollectionsScreen(
                    viewModel = albumsVm,
                    onAlbumClick = { openAlbum(it.albumId) },
                    onFavoritesClick = { navController.navigate(Route.Favorites.path) },
                    onArchiveClick = { navController.navigate(Route.Archive.path) },
                    onTrashClick = { navController.navigate(Route.Trash.path) },
                    imageLoader = imageLoader,
                )
            }

            composable(Route.Favorites.path) {
                val favoritesVm = remember { favoritesViewModel() }
                FavoritesScreen(
                    viewModel = favoritesVm,
                    onBack = { navController.popBackStack() },
                    onPhotoClick = { photo ->
                        val items = favoritesVm.state.value.pagedItems
                        val idx = items.indexOfFirst { it.fileId == photo.fileId }
                        if (idx >= 0) {
                            viewerBridge.items = items
                            viewerBridge.onClosed = { deletedAny -> if (deletedAny) favoritesVm.refresh() }
                            navController.navigate(Route.Viewer.path(idx))
                        }
                    },
                    imageLoader = imageLoader,
                )
            }

            composable(Route.Archive.path) {
                val archiveVm = remember { archiveViewModel() }
                ArchiveScreen(
                    viewModel = archiveVm,
                    onBack = { navController.popBackStack() },
                    onPhotoClick = { photo ->
                        val items = archiveVm.state.value.pagedItems
                        val idx = items.indexOfFirst { it.fileId == photo.fileId }
                        if (idx >= 0) {
                            viewerBridge.items = items
                            viewerBridge.onClosed = { deletedAny -> if (deletedAny) archiveVm.refresh() }
                            navController.navigate(Route.Viewer.path(idx))
                        }
                    },
                    imageLoader = imageLoader,
                )
            }

            composable(Route.Trash.path) {
                val trashVm = remember { trashViewModel() }
                TrashScreen(
                    viewModel = trashVm,
                    onBack = { navController.popBackStack() },
                    onPhotoClick = { photo ->
                        val items = trashVm.state.value.pagedItems
                        val idx = items.indexOfFirst { it.fileId == photo.fileId }
                        if (idx >= 0) {
                            viewerBridge.items = items
                            viewerBridge.onClosed = { deletedAny -> if (deletedAny) trashVm.refresh() }
                            navController.navigate(Route.Viewer.path(idx))
                        }
                    },
                    imageLoader = imageLoader,
                )
            }

            composable(
                route = Route.AlbumDetail.path,
                arguments = listOf(navArgument(Route.AlbumDetail.ARG) { type = NavType.StringType }),
            ) { entry ->
                val albumId = entry.arguments?.getString(Route.AlbumDetail.ARG)
                // Re-derived from the albums VM, so a rename/set-cover lands in this screen's bar.
                val album = remember(albumId, albumsState.albums) {
                    albumsState.albums.firstOrNull { it.album.albumId.toString() == albumId }?.album
                }
                if (album == null) {
                    // Album not in the loaded list (deleted, or process death) — pop back.
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    AlbumDetailScreen(
                        album = album,
                        albumsViewModel = albumsVm,
                        onBack = { navController.popBackStack() },
                        onOpenViewer = { photos, index, refreshOnDelete ->
                            viewerBridge.items = photos
                            viewerBridge.onClosed = { deletedAny ->
                                if (deletedAny) refreshOnDelete()
                            }
                            navController.navigate(Route.Viewer.path(index))
                        },
                        imageLoader = imageLoader,
                    )
                }
            }

            composable(
                route = Route.Viewer.path,
                arguments = listOf(navArgument(Route.Viewer.ARG) { type = NavType.IntType }),
            ) { entry ->
                val index = entry.arguments?.getInt(Route.Viewer.ARG) ?: 0
                val items = viewerBridge.items
                if (items.isEmpty()) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    ViewerScreen(
                        items = items,
                        initialIndex = index,
                        imageLoader = imageLoader,
                        onDismiss = { deletedAny ->
                            viewerBridge.onClosed(deletedAny)
                            navController.popBackStack()
                        },
                        onAddToAlbum = { photo ->
                            addRequest = AddToAlbumRequest(listOf(photo.fileId)) {}
                        },
                    )
                }
            }

            composable(Route.Search.path) {
                SearchScreen(onBack = { navController.popBackStack() })
            }
        }

        AnimatedVisibility(
            visible = shellVisible,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            FloatingNavBar(
                currentRoute = currentRoute,
                onPhotos = { switchFeed(Route.Photos.path) },
                onCollections = { switchFeed(Route.Collections.path) },
                onCreate = { showCreateSheet = true },
                onSearch = { navController.navigate(Route.Search.path) },
            )
        }

        SnackbarHost(
            hostState = albumSnackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }

    if (showCreateSheet) {
        CreateSheet(
            sheetState = createSheetState,
            onDismiss = { showCreateSheet = false },
            onNewAlbum = {
                showCreateSheet = false
                showCreateAlbumDialog = true
            },
        )
    }

    // C3: create from the Create sheet → the new album opens straight away.
    if (showCreateAlbumDialog) {
        NameInputDialog(
            title = "New album",
            confirmLabel = "Create",
            testTag = "create-album-dialog",
            onConfirm = { name ->
                showCreateAlbumDialog = false
                scope.launch { albumsVm.createAlbumAndWait(name)?.let { openAlbum(it.albumId) } }
            },
            onDismiss = { showCreateAlbumDialog = false },
        )
    }

    // C3: add-to-album picker over whichever screen raised it.
    addRequest?.let { request ->
        if (showNewAlbumForAdd) {
            NameInputDialog(
                title = "New album",
                confirmLabel = "Create",
                testTag = "create-album-dialog",
                onConfirm = { name ->
                    showNewAlbumForAdd = false
                    addRequest = null
                    request.onDone()
                    scope.launch { albumsVm.createAlbumWithPhotosAndWait(name, request.fileIds) }
                },
                onDismiss = { showNewAlbumForAdd = false },
            )
        } else {
            AlbumPickerSheet(
                albums = albumsState.albums,
                sheetState = pickerSheetState,
                onAlbumSelected = { album ->
                    addRequest = null
                    request.onDone()
                    albumsVm.addToAlbum(album.albumId, request.fileIds)
                },
                onNewAlbum = { showNewAlbumForAdd = true },
                onDismiss = { addRequest = null },
                imageLoader = imageLoader,
            )
        }
    }
}
