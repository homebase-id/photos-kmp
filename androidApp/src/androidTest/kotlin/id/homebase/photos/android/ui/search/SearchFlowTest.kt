@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.photos.android.ui.search

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.homebase.photos.albums.AlbumSummary
import id.homebase.photos.android.ui.theme.PhotosTheme
import id.homebase.photos.domain.AlbumItem
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.search.SearchUiState
import id.homebase.photos.search.TypeFilter
import id.homebase.photos.timeline.TimelineSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Compose UI flow-test for the Search screen (Batch E). Drives the stateless [SearchScreen]
 * overload with fixed [SearchUiState] fixtures and spy callbacks — same no-Koin convention as
 * [id.homebase.photos.android.ui.library.TrashScreenTest] / [id.homebase.photos.android.ui.timeline.SelectionFlowTest].
 * [SearchViewModel][id.homebase.photos.search.SearchViewModel]'s own search/recents/filter logic is
 * covered by `shared/src/jvmTest/.../search/SearchViewModelTest.kt`; this file only asserts the
 * screen renders each state correctly and wires taps to the right intent.
 */
@RunWith(AndroidJUnit4::class)
class SearchFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun photoItem(seed: Int, isVideo: Boolean = false): PhotoItem {
        val id = Uuid.random()
        return PhotoItem(
            fileId = id,
            uniqueId = id,
            userDate = 1_718_000_000_000L + seed * 86_400_000L,
            isVideo = isVideo,
            pixelWidth = 225,
            pixelHeight = 300,
            previewPlaceholder = null,
            driveId = Uuid.random(),
            payloadKey = "dflt_key",
        )
    }

    private fun album(name: String) =
        AlbumItem(fileId = Uuid.random(), albumId = Uuid.random(), name = name, coverFileId = null)

    @Test
    fun idleState_showsRecents_tappingOneFiresOnRecentClick() {
        var clicked: String? = null

        composeRule.setContent {
            PhotosTheme {
                SearchScreen(
                    state = SearchUiState(recent = listOf("beach", "lake")),
                    onBack = {},
                    onRecentClick = { clicked = it },
                )
            }
        }

        composeRule.onNodeWithTag("search-recent").assertExists()
        composeRule.onNodeWithText("beach").performClick()

        assertEquals("beach", clicked)
    }

    @Test
    fun searchingState_showsSkeleton_notResultsOrEmpty() {
        composeRule.setContent {
            PhotosTheme {
                SearchScreen(
                    state = SearchUiState(query = "sunset", isSearching = true),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("search-skeleton").assertExists()
        composeRule.onNodeWithTag("search-results-grid").assertDoesNotExist()
        composeRule.onNodeWithTag("search-empty").assertDoesNotExist()
    }

    @Test
    fun resultsState_showsGrid_andTapFiresOnPhotoClick() {
        val items = listOf(photoItem(0), photoItem(1), photoItem(2))
        var clicked: PhotoItem? = null

        composeRule.setContent {
            PhotosTheme {
                SearchScreen(
                    state = SearchUiState(
                        query = "june",
                        hasSearched = true,
                        sections = listOf(TimelineSection(title = "June 2026", items = items)),
                    ),
                    onBack = {},
                    onPhotoClick = { clicked = it },
                )
            }
        }

        composeRule.onNodeWithTag("search-results-grid").assertExists()
        val cells = composeRule.onAllNodesWithTag("timeline-cell", useUnmergedTree = true)
        assertEquals(items.size, cells.fetchSemanticsNodes().size)

        cells.onFirst().performClick()

        assertTrue(clicked != null)
    }

    @Test
    fun isSearching_withNonEmptyResults_showsProgressWithoutHidingGrid() {
        val items = listOf(photoItem(0))

        composeRule.setContent {
            PhotosTheme {
                SearchScreen(
                    state = SearchUiState(
                        query = "june",
                        hasSearched = true,
                        isSearching = true, // a filter change re-ran the search over existing results
                        sections = listOf(TimelineSection(title = "June 2026", items = items)),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("search-progress").assertExists()
        composeRule.onNodeWithTag("search-results-grid").assertExists()
        composeRule.onNodeWithTag("search-skeleton").assertDoesNotExist()
    }

    @Test
    fun error_withNonEmptyResults_showsBannerWithoutHidingGrid() {
        val items = listOf(photoItem(0))

        composeRule.setContent {
            PhotosTheme {
                SearchScreen(
                    state = SearchUiState(
                        query = "june",
                        hasSearched = true,
                        error = "Couldn't refresh results", // stale re-search failed
                        sections = listOf(TimelineSection(title = "June 2026", items = items)),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("search-error-banner").assertExists()
        composeRule.onNodeWithText("Couldn't refresh results").assertExists()
        composeRule.onNodeWithTag("search-results-grid").assertExists()
        composeRule.onNodeWithTag("search-error").assertDoesNotExist() // not the full-screen state
    }

    @Test
    fun filterRow_withLongLabels_albumChipReachableViaScroll() {
        val longAlbum = album("Summer Vacation With All The Extended Family And Friends 2026")

        composeRule.setContent {
            PhotosTheme {
                SearchScreen(
                    state = SearchUiState(
                        fromUserDate = 1_718_000_000_000L,
                        toUserDate = 1_720_000_000_000L, // long formatted date-range label
                        albumFilter = longAlbum,
                    ),
                    onBack = {},
                )
            }
        }

        // The row doesn't clip/hide the last chip — scrolling to it brings it fully on screen.
        composeRule.onNodeWithTag("search-chip-album").performScrollTo()
        composeRule.onNodeWithTag("search-chip-album").assertIsDisplayed()
    }

    @Test
    fun emptyState_afterSearchWithNoResults_showsEmptyTag() {
        composeRule.setContent {
            PhotosTheme {
                SearchScreen(
                    state = SearchUiState(query = "nothing-matches", hasSearched = true, sections = emptyList()),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("search-empty").assertExists()
    }

    @Test
    fun typingQuery_firesOnQueryChange() {
        var lastQuery = ""

        composeRule.setContent {
            PhotosTheme {
                SearchScreen(
                    state = SearchUiState(),
                    onBack = {},
                    onQueryChange = { lastQuery = it },
                )
            }
        }

        composeRule.onNodeWithTag("search-field").performTextInput("sunset")

        assertEquals("sunset", lastQuery)
    }

    @Test
    fun imeSearchAction_firesOnSubmit() {
        var submitted = false

        composeRule.setContent {
            PhotosTheme {
                SearchScreen(
                    state = SearchUiState(query = "sunset"),
                    onBack = {},
                    onSubmit = { submitted = true },
                )
            }
        }

        composeRule.onNodeWithTag("search-field").performImeAction()

        assertTrue(submitted)
    }

    @Test
    fun typeChip_selectingVideos_firesOnTypeFilterChange() {
        var picked: TypeFilter? = null

        composeRule.setContent {
            PhotosTheme {
                SearchScreen(
                    state = SearchUiState(),
                    onBack = {},
                    onTypeFilterChange = { picked = it },
                )
            }
        }

        composeRule.onNodeWithTag("search-chip-type").performClick()
        composeRule.onNodeWithTag("search-type-option-videos").performClick()

        assertEquals(TypeFilter.VIDEOS, picked)
    }

    @Test
    fun typeChip_activeVideos_showsClearIcon_clearingResetsToAll() {
        var picked: TypeFilter? = null

        composeRule.setContent {
            PhotosTheme {
                SearchScreen(
                    state = SearchUiState(typeFilter = TypeFilter.VIDEOS),
                    onBack = {},
                    onTypeFilterChange = { picked = it },
                )
            }
        }

        composeRule.onNodeWithText("Videos").assertExists()
        composeRule.onNodeWithContentDescription("Clear type filter").performClick()

        assertEquals(TypeFilter.ALL, picked)
    }

    @Test
    fun dateChip_tap_opensDateRangeDialog() {
        composeRule.setContent {
            PhotosTheme {
                SearchScreen(state = SearchUiState(), onBack = {})
            }
        }

        composeRule.onNodeWithTag("search-chip-date").performClick()

        composeRule.onNodeWithTag("search-date-dialog").assertExists()
    }

    @Test
    fun albumChip_pickingAlbum_firesOnAlbumFilterChange() {
        val target = album("Hikes")
        var picked: AlbumItem? = null

        composeRule.setContent {
            PhotosTheme {
                SearchScreen(
                    state = SearchUiState(),
                    albums = listOf(AlbumSummary(album = target, cover = null)),
                    onBack = {},
                    onAlbumFilterChange = { picked = it },
                )
            }
        }

        composeRule.onNodeWithTag("search-chip-album").performClick()
        composeRule.onNodeWithText("Hikes").performClick()

        assertEquals(target, picked)
    }

    @Test
    fun clearButton_hiddenWhenIdle_visibleAndFiresOnClearFiltersOtherwise() {
        var cleared = false

        composeRule.setContent {
            PhotosTheme {
                SearchScreen(
                    state = SearchUiState(typeFilter = TypeFilter.VIDEOS),
                    onBack = {},
                    onClearFilters = { cleared = true },
                )
            }
        }

        composeRule.onNodeWithTag("search-clear").performClick()

        assertTrue(cleared)
    }

    @Test
    fun clearButton_notShown_whenStateIsIdle() {
        composeRule.setContent {
            PhotosTheme {
                SearchScreen(state = SearchUiState(), onBack = {})
            }
        }

        composeRule.onNodeWithTag("search-clear").assertDoesNotExist()
    }

    @Test
    fun backButton_firesOnBack() {
        var backCalled = false

        composeRule.setContent {
            PhotosTheme {
                SearchScreen(state = SearchUiState(), onBack = { backCalled = true })
            }
        }

        composeRule.onNodeWithTag("search-back").performClick()

        assertTrue(backCalled)
    }
}
