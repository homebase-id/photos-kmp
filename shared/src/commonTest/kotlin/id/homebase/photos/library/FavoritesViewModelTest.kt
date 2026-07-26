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
 * Favorites contract: server-paged via [id.homebase.photos.data.PhotosRepository.loadFavoritesPage]
 * (cursor, not beforeUserDate), Timeline-parity selection, and [FavoritesViewModel.unfavoriteSelectedAndWait]
 * optimistically drops succeeded items, clears selection unconditionally, and reconciles with a refresh.
 */
class FavoritesViewModelTest {

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
        isFavorite = true,
    )

    // Newest first, mirroring the server's userDate DESC contract.
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
    fun init_loadsFavoritesIntoMonthSections() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(favorites = listOf(p3, p2, p1))
        val vm = FavoritesViewModel(repo)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertEquals(listOf(p3, p2, p1), state.pagedItems)
        assertTrue(state.sections.isNotEmpty())
        assertTrue(state.endReached)
    }

    @Test
    fun loadMore_appendsNextCursorPage() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(favorites = listOf(p3, p2, p1), favoritesPageSize = 2)
        val vm = FavoritesViewModel(repo)
        advanceUntilIdle()

        assertEquals(listOf(p3, p2), vm.state.value.pagedItems)
        assertFalse(vm.state.value.endReached)

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(p3, p2, p1), vm.state.value.pagedItems)
        assertTrue(vm.state.value.endReached)
    }

    @Test
    fun toggleSelection_andClearSelection() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(favorites = listOf(p2, p1))
        val vm = FavoritesViewModel(repo)
        advanceUntilIdle()

        vm.toggleSelection(p1)
        assertTrue(vm.state.value.inSelectionMode)
        assertTrue(vm.state.value.isSelected(p1))

        vm.clearSelection()
        assertFalse(vm.state.value.inSelectionMode)
    }

    @Test
    fun unfavoriteSelected_removesFromStateAndEmitsEvent() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(favorites = listOf(p2, p1))
        val vm = FavoritesViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<FavoritesEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleSelection(p1)
        vm.unfavoriteSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p2), state.pagedItems)
        assertTrue(state.selectedIds.isEmpty())
        assertEquals(listOf(p1.fileId to false), repo.setFavoriteCalls)
        assertEquals(listOf<FavoritesEvent>(FavoritesEvent.Unfavorited(1, 0)), events)
        collector.cancel()
    }

    @Test
    fun unfavoriteSelected_partialFailure_keepsFailedItemButClearsSelection() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(favorites = listOf(p2, p1), failFavoriteFor = setOf(p1.fileId))
        val vm = FavoritesViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<FavoritesEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleSelection(p1)
        vm.toggleSelection(p2)
        vm.unfavoriteSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p1), state.pagedItems)
        assertTrue(state.selectedIds.isEmpty())
        assertEquals(listOf<FavoritesEvent>(FavoritesEvent.Unfavorited(1, 1)), events)
        collector.cancel()
    }

    @Test
    fun unfavoriteSelected_onThrow_leavesStateUntouchedAndEmitsError() = runTest(dispatcher) {
        val repo = FakeLibraryPhotosRepository(favorites = listOf(p2, p1), setFavoriteThrows = true)
        val vm = FavoritesViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<FavoritesEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleSelection(p1)
        vm.unfavoriteSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p2, p1), state.pagedItems)
        assertTrue(state.isSelected(p1))
        assertFalse(state.isMutating)
        assertTrue(events.single() is FavoritesEvent.Error)
        collector.cancel()
    }

    @Test
    fun unfavoriteSelected_whileMutating_isNoOp() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeLibraryPhotosRepository(favorites = listOf(p2, p1), mutationGate = gate)
        val vm = FavoritesViewModel(repo)
        advanceUntilIdle()

        vm.toggleSelection(p1)
        val inFlight = launch { vm.unfavoriteSelectedAndWait() }
        advanceUntilIdle() // parked on the gate with isMutating = true
        assertTrue(vm.state.value.isMutating)

        vm.unfavoriteSelectedAndWait() // guarded: returns without a second repository call
        assertEquals(1, repo.setFavoriteCalls.size)

        gate.complete(Unit)
        advanceUntilIdle()
        inFlight.join()
        assertFalse(vm.state.value.isMutating)
    }
}
