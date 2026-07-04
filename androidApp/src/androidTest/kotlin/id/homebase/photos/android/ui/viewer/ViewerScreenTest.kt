@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.photos.android.ui.viewer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import id.homebase.photos.android.ui.theme.PhotosTheme
import id.homebase.photos.domain.PhotoItem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Compose UI flow-test for the fullscreen [ViewerScreen] (plan 004 §A3). Drives the stateless screen
 * with a fixed mock [PhotoItem] list and a plain Coil loader (requests fall back to placeholder on the
 * fake ids — no network), asserting structure only: the viewer root and chrome exist, the back target
 * dismisses, and a page tap toggles the chrome away.
 */
@RunWith(AndroidJUnit4::class)
class ViewerScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun mockItems(count: Int): List<PhotoItem> = (0 until count).map { seed ->
        val id = Uuid.random()
        PhotoItem(
            fileId = id,
            uniqueId = id,
            userDate = 1_718_000_000_000L + seed * 86_400_000L,
            isVideo = false,
            pixelWidth = 900,
            pixelHeight = 1200,
            previewPlaceholder = null,
            driveId = Uuid.random(),
            payloadKey = "dflt_key",
        )
    }

    // Plain loader — the Homebase fetcher/keyer graph isn't needed; fake ids fail and fall to placeholder.
    private fun loader(): ImageLoader = ImageLoader.Builder(composeRule.activity).build()

    @Test
    fun rendersViewerRootAndChrome() {
        composeRule.setContent {
            PhotosTheme {
                ViewerScreen(
                    items = mockItems(3),
                    initialIndex = 0,
                    imageLoader = loader(),
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("viewer-root").assertExists()
        composeRule.onNodeWithTag("viewer-back").assertExists()
    }

    @Test
    fun backTargetInvokesOnDismiss() {
        var dismissed = false
        composeRule.setContent {
            PhotosTheme {
                ViewerScreen(
                    items = mockItems(3),
                    initialIndex = 0,
                    imageLoader = loader(),
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithTag("viewer-back").performClick()

        assertTrue(dismissed)
    }

    @Test
    fun tappingPageTogglesChromeAway() {
        composeRule.setContent {
            PhotosTheme {
                ViewerScreen(
                    items = mockItems(3),
                    initialIndex = 0,
                    imageLoader = loader(),
                    onDismiss = {},
                )
            }
        }

        // Chrome starts visible.
        composeRule.onNodeWithTag("viewer-back").assertExists()

        // A single tap on the page toggles the chrome off → the back target disappears.
        composeRule.onAllNodesWithTag("viewer-page").onFirst().performClick()

        composeRule.onNodeWithTag("viewer-back").assertDoesNotExist()
    }
}
