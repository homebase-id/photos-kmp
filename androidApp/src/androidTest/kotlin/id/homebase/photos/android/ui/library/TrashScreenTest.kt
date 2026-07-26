@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.photos.android.ui.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.homebase.photos.android.ui.theme.PhotosTheme
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.library.TrashUiState
import id.homebase.photos.timeline.TimelineSection
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Compose UI flow-test for [TrashScreen] (Batch D). Drives the stateless overload with a fixed
 * [TrashUiState] so the bin note, the Restore/Delete-forever selection bar, and the permanent-delete
 * confirmation assert without the shared ViewModel / Koin graph.
 */
@RunWith(AndroidJUnit4::class)
class TrashScreenTest {

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

    private fun stateOf(items: List<PhotoItem>, selected: Set<String>) = TrashUiState(
        isLoading = false,
        sections = listOf(TimelineSection(title = "June 2026", items = items)),
        pagedItems = items,
        selectedIds = selected,
    )

    @Test
    fun headerNote_alwaysVisible() {
        val items = listOf(photoItem(0))

        composeRule.setContent {
            PhotosTheme { TrashScreen(state = stateOf(items, emptySet()), onBack = {}) }
        }

        composeRule.onNodeWithText("Items stay in the bin until you delete them permanently.")
            .assertExists()
    }

    @Test
    fun selectionMode_showsRestoreAndDeleteForever() {
        val items = listOf(photoItem(0), photoItem(1))
        val selected = items.map { it.fileId.toString() }.toSet()

        composeRule.setContent {
            PhotosTheme { TrashScreen(state = stateOf(items, selected), onBack = {}) }
        }

        composeRule.onNodeWithTag("trash-restore").assertExists()
        composeRule.onNodeWithTag("trash-delete-forever").assertExists()
        composeRule.onNodeWithTag("trash-grid").assertExists()
    }

    @Test
    fun notSelecting_hidesRestoreAndDeleteForever() {
        val items = listOf(photoItem(0))

        composeRule.setContent {
            PhotosTheme { TrashScreen(state = stateOf(items, emptySet()), onBack = {}) }
        }

        composeRule.onNodeWithTag("trash-restore").assertDoesNotExist()
        composeRule.onNodeWithTag("trash-delete-forever").assertDoesNotExist()
    }

    @Test
    fun restoreButton_firesOnRestoreSelected() {
        val items = listOf(photoItem(0))
        val selected = setOf(items.first().fileId.toString())
        var restored = false

        composeRule.setContent {
            PhotosTheme {
                TrashScreen(
                    state = stateOf(items, selected),
                    onBack = {},
                    onRestoreSelected = { restored = true },
                )
            }
        }

        composeRule.onNodeWithTag("trash-restore").performClick()

        assertTrue(restored)
    }

    @Test
    fun deleteForever_opensConfirmDialog_andConfirmFiresCallback() {
        val items = listOf(photoItem(0))
        val selected = setOf(items.first().fileId.toString())
        var deleted = false

        composeRule.setContent {
            PhotosTheme {
                TrashScreen(
                    state = stateOf(items, selected),
                    onBack = {},
                    onPermanentDeleteSelected = { deleted = true },
                )
            }
        }

        composeRule.onNodeWithTag("trash-delete-forever").performClick()
        composeRule.onNodeWithText("Delete forever?").assertExists()
        composeRule.onNodeWithTag("delete-confirm").performClick()

        assertTrue(deleted)
    }
}
