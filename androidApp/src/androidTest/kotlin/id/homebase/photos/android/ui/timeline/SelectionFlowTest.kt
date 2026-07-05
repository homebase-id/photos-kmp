@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.photos.android.ui.timeline

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.homebase.photos.android.ui.theme.PhotosTheme
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.timeline.TimelineSection
import id.homebase.photos.timeline.TimelineUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Compose UI flow-test for timeline selection mode (contract C5). Drives the stateless
 * [TimelineScreen] with fixed [TimelineUiState] values carrying `selectedIds`, so the top-bar
 * swap, count label, delete confirmation, long-press entry, and toggle-vs-open tap routing all
 * assert without the shared ViewModel / Koin graph.
 */
@RunWith(AndroidJUnit4::class)
class SelectionFlowTest {

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

    private fun stateOf(items: List<PhotoItem>, selected: Set<String>) = TimelineUiState(
        isLoading = false,
        sections = listOf(TimelineSection(title = "June 2026", items = items)),
        pagedItems = items,
        selectedIds = selected,
    )

    @Test
    fun selectionMode_swapsTopBar_andShowsCount() {
        val items = listOf(photoItem(0), photoItem(1))
        val selected = items.map { it.fileId.toString() }.toSet()

        composeRule.setContent {
            PhotosTheme { TimelineScreen(state = stateOf(items, selected)) }
        }

        composeRule.onNodeWithTag("selection-topbar").assertExists()
        composeRule.onNodeWithTag("selection-count", useUnmergedTree = true)
            .assertTextEquals("2 selected")
        // The default Photos bar (account action) is replaced while selecting.
        composeRule.onNodeWithTag("account-button").assertDoesNotExist()
    }

    @Test
    fun trash_opensConfirmDialog_andConfirmFiresDeleteSelected() {
        val items = listOf(photoItem(0), photoItem(1))
        val selected = items.map { it.fileId.toString() }.toSet()
        var deleteCalled = false

        composeRule.setContent {
            PhotosTheme {
                TimelineScreen(
                    state = stateOf(items, selected),
                    onDeleteSelected = { deleteCalled = true },
                )
            }
        }

        composeRule.onNodeWithTag("selection-delete").performClick()

        composeRule.onNodeWithText("Delete 2 items?").assertIsDisplayed()
        composeRule.onNodeWithText("They'll be removed from your Homebase photo library.")
            .assertExists()

        composeRule.onNodeWithTag("delete-confirm").performClick()

        assertTrue(deleteCalled)
    }

    @Test
    fun closeButton_firesClearSelection() {
        val items = listOf(photoItem(0))
        var cleared = false

        composeRule.setContent {
            PhotosTheme {
                TimelineScreen(
                    state = stateOf(items, setOf(items.first().fileId.toString())),
                    onClearSelection = { cleared = true },
                )
            }
        }

        composeRule.onNodeWithTag("selection-close").performClick()

        assertTrue(cleared)
    }

    @Test
    fun longPress_outsideSelectionMode_firesToggleSelection() {
        val items = listOf(photoItem(0), photoItem(1))
        var toggled: PhotoItem? = null

        composeRule.setContent {
            PhotosTheme {
                TimelineScreen(
                    state = stateOf(items, emptySet()),
                    onToggleSelection = { toggled = it },
                )
            }
        }

        composeRule.onAllNodesWithTag("timeline-cell", useUnmergedTree = true)
            .onFirst()
            .performTouchInput { longClick() }

        assertNotNull(toggled)
        assertEquals(items.first().fileId, toggled?.fileId)
    }

    @Test
    fun tap_inSelectionMode_togglesInsteadOfOpeningViewer() {
        val items = listOf(photoItem(0), photoItem(1))
        var toggled = false
        var opened = false

        composeRule.setContent {
            PhotosTheme {
                TimelineScreen(
                    state = stateOf(items, setOf(items.first().fileId.toString())),
                    onPhotoClick = { opened = true },
                    onToggleSelection = { toggled = true },
                )
            }
        }

        composeRule.onAllNodesWithTag("timeline-cell", useUnmergedTree = true)
            .onFirst()
            .performClick()

        assertTrue(toggled)
        assertFalse(opened)
    }

    @Test
    fun selectedCell_showsCheckBadge() {
        val items = listOf(photoItem(0), photoItem(1))

        composeRule.setContent {
            PhotosTheme {
                TimelineScreen(state = stateOf(items, setOf(items.first().fileId.toString())))
            }
        }

        composeRule.onAllNodesWithTag("timeline-cell-check", useUnmergedTree = true)
            .onFirst()
            .assertExists()
    }
}
