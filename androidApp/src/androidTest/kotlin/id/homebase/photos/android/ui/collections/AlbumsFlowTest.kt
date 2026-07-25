@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.photos.android.ui.collections

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.homebase.photos.albums.AlbumDetailViewModel
import id.homebase.photos.albums.AlbumsViewModel
import id.homebase.photos.android.ui.theme.PhotosTheme
import id.homebase.photos.domain.AlbumItem
import id.homebase.photos.domain.PhotoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Compose UI flow-tests for Batch C's album management, driving the REAL shared ViewModels over
 * [FakeAlbumsRepository] (no Koin graph, no network): create an album → it lands in the grid;
 * select a photo in an album → remove → the count drops; rename → the detail bar's title follows.
 */
@RunWith(AndroidJUnit4::class)
class AlbumsFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun photoItem(seed: Int): PhotoItem {
        val id = Uuid.random()
        return PhotoItem(
            fileId = id,
            uniqueId = id,
            userDate = 1_718_000_000_000L + seed * 86_400_000L,
            isVideo = false,
            pixelWidth = 225,
            pixelHeight = 300,
            previewPlaceholder = null,
            driveId = Uuid.random(),
            payloadKey = "dflt_key",
        )
    }

    private fun album(name: String) = AlbumItem(
        fileId = Uuid.random(),
        albumId = Uuid.random(),
        name = name,
        coverFileId = null,
    )

    private fun cellCount(): Int =
        composeRule.onAllNodesWithTag("timeline-cell", useUnmergedTree = true)
            .fetchSemanticsNodes().size

    @Test
    fun createAlbum_fromTheHub_appearsInTheGrid_andOpens() {
        val repo = FakeAlbumsRepository(albums = listOf(album("Hikes")))
        val albumsVm = AlbumsViewModel(repo)
        var opened: AlbumItem? = null

        composeRule.setContent {
            PhotosTheme {
                CollectionsScreen(viewModel = albumsVm, onAlbumClick = { opened = it })
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("album-card").fetchSemanticsNodes().size == 1
        }

        composeRule.onNodeWithTag("collections-create").performClick()
        composeRule.onNodeWithTag("name-dialog-field").performTextInput("Trips")
        composeRule.onNodeWithTag("name-dialog-confirm").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("album-card").fetchSemanticsNodes().size == 2
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { opened != null }

        composeRule.onAllNodesWithText("Trips").onFirst().assertExists()
        assertEquals("Trips", opened?.name)
    }

    @Test
    fun selectThenRemove_dropsThePhotoFromTheAlbum() {
        val photos = listOf(photoItem(0), photoItem(1), photoItem(2))
        val target = album("Hikes")
        val repo = FakeAlbumsRepository(
            albums = listOf(target),
            photos = mapOf(target.albumId to photos),
        )
        val albumsVm = AlbumsViewModel(repo)
        val detailVm = AlbumDetailViewModel(target, repo)

        composeRule.setContent {
            PhotosTheme {
                AlbumDetailScreen(
                    album = target,
                    albumsViewModel = albumsVm,
                    onBack = {},
                    onOpenViewer = { _, _, _ -> },
                    viewModel = detailVm,
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { cellCount() == 3 }

        composeRule.onAllNodesWithTag("timeline-cell", useUnmergedTree = true)
            .onFirst()
            .performTouchInput { longClick() }

        composeRule.onNodeWithTag("selection-topbar").assertExists()
        composeRule.onNodeWithTag("album-remove").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { detailVm.state.value.photos.size == 2 }
        composeRule.waitUntil(timeoutMillis = 5_000) { cellCount() == 2 }
        assertTrue(detailVm.state.value.selectedIds.isEmpty())
    }

    @Test
    fun renameFromTheOverflowMenu_reflectsInTheTitle() {
        val target = album("Hikes")
        val repo = FakeAlbumsRepository(
            albums = listOf(target),
            photos = mapOf(target.albumId to listOf(photoItem(0))),
        )
        val albumsVm = AlbumsViewModel(repo)
        val detailVm = AlbumDetailViewModel(target, repo)

        composeRule.setContent {
            PhotosTheme {
                // Mirrors the shell: the album is re-derived from the albums VM, so a rename flows
                // back into this screen instead of leaving a stale title behind.
                val albumsState by albumsVm.state.collectAsStateWithLifecycle()
                val current = albumsState.albums
                    .firstOrNull { it.album.fileId == target.fileId }?.album ?: target
                AlbumDetailScreen(
                    album = current,
                    albumsViewModel = albumsVm,
                    onBack = {},
                    onOpenViewer = { _, _, _ -> },
                    viewModel = detailVm,
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { albumsVm.state.value.albums.size == 1 }

        composeRule.onNodeWithTag("album-menu").performClick()
        composeRule.onNodeWithTag("album-rename").performClick()
        composeRule.onNodeWithTag("name-dialog-field").performTextReplacement("Trails")
        composeRule.onNodeWithTag("name-dialog-confirm").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            albumsVm.state.value.albums.firstOrNull()?.album?.name == "Trails"
        }
        composeRule.onNodeWithTag("album-title", useUnmergedTree = true).assertExists()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Trails").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun setAsCover_appliesTheSingleSelectedPhoto() {
        val photos = listOf(photoItem(0), photoItem(1))
        val target = album("Hikes")
        val repo = FakeAlbumsRepository(
            albums = listOf(target),
            photos = mapOf(target.albumId to photos),
        )
        val albumsVm = AlbumsViewModel(repo)
        val detailVm = AlbumDetailViewModel(target, repo)

        composeRule.setContent {
            PhotosTheme {
                AlbumDetailScreen(
                    album = target,
                    albumsViewModel = albumsVm,
                    onBack = {},
                    onOpenViewer = { _, _, _ -> },
                    viewModel = detailVm,
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { cellCount() == 2 }
        composeRule.waitUntil(timeoutMillis = 5_000) { albumsVm.state.value.albums.size == 1 }

        composeRule.onAllNodesWithTag("timeline-cell", useUnmergedTree = true)
            .onFirst()
            .performTouchInput { longClick() }
        composeRule.onNodeWithTag("album-menu").performClick()
        composeRule.onNodeWithTag("album-setcover").performClick()

        // Sections are newest-first, so the first cell is the newest photo.
        val newest = photos.maxByOrNull { it.userDate }!!
        composeRule.waitUntil(timeoutMillis = 5_000) {
            albumsVm.state.value.albums.firstOrNull()?.album?.coverFileId == newest.fileId
        }
        assertTrue(detailVm.state.value.selectedIds.isEmpty())
    }
}
