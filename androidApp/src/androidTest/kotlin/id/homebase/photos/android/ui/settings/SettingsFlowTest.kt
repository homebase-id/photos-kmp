package id.homebase.photos.android.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.homebase.photos.android.ui.backup.BackupScreen
import id.homebase.photos.android.ui.nav.Route
import id.homebase.photos.android.ui.theme.PhotosTheme
import id.homebase.photos.android.ui.timeline.TimelineScreen
import id.homebase.photos.settings.SettingsUiState
import id.homebase.photos.timeline.TimelineUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI flow-test for the Settings + Backup screens (Batch G). Drives the stateless
 * [SettingsScreen] / [BackupScreen] overloads with fixed states and spy callbacks — same no-Koin
 * convention as [id.homebase.photos.android.ui.search.SearchFlowTest]. The nav test wires the real
 * stateless [TimelineScreen] into a [NavHost] so the account-button → Settings hop exercises the
 * actual route wiring.
 */
@RunWith(AndroidJUnit4::class)
class SettingsFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val sampleState = SettingsUiState(
        identity = "frodo.dotyou.cloud",
        displayName = "Frodo Baggins",
        initials = "FB",
    )

    private fun emptyTimelineState() =
        TimelineUiState(isLoading = false, sections = emptyList(), pagedItems = emptyList())

    private fun setNav() {
        composeRule.setContent {
            PhotosTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = Route.Photos.path) {
                    composable(Route.Photos.path) {
                        TimelineScreen(
                            state = emptyTimelineState(),
                            onOpenSettings = { nav.navigate(Route.Settings.path) },
                        )
                    }
                    composable(Route.Settings.path) {
                        SettingsScreen(
                            state = sampleState,
                            onBack = { nav.popBackStack() },
                            onOpenBackup = { nav.navigate(Route.Backup.path) },
                        )
                    }
                    composable(Route.Backup.path) {
                        BackupScreen(
                            enabled = false,
                            running = false,
                            done = 0,
                            total = 0,
                            currentName = null,
                            lastCompletedAt = null,
                            selectedFolderCount = 0,
                            folders = emptyList(),
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun accountButton_opensSettings() {
        setNav()

        composeRule.onNodeWithTag("account-button").assertIsDisplayed().performClick()

        composeRule.onNodeWithTag("settings-root").assertExists()
        composeRule.onNodeWithTag("settings-account").assertIsDisplayed()
    }

    @Test
    fun settingsRows_renderAccountAndDestinations() {
        composeRule.setContent {
            PhotosTheme {
                SettingsScreen(state = sampleState, onBack = {})
            }
        }

        composeRule.onNodeWithTag("settings-account").assertIsDisplayed()
        composeRule.onNodeWithText("Frodo Baggins").assertExists()
        composeRule.onNodeWithText("frodo.dotyou.cloud").assertExists()
        composeRule.onNodeWithTag("settings-backup").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-about").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-signout").assertIsDisplayed()
    }

    @Test
    fun signOut_confirmInvokesOnLogout() {
        var loggedOut = false
        composeRule.setContent {
            PhotosTheme {
                SettingsScreen(state = sampleState, onBack = {}, onLogout = { loggedOut = true })
            }
        }

        composeRule.onNodeWithTag("settings-signout").performScrollTo().performClick()

        composeRule.onNodeWithTag("logout-confirm").assertIsDisplayed()
        composeRule.onNodeWithText("Log out?").assertExists()

        composeRule.onNodeWithTag("logout-confirm").performClick()

        assertTrue(loggedOut)
    }

    @Test
    fun signOut_cancelDismissesWithoutLoggingOut() {
        var loggedOut = false
        composeRule.setContent {
            PhotosTheme {
                SettingsScreen(state = sampleState, onBack = {}, onLogout = { loggedOut = true })
            }
        }

        composeRule.onNodeWithTag("settings-signout").performScrollTo().performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithTag("logout-confirm").assertDoesNotExist()
        assertFalse(loggedOut)
    }

    @Test
    fun backupRow_navigatesToBackupScreen() {
        setNav()

        composeRule.onNodeWithTag("account-button").performClick()
        composeRule.onNodeWithTag("settings-backup").performClick()

        composeRule.onNodeWithTag("backup-screen").assertExists()
        composeRule.onNodeWithTag("backup-toggle").assertIsDisplayed()
        composeRule.onNodeWithTag("backup-now").assertExists()
    }

    @Test
    fun backupToggle_withZeroFolders_opensFolderSheetInsteadOfEnabling() {
        var toggled: Boolean? = null
        composeRule.setContent {
            PhotosTheme {
                BackupScreen(
                    enabled = false,
                    running = false,
                    done = 0,
                    total = 0,
                    currentName = null,
                    lastCompletedAt = null,
                    selectedFolderCount = 0,
                    folders = emptyList(),
                    onBack = {},
                    onToggle = { toggled = it },
                )
            }
        }

        composeRule.onNodeWithTag("backup-toggle").performClick()

        // Enabling with nothing selected must not start a run — it opens the picker instead.
        assertTrue(toggled == null)
        composeRule.onNodeWithTag("backup-folder-sheet").assertExists()
    }
}
