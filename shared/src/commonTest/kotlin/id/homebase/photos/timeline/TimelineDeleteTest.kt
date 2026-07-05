package id.homebase.photos.timeline

import id.homebase.photos.data.PhotosRepository
import id.homebase.photos.domain.PhotoItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * Delete contract (C1 delete half): [TimelineViewModel.deleteSelectedAndWait] batch-deletes the
 * selection through the repository, prunes state + sections on success and emits
 * [TimelineEvent.Deleted]; on failure it keeps the selection and emits [TimelineEvent.Error].
 */
class TimelineDeleteTest {

    /** Fake repo: fixed newest page; records delete batches; failure/gating knobs per test. */
    private class RecordingDeleteRepository(
        private val pages: List<PhotoItem>,
        var deleteResult: Boolean = true,
        var deleteThrows: Boolean = false,
        val deleteGate: CompletableDeferred<Unit>? = null,
    ) : PhotosRepository {
        val deletedBatches = mutableListOf<List<Uuid>>()

        override fun observePhotos(): Flow<List<PhotoItem>> = MutableStateFlow(pages).asStateFlow()

        override suspend fun loadPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> =
            if (beforeUserDate == null) pages.take(limit) else emptyList()

        override suspend fun sync() {}

        override suspend fun deletePhotos(fileIds: List<Uuid>): Boolean {
            deletedBatches += fileIds
            deleteGate?.await()
            if (deleteThrows) throw IllegalStateException("delete exploded")
            return deleteResult
        }

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

    @Test
    fun deleteSelected_removesPhotosFromStateAndEmitsDeleted() = runTest(dispatcher) {
        val repo = RecordingDeleteRepository(listOf(p2, p1))
        val vm = TimelineViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<TimelineEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleSelection(p1)
        vm.deleteSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p2), state.pagedItems)
        assertTrue(state.sections.flatMap { it.items }.none { it.fileId == p1.fileId })
        assertTrue(state.selectedIds.isEmpty())
        assertFalse(state.isDeleting)
        assertEquals(listOf(listOf(p1.fileId)), repo.deletedBatches)
        assertEquals(listOf<TimelineEvent>(TimelineEvent.Deleted(1)), events)
        collector.cancel()
    }

    @Test
    fun deleteSelected_onRepositoryFailure_keepsSelectionAndEmitsError() = runTest(dispatcher) {
        val repo = RecordingDeleteRepository(listOf(p2, p1), deleteResult = false)
        val vm = TimelineViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<TimelineEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleSelection(p1)
        vm.deleteSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p2, p1), state.pagedItems)
        assertTrue(state.isSelected(p1))
        assertFalse(state.isDeleting)
        assertTrue(events.single() is TimelineEvent.Error)
        collector.cancel()
    }

    @Test
    fun deleteSelected_onRepositoryThrow_keepsSelectionAndEmitsError() = runTest(dispatcher) {
        val repo = RecordingDeleteRepository(listOf(p2, p1), deleteThrows = true)
        val vm = TimelineViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<TimelineEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleSelection(p1)
        vm.deleteSelectedAndWait()
        advanceUntilIdle()

        assertEquals(listOf(p2, p1), vm.state.value.pagedItems)
        assertTrue(vm.state.value.isSelected(p1))
        assertTrue(events.single() is TimelineEvent.Error)
        collector.cancel()
    }

    @Test
    fun deleteSelected_whileDeleting_isNoOp() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = RecordingDeleteRepository(listOf(p2, p1), deleteGate = gate)
        val vm = TimelineViewModel(repo)
        advanceUntilIdle()

        vm.toggleSelection(p1)
        val inFlight = launch { vm.deleteSelectedAndWait() }
        advanceUntilIdle() // parked on the gate with isDeleting = true
        assertTrue(vm.state.value.isDeleting)

        vm.deleteSelectedAndWait() // guarded: returns without a second repository call
        assertEquals(1, repo.deletedBatches.size)

        gate.complete(Unit)
        advanceUntilIdle()
        inFlight.join()
        assertFalse(vm.state.value.isDeleting)
    }
}
