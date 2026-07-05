package id.homebase.photos.timeline

import id.homebase.photos.data.PhotosRepository
import id.homebase.photos.domain.PhotoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Selection-mode contract (C1): [TimelineUiState.selectedIds] tracks dashed-Uuid string keys;
 * selection mode simply means "any id selected". Pure state transitions — no I/O involved.
 */
class TimelineSelectionTest {

    /** Fake repo: serves [pages] on the newest page (null cursor), empty otherwise. */
    private class FixedPageRepository(private val pages: List<PhotoItem>) : PhotosRepository {
        override fun observePhotos(): Flow<List<PhotoItem>> = MutableStateFlow(pages).asStateFlow()

        override suspend fun loadPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> =
            if (beforeUserDate == null) pages.take(limit) else emptyList()

        override suspend fun sync() {}

        override suspend fun deletePhotos(fileIds: List<Uuid>): Boolean = true

        override suspend fun loadThumbnailBytes(item: PhotoItem, maxDim: Int): ByteArray? = null
    }

    private fun item(userDate: Long): PhotoItem = PhotoItem(
        fileId = Uuid.random(),
        uniqueId = Uuid.random(),
        userDate = userDate,
        isVideo = false,
        pixelWidth = 900,
        pixelHeight = 1200,
        previewPlaceholder = null,
        driveId = Uuid.random(),
        payloadKey = "dflt_key",
    )

    // Newest first so the fake page mirrors the real index's userDate DESC order.
    private val p2 = item(1_700_000_100_000L)
    private val p1 = item(1_700_000_000_000L)

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun loadedViewModel(): TimelineViewModel =
        TimelineViewModel(FixedPageRepository(listOf(p2, p1)))

    @Test
    fun toggleSelection_entersSelectionMode_andSelectsPhoto() = runTest(dispatcher) {
        val vm = loadedViewModel()
        advanceUntilIdle()

        vm.toggleSelection(p1)

        val state = vm.state.value
        assertTrue(state.inSelectionMode)
        assertTrue(state.isSelected(p1))
        assertFalse(state.isSelected(p2))
    }

    @Test
    fun toggleSelection_samePhotoTwice_exitsSelectionMode() = runTest(dispatcher) {
        val vm = loadedViewModel()
        advanceUntilIdle()

        vm.toggleSelection(p1)
        vm.toggleSelection(p1)

        assertFalse(vm.state.value.inSelectionMode)
        assertFalse(vm.state.value.isSelected(p1))
    }

    @Test
    fun clearSelection_emptiesSelectedIds() = runTest(dispatcher) {
        val vm = loadedViewModel()
        advanceUntilIdle()

        vm.toggleSelection(p1)
        vm.toggleSelection(p2)
        vm.clearSelection()

        assertTrue(vm.state.value.selectedIds.isEmpty())
        assertFalse(vm.state.value.inSelectionMode)
    }

    @Test
    fun selection_keysAreDashedUuidStrings() = runTest(dispatcher) {
        val vm = loadedViewModel()
        advanceUntilIdle()

        vm.toggleSelection(p1)

        assertEquals(setOf(p1.fileId.toString()), vm.state.value.selectedIds)
        assertTrue(vm.state.value.selectedIds.single().contains("-"), "keys must be dashed, never bare hex")
    }
}
