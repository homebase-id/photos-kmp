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
 * Archive contract: local-paged via [id.homebase.photos.data.PhotosRepository.loadArchivedPage]
 * (beforeUserDate, mirrors [id.homebase.photos.timeline.TimelineViewModel.loadPage]'s cursor contract),
 * and [ArchiveViewModel.unarchiveSelectedAndWait] optimistically drops succeeded items and clears
 * selection unconditionally — no reload, so a deep `loadMore` session keeps its loaded depth.
 */
class ArchiveViewModelTest {

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

    // Newest first, mirroring the local index's userDate DESC order.
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
    fun init_loadsArchivedIntoMonthSections() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(archived = listOf(p3, p2, p1))
        val vm = ArchiveViewModel(repo)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertEquals(listOf(p3, p2, p1), state.pagedItems)
        assertTrue(state.sections.isNotEmpty())
        assertTrue(state.endReached)
    }

    @Test
    fun init_withNoArchived_isEmptyAndEndReached() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository()
        val vm = ArchiveViewModel(repo)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertTrue(state.pagedItems.isEmpty())
        assertTrue(state.sections.isEmpty())
        assertTrue(state.endReached)
    }

    @Test
    fun loadMore_paginatesOlderByUserDate() = runTest(dispatcher) {
        // PAGE_SIZE full items + 1 older one so the first page doesn't reach the end.
        val fullPage = (0 until ArchiveViewModel.PAGE_SIZE).map { item(2_000_000_000_000L - it * 1000L) }
        val extra = item(1_000_000_000_000L)
        val repo = FakeLibraryPhotosRepository(archived = fullPage + extra)
        val vm = ArchiveViewModel(repo)
        advanceUntilIdle()

        assertEquals(ArchiveViewModel.PAGE_SIZE, vm.state.value.pagedItems.size)
        assertFalse(vm.state.value.endReached)

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(ArchiveViewModel.PAGE_SIZE + 1, vm.state.value.pagedItems.size)
        assertTrue(vm.state.value.endReached)
    }

    @Test
    fun toggleSelection_andClearSelection() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(archived = listOf(p2, p1))
        val vm = ArchiveViewModel(repo)
        advanceUntilIdle()

        vm.toggleSelection(p1)
        assertTrue(vm.state.value.inSelectionMode)
        assertTrue(vm.state.value.isSelected(p1))

        vm.clearSelection()
        assertFalse(vm.state.value.inSelectionMode)
    }

    @Test
    fun unarchiveSelected_removesFromStateAndEmitsEvent() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(archived = listOf(p2, p1))
        val vm = ArchiveViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<ArchiveEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()
        val syncCountBeforeMutation = repo.syncCount // init's refresh already synced once

        vm.toggleSelection(p1)
        vm.unarchiveSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p2), state.pagedItems)
        assertTrue(state.selectedIds.isEmpty())
        assertEquals(listOf(listOf(p1.fileId) to false), repo.setArchivedCalls)
        assertEquals(listOf<ArchiveEvent>(ArchiveEvent.Unarchived(1, 0)), events)
        assertEquals(
            syncCountBeforeMutation + 1,
            repo.syncCount,
            "a successful unarchive must reconcile the local index",
        )
        collector.cancel()
    }

    @Test
    fun unarchiveSelected_afterLoadMore_keepsLoadedDepthMinusMutated() = runTest(dispatcher) {
        // PAGE_SIZE + 5 loaded: a wrongly-triggered refresh would clip the reload back to
        // PAGE_SIZE (the page limit), which is NOT what "depth minus one mutated item" is.
        val fullPage = (0 until ArchiveViewModel.PAGE_SIZE).map { item(2_000_000_000_000L - it * 1000L) }
        val extra = (0 until 5).map { item(1_000_000_000_000L - it * 1000L) }
        val repo = FakeLibraryPhotosRepository(archived = fullPage + extra)
        val vm = ArchiveViewModel(repo)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(ArchiveViewModel.PAGE_SIZE + 5, vm.state.value.pagedItems.size)

        val target = vm.state.value.pagedItems.last()
        vm.toggleSelection(target)
        vm.unarchiveSelectedAndWait()
        advanceUntilIdle()

        assertEquals(ArchiveViewModel.PAGE_SIZE + 4, vm.state.value.pagedItems.size)
        assertTrue(vm.state.value.pagedItems.none { it.fileId == target.fileId })
    }

    @Test
    fun unarchiveSelected_partialFailure_keepsFailedItemButClearsSelection() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(archived = listOf(p2, p1), failArchivedFor = setOf(p1.fileId))
        val vm = ArchiveViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<ArchiveEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleSelection(p1)
        vm.toggleSelection(p2)
        vm.unarchiveSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p1), state.pagedItems)
        assertTrue(state.selectedIds.isEmpty())
        assertEquals(ArchiveEvent.Unarchived(1, 1), events.first())
        assertTrue(events.last() is ArchiveEvent.Error)
        collector.cancel()
    }

    @Test
    fun unarchiveSelected_onThrow_leavesStateUntouchedAndEmitsError() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(archived = listOf(p2, p1), setArchivedThrows = true)
        val vm = ArchiveViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<ArchiveEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()
        val syncCountBeforeMutation = repo.syncCount

        vm.toggleSelection(p1)
        vm.unarchiveSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p2, p1), state.pagedItems)
        assertTrue(state.isSelected(p1))
        assertFalse(state.isMutating)
        assertTrue(events.single() is ArchiveEvent.Error)
        assertEquals(syncCountBeforeMutation, repo.syncCount, "a failed unarchive must not fire a background sync")
        collector.cancel()
    }

    @Test
    fun refreshAndWait_syncsBeforeReadingTheLocalIndex() = runTest(dispatcher) {
        // The archived photo only appears once "sync" has run — proves refreshAndWait's
        // read happens after (not before, and not without) its sync call.
        val repo = FakeLibraryPhotosRepository()
        repo.onSync = { repo.archived += p1 }
        val vm = ArchiveViewModel(repo)
        advanceUntilIdle()

        assertEquals(listOf(p1), vm.state.value.pagedItems)
        assertEquals(1, repo.syncCount)
    }

    @Test
    fun refreshAndWait_onSyncFailure_keepsExistingContentAndEmitsError() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(archived = listOf(p1))
        val vm = ArchiveViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<ArchiveEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        repo.syncThrows = true
        vm.refreshAndWait()
        advanceUntilIdle()

        assertEquals(listOf(p1), vm.state.value.pagedItems, "a failed sync must not wipe the grid")
        assertFalse(vm.state.value.isLoading)
        assertTrue(events.any { it is ArchiveEvent.Error })
        collector.cancel()
    }

    @Test
    fun unarchiveSelected_whileMutating_isNoOp() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeLibraryPhotosRepository(archived = listOf(p2, p1), mutationGate = gate)
        val vm = ArchiveViewModel(repo)
        advanceUntilIdle()

        vm.toggleSelection(p1)
        val inFlight = launch { vm.unarchiveSelectedAndWait() }
        advanceUntilIdle() // parked on the gate with isMutating = true
        assertTrue(vm.state.value.isMutating)

        vm.unarchiveSelectedAndWait() // guarded: returns without a second repository call
        assertEquals(1, repo.setArchivedCalls.size)

        gate.complete(Unit)
        advanceUntilIdle()
        inFlight.join()
        assertFalse(vm.state.value.isMutating)
    }
}
