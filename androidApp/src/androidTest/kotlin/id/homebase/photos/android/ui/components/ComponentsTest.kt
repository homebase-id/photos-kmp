@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.photos.android.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.homebase.photos.android.ui.theme.PhotosTheme
import id.homebase.photos.domain.PhotoItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Compose UI test for the extracted component package: the selection top bar reports its
 * callbacks and count, and [PhotoGridCell] renders the selection badge / long-press affordance
 * per contract C5 — no ViewModel / Koin graph involved.
 */
@RunWith(AndroidJUnit4::class)
class ComponentsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun photoItem(isVideo: Boolean = false): PhotoItem {
        val id = Uuid.random()
        return PhotoItem(
            fileId = id,
            uniqueId = id,
            userDate = 1_718_000_000_000L,
            isVideo = isVideo,
            pixelWidth = 225,
            pixelHeight = 300,
            previewPlaceholder = null,
            driveId = Uuid.random(),
            payloadKey = "dflt_key",
        )
    }

    @Test
    fun selectionTopBar_showsCount_andFiresBothCallbacks() {
        var closed = false
        var deleted = false
        composeRule.setContent {
            PhotosTheme {
                SelectionTopBar(count = 2, onClose = { closed = true }, onDelete = { deleted = true })
            }
        }

        composeRule.onNodeWithTag("selection-topbar").assertExists()
        composeRule.onNodeWithTag("selection-count", useUnmergedTree = true)
            .assertTextEquals("2 selected")

        composeRule.onNodeWithTag("selection-close").performClick()
        composeRule.onNodeWithTag("selection-delete").performClick()

        assertTrue(closed)
        assertTrue(deleted)
    }

    @Test
    fun photoGridCell_selected_showsCheckBadge() {
        composeRule.setContent {
            PhotosTheme {
                PhotoGridCell(
                    photo = photoItem(),
                    imageLoader = null,
                    onClick = {},
                    selected = true,
                    selectionMode = true,
                )
            }
        }

        composeRule.onNodeWithTag("timeline-cell-check", useUnmergedTree = true).assertExists()
    }

    @Test
    fun photoGridCell_unselected_hasNoCheckBadge() {
        composeRule.setContent {
            PhotosTheme {
                PhotoGridCell(
                    photo = photoItem(),
                    imageLoader = null,
                    onClick = {},
                    selected = false,
                    selectionMode = true,
                )
            }
        }

        composeRule.onNodeWithTag("timeline-cell-check", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun photoGridCell_longPress_firesOnLongPress_notOnClick() {
        var longPressed = false
        var clicked = false
        composeRule.setContent {
            PhotosTheme {
                PhotoGridCell(
                    photo = photoItem(),
                    imageLoader = null,
                    onClick = { clicked = true },
                    onLongPress = { longPressed = true },
                )
            }
        }

        composeRule.onNodeWithTag("timeline-cell").performTouchInput { longClick() }

        assertTrue(longPressed)
        assertFalse(clicked)
    }

    @Test
    fun photoGridCell_tap_firesOnClick() {
        var clickedPhoto: PhotoItem? = null
        var longPressed: Boolean? = null
        val photo = photoItem()
        composeRule.setContent {
            PhotosTheme {
                PhotoGridCell(
                    photo = photo,
                    imageLoader = null,
                    onClick = { clickedPhoto = photo },
                    onLongPress = { longPressed = true },
                )
            }
        }

        composeRule.onNodeWithTag("timeline-cell").performClick()

        assertNotNull(clickedPhoto)
        assertNull(longPressed)
    }
}
