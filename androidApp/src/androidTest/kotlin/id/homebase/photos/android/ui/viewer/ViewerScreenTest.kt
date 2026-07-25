@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.photos.android.ui.viewer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import id.homebase.photos.android.ui.theme.PhotosTheme
import id.homebase.photos.data.MockPhotosRepository
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.viewer.ViewerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Compose UI flow-test for the VM-driven fullscreen [ViewerScreen] (Batch B). Drives the screen
 * with a directly-constructed [ViewerViewModel] over [MockPhotosRepository] (no Koin graph) and a
 * plain Coil loader (requests fall back to placeholder on the fake ids — no network). Asserts the
 * contract surface: root + chrome + action bar render, the info sheet opens, delete confirms via
 * `delete-confirm` and removes the current item, and dismissal reports `deletedAny`.
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

    private fun setViewer(
        viewModel: ViewerViewModel,
        onDismiss: (Boolean) -> Unit = {},
    ) {
        composeRule.setContent {
            PhotosTheme {
                ViewerScreen(
                    items = viewModel.state.value.items,
                    initialIndex = viewModel.state.value.index,
                    imageLoader = loader(),
                    onDismiss = onDismiss,
                    viewModel = viewModel,
                )
            }
        }
    }

    private fun vm(count: Int, initialIndex: Int = 0): ViewerViewModel =
        ViewerViewModel(mockItems(count), initialIndex, MockPhotosRepository())

    @Test
    fun rendersRootChromeAndActionBar() {
        setViewer(vm(3))

        composeRule.onNodeWithTag("viewer-root").assertExists()
        composeRule.onNodeWithTag("viewer-back").assertExists()
        composeRule.onNodeWithTag("viewer-actionbar").assertIsDisplayed()
        composeRule.onNodeWithTag("viewer-share").assertIsDisplayed()
        composeRule.onNodeWithTag("viewer-delete").assertIsDisplayed()
        composeRule.onNodeWithTag("viewer-info").assertIsDisplayed()
    }

    @Test
    fun backTargetDismissesWithoutDeletes() {
        var dismissed = false
        var reportedDeletedAny = true
        setViewer(vm(3)) { deletedAny ->
            dismissed = true
            reportedDeletedAny = deletedAny
        }

        composeRule.onNodeWithTag("viewer-back").performClick()

        assertTrue(dismissed)
        assertEquals(false, reportedDeletedAny)
    }

    @Test
    fun infoButtonOpensInfoSheet() {
        setViewer(vm(3))

        composeRule.onNodeWithTag("viewer-info").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("viewer-info-sheet").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun deleteFlowConfirmsAndRemovesCurrentItem() {
        val viewModel = vm(3)
        setViewer(viewModel)

        composeRule.onNodeWithTag("viewer-delete").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("delete-confirm").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("delete-confirm").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.items.size == 2
        }
        assertTrue(viewModel.state.value.deletedAny)
    }

    @Test
    fun tappingPageTogglesChromeAway() {
        setViewer(vm(3))

        // Chrome starts visible.
        composeRule.onNodeWithTag("viewer-back").assertExists()

        // A single tap on the page toggles the chrome off — the tap fires after the double-tap
        // discrimination window, so poll instead of asserting synchronously (auto-hide is 3s,
        // beyond this window, so a pass here is the tap, not the timer).
        composeRule.onAllNodesWithTag("viewer-page").onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 2_000) {
            composeRule.onAllNodesWithTag("viewer-back").fetchSemanticsNodes().isEmpty()
        }
    }
}
