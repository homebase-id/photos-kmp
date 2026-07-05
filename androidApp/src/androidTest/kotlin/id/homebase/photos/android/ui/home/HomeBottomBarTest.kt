package id.homebase.photos.android.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.homebase.photos.android.ui.components.HomeBottomBar
import id.homebase.photos.android.ui.components.HomeTab
import id.homebase.photos.android.ui.theme.PhotosTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compose UI test for the two-destination [HomeBottomBar] (contract C4 ids). */
@RunWith(AndroidJUnit4::class)
class HomeBottomBarTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bothTabs_renderWithContractIds() {
        composeRule.setContent {
            PhotosTheme {
                HomeBottomBar(selectedTab = HomeTab.Photos, onTabSelected = {})
            }
        }

        composeRule.onNodeWithTag("bottom-nav").assertExists()
        composeRule.onNodeWithTag("tab-photos").assertIsDisplayed()
        composeRule.onNodeWithTag("tab-collections").assertIsDisplayed()
    }

    @Test
    fun tabClick_firesCallbackWithDestination() {
        var selected: HomeTab? = null
        composeRule.setContent {
            PhotosTheme {
                HomeBottomBar(selectedTab = HomeTab.Photos, onTabSelected = { selected = it })
            }
        }

        composeRule.onNodeWithTag("tab-collections").performClick()

        assertEquals(HomeTab.Collections, selected)
    }

    @Test
    fun reclickingSelectedTab_stillReportsIt() {
        var selected: HomeTab? = null
        composeRule.setContent {
            PhotosTheme {
                HomeBottomBar(selectedTab = HomeTab.Photos, onTabSelected = { selected = it })
            }
        }

        assertNull(selected)
        composeRule.onNodeWithTag("tab-photos").performClick()

        assertEquals(HomeTab.Photos, selected)
    }
}
