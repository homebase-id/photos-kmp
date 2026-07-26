@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.photos.android.ui.collections

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.homebase.photos.albums.AlbumSummary
import id.homebase.photos.albums.AlbumsUiState
import id.homebase.photos.android.ui.theme.PhotosTheme
import id.homebase.photos.domain.AlbumItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Compose UI flow-test for the stateless [CollectionsScreen]. Drives it with fixed [AlbumsUiState]
 * values so the album grid, card clicks, and the skeleton / empty / error branches assert without
 * the shared ViewModel / Koin graph.
 */
@RunWith(AndroidJUnit4::class)
class CollectionsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun summary(name: String): AlbumSummary = AlbumSummary(
        album = AlbumItem(
            fileId = Uuid.random(),
            albumId = Uuid.random(),
            name = name,
            coverFileId = null,
        ),
        cover = null,
    )

    @Test
    fun twoAlbums_renderTwoCardsInTheGrid() {
        val state = AlbumsUiState(
            isLoading = false,
            albums = listOf(summary("Hikes"), summary("Family")),
        )

        composeRule.setContent {
            PhotosTheme { CollectionsScreen(state = state, onAlbumClick = {}) }
        }

        composeRule.onNodeWithTag("collections-grid").assertExists()
        composeRule.onAllNodesWithTag("album-card").assertCountEquals(2)
        composeRule.onNodeWithText("Hikes").assertExists()
        composeRule.onNodeWithText("Family").assertExists()
    }

    @Test
    fun albumCardClick_firesCallbackWithThatAlbum() {
        val state = AlbumsUiState(
            isLoading = false,
            albums = listOf(summary("Hikes"), summary("Family")),
        )
        var clicked: AlbumItem? = null

        composeRule.setContent {
            PhotosTheme { CollectionsScreen(state = state, onAlbumClick = { clicked = it }) }
        }

        composeRule.onAllNodesWithTag("album-card").onFirst().performClick()

        assertEquals("Hikes", clicked?.name)
    }

    @Test
    fun loading_showsSkeleton() {
        val state = AlbumsUiState(isLoading = true)

        composeRule.setContent {
            PhotosTheme { CollectionsScreen(state = state, onAlbumClick = {}) }
        }

        composeRule.onNodeWithTag("collections-skeleton").assertExists()
    }

    @Test
    fun emptyState_showsNoAlbumsYet() {
        val state = AlbumsUiState(isLoading = false, albums = emptyList())

        composeRule.setContent {
            PhotosTheme { CollectionsScreen(state = state, onAlbumClick = {}) }
        }

        composeRule.onNodeWithTag("collections-empty").assertExists()
        composeRule.onNodeWithText("No albums yet").assertExists()
    }

    @Test
    fun libraryRows_renderAboveTheGrid_favoritesArchiveTrashEnabled_utilitiesStaysSoon() {
        val state = AlbumsUiState(isLoading = false, albums = listOf(summary("Hikes")))

        composeRule.setContent {
            PhotosTheme { CollectionsScreen(state = state, onAlbumClick = {}) }
        }

        composeRule.onNodeWithTag("collections-library-row-favorites").assertExists()
        composeRule.onNodeWithTag("collections-library-row-archive").assertExists()
        composeRule.onNodeWithTag("collections-library-row-trash").assertExists()
        composeRule.onNodeWithTag("collections-library-row-utilities").assertExists()
        composeRule.onNodeWithText("Favorites").assertExists()
        composeRule.onNodeWithText("Soon").assertExists() // Utilities is the only row still inert
        composeRule.onNodeWithTag("collections-grid").assertExists()
    }

    @Test
    fun favoritesRow_click_navigatesToFavorites() {
        val state = AlbumsUiState(isLoading = false, albums = listOf(summary("Hikes")))
        var clicked = false

        composeRule.setContent {
            PhotosTheme {
                CollectionsScreen(state = state, onAlbumClick = {}, onFavoritesClick = { clicked = true })
            }
        }

        composeRule.onNodeWithTag("collections-library-row-favorites").performClick()

        assertTrue(clicked)
    }

    @Test
    fun archiveRow_click_navigatesToArchive() {
        val state = AlbumsUiState(isLoading = false, albums = listOf(summary("Hikes")))
        var clicked = false

        composeRule.setContent {
            PhotosTheme {
                CollectionsScreen(state = state, onAlbumClick = {}, onArchiveClick = { clicked = true })
            }
        }

        composeRule.onNodeWithTag("collections-library-row-archive").performClick()

        assertTrue(clicked)
    }

    @Test
    fun trashRow_click_navigatesToTrash() {
        val state = AlbumsUiState(isLoading = false, albums = listOf(summary("Hikes")))
        var clicked = false

        composeRule.setContent {
            PhotosTheme {
                CollectionsScreen(state = state, onAlbumClick = {}, onTrashClick = { clicked = true })
            }
        }

        composeRule.onNodeWithTag("collections-library-row-trash").performClick()

        assertTrue(clicked)
    }

    @Test
    fun createAction_opensTheDialog_andReportsTheName() {
        val state = AlbumsUiState(isLoading = false, albums = listOf(summary("Hikes")))
        var created: String? = null

        composeRule.setContent {
            PhotosTheme {
                CollectionsScreen(
                    state = state,
                    onAlbumClick = {},
                    onCreateAlbum = { created = it },
                )
            }
        }

        composeRule.onNodeWithTag("collections-create").performClick()
        composeRule.onNodeWithTag("create-album-dialog").assertExists()
        composeRule.onNodeWithTag("name-dialog-field").performTextInput("Trips")
        composeRule.onNodeWithTag("name-dialog-confirm").performClick()

        assertEquals("Trips", created)
    }

    @Test
    fun errorState_showsRetry_andFiresIt() {
        val state = AlbumsUiState(isLoading = false, error = "Network unreachable")
        var retried = false

        composeRule.setContent {
            PhotosTheme {
                CollectionsScreen(state = state, onAlbumClick = {}, onRetry = { retried = true })
            }
        }

        composeRule.onNodeWithTag("collections-error").assertExists()
        composeRule.onNodeWithText("Try again").performClick()

        assertTrue(retried)
    }
}
