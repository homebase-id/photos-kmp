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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import id.homebase.photos.albumsViewModel
import id.homebase.photos.android.ui.collections.AlbumDetailScreen
import id.homebase.photos.android.ui.collections.CollectionsScreen
import id.homebase.photos.android.ui.components.FloatingNavBar
import id.homebase.photos.android.ui.create.CreateSheet
import id.homebase.photos.android.ui.create.NewAlbumScreen
import id.homebase.photos.android.ui.nav.Route
import id.homebase.photos.android.ui.search.SearchScreen
import id.homebase.photos.android.ui.timeline.TimelineScreen
import id.homebase.photos.android.ui.viewer.ViewerScreen
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.timeline.TimelineViewModel
import kotlin.uuid.ExperimentalUuidApi

/** Carries the viewer's photo list across a nav hop — too large/complex for a Bundle arg. */
private class ViewerBridge {
    var items: List<PhotoItem> = emptyList()
    // Host grid's close hook — refreshes its VM when the viewer deleted anything (contract B).
    var onClosed: (deletedAny: Boolean) -> Unit = {}
}

/**
 * App shell for the authenticated session. A real [NavHost] back-stack replaces the old hoisted
 * viewer/album overlay state: Photos and Collections are the two top-level feeds; AlbumDetail, Viewer,
 * the New-album placeholder, and Search are push destinations. The Google-Photos floating pill +
 * Search button hover over the two feeds and auto-hide during timeline selection and on pushed screens.
 * Back closes the viewer, then the album, then returns to Photos — the natural pop order.
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
    // One albums VM for the shell's lifetime so Collections state survives tab switches.
    val albumsVm = remember { albumsViewModel() }
    val timelineState by timelineViewModel.state.collectAsStateWithLifecycle()
    val viewerBridge = remember { ViewerBridge() }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val onTopLevel = currentRoute == Route.Photos.path || currentRoute == Route.Collections.path
    // Pill hides during selection (contract C5) and on every pushed screen.
    val shellVisible = onTopLevel && !timelineState.inSelectionMode

    var showCreateSheet by remember { mutableStateOf(false) }
    val createSheetState = rememberModalBottomSheetState()

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
                    imageLoader = imageLoader,
                    snackbarHostState = snackbarHostState,
                )
            }

            composable(Route.Collections.path) {
                CollectionsScreen(
                    viewModel = albumsVm,
                    onAlbumClick = { navController.navigate(Route.AlbumDetail.path(it.albumId.toString())) },
                    imageLoader = imageLoader,
                )
            }

            composable(
                route = Route.AlbumDetail.path,
                arguments = listOf(navArgument(Route.AlbumDetail.ARG) { type = NavType.StringType }),
            ) { entry ->
                val albumId = entry.arguments?.getString(Route.AlbumDetail.ARG)
                val albumsState by albumsVm.state.collectAsStateWithLifecycle()
                val album = remember(albumId, albumsState.albums) {
                    albumsState.albums.firstOrNull { it.album.albumId.toString() == albumId }?.album
                }
                if (album == null) {
                    // Album not in the loaded list (e.g. process death) — nothing to show; pop back.
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    AlbumDetailScreen(
                        album = album,
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
                    )
                }
            }

            composable(Route.Create.path) {
                NewAlbumScreen(onBack = { navController.popBackStack() })
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
    }

    if (showCreateSheet) {
        CreateSheet(
            sheetState = createSheetState,
            onDismiss = { showCreateSheet = false },
            onNewAlbum = {
                showCreateSheet = false
                navController.navigate(Route.Create.path)
            },
        )
    }
}
