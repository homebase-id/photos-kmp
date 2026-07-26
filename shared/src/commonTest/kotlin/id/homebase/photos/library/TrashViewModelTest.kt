package id.homebase.photos.library

import id.homebase.photos.domain.PhotoItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
 * Trash contract: local-paged via [id.homebase.photos.data.PhotosRepository.loadTrashPage]
 * (same cursor contract as Archive). [TrashViewModel.restoreSelectedAndWait] optimistically drops
 * succeeded items with no reload (a deep `loadMore` session keeps its loaded depth);
 * [TrashViewModel.permanentDeleteSelectedAndWait] is all-or-nothing like
 * [id.homebase.photos.timeline.TimelineViewModel.deleteSelectedAndWait] — both share one
 * in-flight guard.
 */
class TrashViewModelTest {

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

    private val p3 = item(1_700_000_200_000L)
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
    fun init_loadsTrashIntoMonthSections() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(trashed = listOf(p3, p2, p1))
        val vm = TrashViewModel(repo)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertEquals(listOf(p3, p2, p1), state.pagedItems)
        assertTrue(state.sections.isNotEmpty())
        assertTrue(state.endReached)
    }

    @Test
    fun init_withNoTrash_isEmptyAndEndReached() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository()
        val vm = TrashViewModel(repo)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertTrue(state.pagedItems.isEmpty())
        assertTrue(state.sections.isEmpty())
        assertTrue(state.endReached)
    }

    @Test
    fun loadMore_paginatesOlderByUserDate() = runTest(dispatcher) {
        val fullPage = (0 until TrashViewModel.PAGE_SIZE).map { item(2_000_000_000_000L - it * 1000L) }
        val extra = item(1_000_000_000_000L)
        val repo = FakeLibraryPhotosRepository(trashed = fullPage + extra)
        val vm = TrashViewModel(repo)
        advanceUntilIdle()

        assertEquals(TrashViewModel.PAGE_SIZE, vm.state.value.pagedItems.size)
        assertFalse(vm.state.value.endReached)

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(TrashViewModel.PAGE_SIZE + 1, vm.state.value.pagedItems.size)
        assertTrue(vm.state.value.endReached)
    }

    @Test
    fun toggleSelection_andClearSelection() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(trashed = listOf(p2, p1))
        val vm = TrashViewModel(repo)
        advanceUntilIdle()

        vm.toggleSelection(p1)
        assertTrue(vm.state.value.inSelectionMode)
        assertTrue(vm.state.value.isSelected(p1))

        vm.clearSelection()
        assertFalse(vm.state.value.inSelectionMode)
    }

    @Test
    fun restoreSelected_removesFromStateAndEmitsEvent() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(trashed = listOf(p2, p1))
        val vm = TrashViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<TrashEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()
        val syncCountBeforeMutation = repo.syncCount // init's refresh already synced once

        vm.toggleSelection(p1)
        vm.restoreSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p2), state.pagedItems)
        assertTrue(state.selectedIds.isEmpty())
        assertEquals(listOf(listOf(p1.fileId)), repo.restoreCalls)
        assertEquals(listOf<TrashEvent>(TrashEvent.Restored(1, 0)), events)
        assertEquals(
            syncCountBeforeMutation + 1,
            repo.syncCount,
            "a successful restore must reconcile the local index",
        )
        collector.cancel()
    }

    @Test
    fun restoreSelected_afterLoadMore_keepsLoadedDepthMinusMutated() = runTest(dispatcher) {
        // PAGE_SIZE + 5 loaded: a wrongly-triggered refresh would clip the reload back to
        // PAGE_SIZE (the page limit), which is NOT what "depth minus one mutated item" is.
        val fullPage = (0 until TrashViewModel.PAGE_SIZE).map { item(2_000_000_000_000L - it * 1000L) }
        val extra = (0 until 5).map { item(1_000_000_000_000L - it * 1000L) }
        val repo = FakeLibraryPhotosRepository(trashed = fullPage + extra)
        val vm = TrashViewModel(repo)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(TrashViewModel.PAGE_SIZE + 5, vm.state.value.pagedItems.size)

        val target = vm.state.value.pagedItems.last()
        vm.toggleSelection(target)
        vm.restoreSelectedAndWait()
        advanceUntilIdle()

        assertEquals(TrashViewModel.PAGE_SIZE + 4, vm.state.value.pagedItems.size)
        assertTrue(vm.state.value.pagedItems.none { it.fileId == target.fileId })
    }

    @Test
    fun restoreSelected_partialFailure_keepsFailedItemButClearsSelection() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(trashed = listOf(p2, p1), failRestoreFor = setOf(p1.fileId))
        val vm = TrashViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<TrashEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleSelection(p1)
        vm.toggleSelection(p2)
        vm.restoreSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p1), state.pagedItems)
        assertTrue(state.selectedIds.isEmpty())
        assertEquals(TrashEvent.Restored(1, 1), events.first())
        assertTrue(events.last() is TrashEvent.Error)
        collector.cancel()
    }

    @Test
    fun restoreSelected_onThrow_leavesStateUntouchedAndEmitsError() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(trashed = listOf(p2, p1), restoreThrows = true)
        val vm = TrashViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<TrashEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()
        val syncCountBeforeMutation = repo.syncCount

        vm.toggleSelection(p1)
        vm.restoreSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p2, p1), state.pagedItems)
        assertTrue(state.isSelected(p1))
        assertFalse(state.isMutating)
        assertTrue(events.single() is TrashEvent.Error)
        assertEquals(syncCountBeforeMutation, repo.syncCount, "a failed restore must not fire a background sync")
        collector.cancel()
    }

    @Test
    fun permanentDeleteSelected_removesFromStateAndEmitsEvent() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(trashed = listOf(p2, p1))
        val vm = TrashViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<TrashEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()
        val syncCountBeforeMutation = repo.syncCount

        vm.toggleSelection(p1)
        vm.permanentDeleteSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p2), state.pagedItems)
        assertTrue(state.selectedIds.isEmpty())
        assertEquals(listOf(listOf(p1.fileId)), repo.permanentDeleteCalls)
        assertEquals(listOf<TrashEvent>(TrashEvent.PermanentlyDeleted(1)), events)
        assertEquals(
            syncCountBeforeMutation + 1,
            repo.syncCount,
            "a successful permanent delete must reconcile the local index",
        )
        collector.cancel()
    }

    @Test
    fun permanentDeleteSelected_onFailure_keepsSelectionAndEmitsError() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(trashed = listOf(p2, p1), permanentDeleteResult = false)
        val vm = TrashViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<TrashEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()
        val syncCountBeforeMutation = repo.syncCount

        vm.toggleSelection(p1)
        vm.permanentDeleteSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p2, p1), state.pagedItems)
        assertTrue(state.isSelected(p1))
        assertFalse(state.isMutating)
        assertTrue(events.single() is TrashEvent.Error)
        assertEquals(
            syncCountBeforeMutation,
            repo.syncCount,
            "a failed permanent delete must not fire a background sync",
        )
        collector.cancel()
    }

    @Test
    fun refreshAndWait_syncsBeforeReadingTheLocalIndex() = runTest(dispatcher) {
        // The trashed photo only appears once "sync" has run — proves refreshAndWait's
        // read happens after (not before, and not without) its sync call.
        val repo = FakeLibraryPhotosRepository()
        repo.onSync = { repo.trashed += p1 }
        val vm = TrashViewModel(repo)
        advanceUntilIdle()

        assertEquals(listOf(p1), vm.state.value.pagedItems)
        assertEquals(1, repo.syncCount)
    }

    @Test
    fun refreshAndWait_onSyncFailure_keepsExistingContentAndEmitsError() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(trashed = listOf(p1))
        val vm = TrashViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<TrashEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        repo.syncThrows = true
        vm.refreshAndWait()
        advanceUntilIdle()

        assertEquals(listOf(p1), vm.state.value.pagedItems, "a failed sync must not wipe the grid")
        assertFalse(vm.state.value.isLoading)
        assertTrue(events.any { it is TrashEvent.Error })
        collector.cancel()
    }

    @Test
    fun restoreAndPermanentDelete_shareOneInFlightGuard() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeLibraryPhotosRepository(trashed = listOf(p2, p1), mutationGate = gate)
        val vm = TrashViewModel(repo)
        advanceUntilIdle()

        vm.toggleSelection(p1)
        val inFlight = launch { vm.restoreSelectedAndWait() }
        advanceUntilIdle() // parked on the gate with isMutating = true
        assertTrue(vm.state.value.isMutating)

        vm.permanentDeleteSelectedAndWait() // guarded by the same flag: no-op
        assertEquals(0, repo.permanentDeleteCalls.size)

        gate.complete(Unit)
        advanceUntilIdle()
        inFlight.join()
        assertFalse(vm.state.value.isMutating)
    }
}
