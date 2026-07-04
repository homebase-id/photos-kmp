package id.homebase.photos.android.ui.backup

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.homebase.photos.android.ui.theme.PhotosTheme
import id.homebase.photos.backup.FolderUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI flow-test for the stateless [BackupStatusCard]. Drives it with fixed values so the
 * card renders, the toggle reports its callback, the `done of total` progress line shows only while
 * a pass runs, and the folder picker lists folders / persists toggles / gates the empty-run case —
 * no shared ViewModel / Koin / WorkManager graph involved.
 */
@RunWith(AndroidJUnit4::class)
class BackupCardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val sampleFolders = listOf(
        FolderUi(folderId = "10", name = "Camera", photoCount = 128, selected = true),
        FolderUi(folderId = "20", name = "Screenshots", photoCount = 42, selected = false),
    )

    @Test
    fun cardAndToggleRender() {
        composeRule.setContent {
            PhotosTheme {
                BackupStatusCard(
                    enabled = false,
                    running = false,
                    done = 0,
                    total = 0,
                    lastCompletedAt = null,
                    selectedFolderCount = 0,
                    folders = emptyList(),
                    onToggle = {},
                    onChooseFolders = {},
                    onFolderToggled = {},
                )
            }
        }

        composeRule.onNodeWithTag("backup-card").assertExists()
        composeRule.onNodeWithTag("backup-toggle").assertIsDisplayed()
    }

    @Test
    fun togglingOn_withSelection_invokesCallbackWithTrue() {
        var toggled: Boolean? = null
        composeRule.setContent {
            PhotosTheme {
                BackupStatusCard(
                    enabled = false,
                    running = false,
                    done = 0,
                    total = 0,
                    lastCompletedAt = null,
                    selectedFolderCount = 1,
                    folders = sampleFolders,
                    onToggle = { toggled = it },
                    onChooseFolders = {},
                    onFolderToggled = {},
                )
            }
        }

        composeRule.onNodeWithTag("backup-toggle").performClick()

        assertEquals(true, toggled)
    }

    @Test
    fun runningState_showsProgress() {
        composeRule.setContent {
            PhotosTheme {
                BackupStatusCard(
                    enabled = true,
                    running = true,
                    done = 2,
                    total = 5,
                    lastCompletedAt = null,
                    selectedFolderCount = 1,
                    folders = sampleFolders,
                    onToggle = {},
                    onChooseFolders = {},
                    onFolderToggled = {},
                )
            }
        }

        composeRule.onNodeWithTag("backup-progress").assertExists()
        composeRule.onNodeWithText("2 of 5").assertExists()
    }

    @Test
    fun chooseFolders_opensSheet_listingFoldersWithCounts() {
        composeRule.setContent {
            PhotosTheme {
                BackupStatusCard(
                    enabled = true,
                    running = false,
                    done = 0,
                    total = 0,
                    lastCompletedAt = null,
                    selectedFolderCount = 1,
                    folders = sampleFolders,
                    onToggle = {},
                    onChooseFolders = {},
                    onFolderToggled = {},
                )
            }
        }

        composeRule.onNodeWithTag("backup-choose-folders").performClick()

        composeRule.onNodeWithTag("backup-folder-sheet").assertExists()
        composeRule.onNodeWithText("Camera").assertExists()
        composeRule.onNodeWithText("128 photos").assertExists()
        composeRule.onNodeWithText("Screenshots").assertExists()
        composeRule.onNodeWithText("42 photos").assertExists()
    }

    @Test
    fun folderRowClick_invokesFolderToggledWithId() {
        var toggledFolderId: String? = null
        composeRule.setContent {
            PhotosTheme {
                BackupStatusCard(
                    enabled = true,
                    running = false,
                    done = 0,
                    total = 0,
                    lastCompletedAt = null,
                    selectedFolderCount = 1,
                    folders = sampleFolders,
                    onToggle = {},
                    onChooseFolders = {},
                    onFolderToggled = { toggledFolderId = it },
                )
            }
        }

        composeRule.onNodeWithTag("backup-choose-folders").performClick()
        composeRule.onNodeWithTag("backup-folder-row-20").performClick()

        assertEquals("20", toggledFolderId)
    }

    @Test
    fun togglingOn_withZeroSelection_opensSheetInsteadOfRunning() {
        var toggled: Boolean? = null
        composeRule.setContent {
            PhotosTheme {
                BackupStatusCard(
                    enabled = false,
                    running = false,
                    done = 0,
                    total = 0,
                    lastCompletedAt = null,
                    selectedFolderCount = 0,
                    folders = sampleFolders,
                    onToggle = { toggled = it },
                    onChooseFolders = {},
                    onFolderToggled = {},
                )
            }
        }

        composeRule.onNodeWithTag("backup-toggle").performClick()

        // Enabling with nothing selected must not start a run…
        assertNull(toggled)
        // …it opens the picker instead.
        composeRule.onNodeWithTag("backup-folder-sheet").assertExists()
    }
}
