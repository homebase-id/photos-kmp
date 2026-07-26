package id.homebase.photos.android.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.homebase.photos.android.ui.backup.BackupScreen
import id.homebase.photos.android.ui.components.FloatingNavBar
import id.homebase.photos.android.ui.nav.Route
import id.homebase.photos.android.ui.search.SearchScreen
import id.homebase.photos.android.ui.settings.SettingsScreen
import id.homebase.photos.android.ui.theme.PhotosTheme
import id.homebase.photos.search.SearchUiState
import id.homebase.photos.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI flow-test for the new floating shell over a real [NavHost]. Wires the actual
 * [FloatingNavBar] + the stateless [SearchScreen] / [SettingsScreen] / [BackupScreen] (fixed
 * states — no Koin graph) with stub feed content (the real feeds need the Koin graph) and asserts
 * the shell's destinations render, feed switching swaps content, and Search/Settings/Backup open
 * the real screens.
 */
@RunWith(AndroidJUnit4::class)
class AppShellNavTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun setShell() {
        composeRule.setContent {
            PhotosTheme {
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route
                Box(Modifier.fillMaxSize()) {
                    NavHost(nav, startDestination = Route.Photos.path, modifier = Modifier.fillMaxSize()) {
                        composable(Route.Photos.path) {
                            Column {
                                Text("PhotosContent", Modifier.testTag("content-photos"))
                                // Stands in for the top bar's account button (needs the Koin graph).
                                Text(
                                    "OpenSettings",
                                    Modifier
                                        .clickable { nav.navigate(Route.Settings.path) }
                                        .testTag("open-settings"),
                                )
                            }
                        }
                        composable(Route.Collections.path) {
                            Text("CollectionsContent", Modifier.testTag("content-collections"))
                        }
                        composable(Route.Search.path) {
                            SearchScreen(state = SearchUiState(), onBack = {})
                        }
                        composable(Route.Settings.path) {
                            SettingsScreen(
                                state = SettingsUiState(),
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
                    FloatingNavBar(
                        currentRoute = route,
                        onPhotos = { nav.navigate(Route.Photos.path) },
                        onCollections = { nav.navigate(Route.Collections.path) },
                        onCreate = {},
                        onSearch = { nav.navigate(Route.Search.path) },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }

    @Test
    fun shell_showsAllDestinations() {
        setShell()

        composeRule.onNodeWithTag("bottom-nav").assertExists()
        composeRule.onNodeWithTag("tab-photos").assertIsDisplayed()
        composeRule.onNodeWithTag("tab-collections").assertIsDisplayed()
        composeRule.onNodeWithTag("tab-create").assertIsDisplayed()
        composeRule.onNodeWithTag("search-button").assertIsDisplayed()
        composeRule.onNodeWithTag("content-photos").assertExists()
    }

    @Test
    fun tappingCollections_switchesContent() {
        setShell()

        composeRule.onNodeWithTag("tab-collections").performClick()

        composeRule.onNodeWithTag("content-collections").assertExists()
    }

    @Test
    fun tappingSearch_opensSearchScreen() {
        setShell()

        composeRule.onNodeWithTag("search-button").performClick()

        composeRule.onNodeWithTag("search-screen").assertExists()
        composeRule.onNodeWithTag("search-field").assertExists()
        composeRule.onNodeWithTag("search-recent").assertExists()
    }

    @Test
    fun navigatingToSettings_showsSettingsScreen() {
        setShell()

        composeRule.onNodeWithTag("open-settings").performClick()

        composeRule.onNodeWithTag("settings-root").assertExists()
        composeRule.onNodeWithTag("settings-account").assertExists()
    }

    @Test
    fun settingsBackupRow_opensBackupScreen() {
        setShell()

        composeRule.onNodeWithTag("open-settings").performClick()
        composeRule.onNodeWithTag("settings-backup").performClick()

        composeRule.onNodeWithTag("backup-screen").assertExists()
        composeRule.onNodeWithTag("backup-toggle").assertExists()
    }
}
