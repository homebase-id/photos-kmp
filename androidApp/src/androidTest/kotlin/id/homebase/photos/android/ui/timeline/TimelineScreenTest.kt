@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.photos.android.ui.timeline

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.timeline.TimelineSection
import id.homebase.photos.timeline.TimelineUiState
import id.homebase.photos.android.ui.theme.PhotosTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Compose UI flow-test for the Conservatory timeline. Drives the stateless [TimelineScreen]
 * overload with fixed [TimelineUiState] values so each of the four states (grid / empty / error /
 * skeleton) and the day sub-headers, pagination trigger, and hidden-at-rest overlay assert their
 * rendering without the live repository / Koin graph.
 */
@RunWith(AndroidJUnit4::class)
class TimelineScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun mockItem(seed: Int): PhotoItem =
        photoItem(seed, userDate = 1_718_000_000_000L + seed * 86_400_000L)

    private fun mockItemOnDay(seed: Int, userDate: Long): PhotoItem = photoItem(seed, userDate)

    private fun photoItem(seed: Int, userDate: Long): PhotoItem {
        val id = Uuid.random()
        return PhotoItem(
            fileId = id,
            uniqueId = id,
            userDate = userDate,
            isVideo = seed % 3 == 0,
            pixelWidth = 225,
            pixelHeight = 300,
            previewPlaceholder = null,
            driveId = Uuid.random(),
            payloadKey = "dflt_key",
        )
    }

    @Test
    fun showsMonthHeaderAndThumbnailCells() {
        val items = (0 until 8).map(::mockItem)
        val state = TimelineUiState(
            isLoading = false,
            sections = listOf(TimelineSection(title = "June 2026", items = items)),
            pagedItems = items,
        )

        composeRule.setContent {
            PhotosTheme {
                TimelineScreen(state = state, onPhotoClick = {})
            }
        }

        // The month header carries the section title (the pinned overlay is hidden at rest, so this
        // matches the single in-grid header).
        composeRule.onAllNodesWithText("June 2026")
            .onFirst()
            .assertIsDisplayed()

        // Grid thumbnail cells are rendered (tagged for discovery).
        composeRule.onAllNodesWithTag("timeline-cell", useUnmergedTree = true)
            .onFirst()
            .assertIsDisplayed()
    }

    @Test
    fun showsEmptyStateWhenNoSections() {
        val state = TimelineUiState(isLoading = false, sections = emptyList(), pagedItems = emptyList())

        composeRule.setContent {
            PhotosTheme { TimelineScreen(state = state) }
        }

        composeRule.onNodeWithTag("timeline-empty").assertExists()
    }

    @Test
    fun showsSkeletonWhileFirstLoading() {
        val state = TimelineUiState(isLoading = true, sections = emptyList(), pagedItems = emptyList())

        composeRule.setContent {
            PhotosTheme { TimelineScreen(state = state) }
        }

        composeRule.onNodeWithTag("timeline-skeleton").assertExists()
    }

    @Test
    fun showsErrorStateWithRetry() {
        val state = TimelineUiState(
            isLoading = false,
            error = "Network unreachable",
            sections = emptyList(),
            pagedItems = emptyList(),
        )

        composeRule.setContent {
            PhotosTheme { TimelineScreen(state = state) }
        }

        composeRule.onNodeWithTag("timeline-error").assertExists()
        composeRule.onNodeWithText("Try again").assertExists()
    }

    @Test
    fun showsDaySubheaderForItemsOnTheSameDay() {
        val day = 1_718_000_000_000L
        val items = listOf(mockItemOnDay(0, day), mockItemOnDay(1, day))
        val state = TimelineUiState(
            isLoading = false,
            sections = listOf(TimelineSection(title = "June 2024", items = items)),
            pagedItems = items,
        )

        composeRule.setContent {
            PhotosTheme { TimelineScreen(state = state) }
        }

        composeRule.onAllNodesWithTag("timeline-day-header").onFirst().assertExists()
    }

    @Test
    fun loadMoreFiresNearEnd() {
        var loadMoreCalled = false
        val day = 1_718_000_000_000L
        val items = (0 until 60).map { mockItemOnDay(it, day) }
        val state = TimelineUiState(
            isLoading = false,
            isPaginating = false,
            endReached = false,
            sections = listOf(TimelineSection(title = "June 2024", items = items)),
            pagedItems = items,
        )

        composeRule.setContent {
            PhotosTheme {
                TimelineScreen(state = state, onLoadMore = { loadMoreCalled = true })
            }
        }

        // Scroll near the end of the grid; the prefetch-margin trigger should fire onLoadMore.
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(items.size)
        composeRule.waitForIdle()

        assertTrue(loadMoreCalled)
    }

    @Test
    fun monthOverlayHiddenAtRest() {
        val items = (0 until 8).map(::mockItem)
        val state = TimelineUiState(
            isLoading = false,
            sections = listOf(TimelineSection(title = "June 2026", items = items)),
            pagedItems = items,
        )

        composeRule.setContent {
            PhotosTheme { TimelineScreen(state = state) }
        }

        // At scroll 0 the first visible item IS the month header, so the pinned overlay is absent.
        composeRule.onAllNodesWithTag("timeline-month-overlay").assertCountEquals(0)
    }

    // --- Account affordance (Batch G): the account button navigates to Settings — the logout
    // dialog lives there now (see SettingsFlowTest). The top bar renders for every content branch,
    // so an empty state is enough to exercise the account action. ---

    private fun emptyState() =
        TimelineUiState(isLoading = false, sections = emptyList(), pagedItems = emptyList())

    @Test
    fun accountButtonInvokesOnOpenSettings() {
        var opened = false
        composeRule.setContent {
            PhotosTheme { TimelineScreen(state = emptyState(), onOpenSettings = { opened = true }) }
        }

        composeRule.onNodeWithTag("account-button").assertIsDisplayed().performClick()

        assertTrue(opened)
    }

    @Test
    fun accountButtonOpensNoDialogInline() {
        composeRule.setContent {
            PhotosTheme { TimelineScreen(state = emptyState()) }
        }

        composeRule.onNodeWithTag("account-button").performClick()

        composeRule.onNodeWithTag("logout-confirm").assertDoesNotExist()
        composeRule.onNodeWithText("Log out?").assertDoesNotExist()
    }
}
