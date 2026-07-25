@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.photos.android.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.homebase.photos.android.ui.theme.PhotosTheme
import id.homebase.photos.domain.PhotoItem
import org.junit.Assert.assertEquals
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
                SelectionTopBar(count = 2, onClose = { closed = true }, onAction = { deleted = true })
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
    fun selectionTopBar_retargetedAction_carriesItsOwnTag_andHostsExtras() {
        var removed = false
        var extra = false
        composeRule.setContent {
            PhotosTheme {
                SelectionTopBar(
                    count = 1,
                    onClose = {},
                    onAction = { removed = true },
                    actionIcon = Icons.Outlined.RemoveCircleOutline,
                    actionLabel = "Remove from album",
                    actionTag = "album-remove",
                    extraActions = {
                        IconButton(
                            onClick = { extra = true },
                            modifier = Modifier.testTag("selection-addto"),
                        ) {
                            Icon(Icons.Outlined.PhotoAlbum, contentDescription = "Add to album")
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag("selection-delete").assertDoesNotExist()
        composeRule.onNodeWithTag("selection-addto").performClick()
        composeRule.onNodeWithTag("album-remove").performClick()

        assertTrue(extra)
        assertTrue(removed)
    }

    @Test
    fun nameInputDialog_disablesConfirmUntilNamed_thenReportsTheTrimmedName() {
        var confirmed: String? = null
        composeRule.setContent {
            PhotosTheme {
                NameInputDialog(
                    title = "New album",
                    confirmLabel = "Create",
                    testTag = "create-album-dialog",
                    onConfirm = { confirmed = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("create-album-dialog").assertExists()
        composeRule.onNodeWithTag("name-dialog-confirm").assertIsNotEnabled()

        composeRule.onNodeWithTag("name-dialog-field").performTextInput("  Hikes  ")
        composeRule.onNodeWithTag("name-dialog-confirm").performClick()

        assertEquals("Hikes", confirmed)
    }

    @Test
    fun albumOverflowMenu_opensAndGatesSetCoverOnASingleSelection() {
        var renamed = false
        composeRule.setContent {
            PhotosTheme {
                AlbumOverflowMenu(
                    onRename = { renamed = true },
                    onSetCover = {},
                    onDelete = {},
                    setCoverEnabled = false,
                )
            }
        }

        composeRule.onNodeWithTag("album-menu").performClick()
        composeRule.onNodeWithTag("album-setcover").assertIsNotEnabled()
        composeRule.onNodeWithTag("album-delete").assertExists()
        composeRule.onNodeWithTag("album-rename").performClick()

        assertTrue(renamed)
    }

    @Test
    fun libraryRow_disabled_rendersButDoesNotFire() {
        var clicked = false
        composeRule.setContent {
            PhotosTheme {
                LibraryRow(
                    icon = Icons.Outlined.Archive,
                    label = "Archive",
                    testTag = "collections-library-row-archive",
                    onClick = { clicked = true },
                    enabled = false,
                    trailingLabel = "Soon",
                )
            }
        }

        composeRule.onNodeWithTag("collections-library-row-archive").assertExists()
        composeRule.onNodeWithTag("collections-library-row-archive").performClick()

        assertFalse(clicked)
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
