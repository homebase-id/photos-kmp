package id.homebase.photos.viewer

import id.homebase.photos.data.FavoritesPage
import id.homebase.photos.data.PhotoStatusResult
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
 * Viewer contract (Batch B): [ViewerViewModel.setIndex] clamps into the list,
 * [ViewerViewModel.deleteCurrentAndWait] deletes through the repository, prunes the
 * current item + clamps the index on success (emitting [ViewerEvent.Closed] when the
 * list empties), and emits [ViewerEvent.Error] leaving state untouched on failure.
 */
class ViewerViewModelTest {

    /** Fake repo: records delete batches; failure/gating knobs per test. */
    private class RecordingDeleteRepository(
        var deleteResult: Boolean = true,
        var deleteThrows: Boolean = false,
        val deleteGate: CompletableDeferred<Unit>? = null,
        var favoriteResult: Boolean = true,
        var favoriteThrows: Boolean = false,
    ) : PhotosRepository {
        val deletedBatches = mutableListOf<List<Uuid>>()
        val favoriteCalls = mutableListOf<Pair<Uuid, Boolean>>()
        var syncCount = 0
            private set

        override fun observePhotos(): Flow<List<PhotoItem>> =
            MutableStateFlow(emptyList<PhotoItem>()).asStateFlow()

        override suspend fun loadPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> = emptyList()

        override suspend fun sync() { syncCount++ }

        override suspend fun deletePhotos(fileIds: List<Uuid>): Boolean {
            deletedBatches += fileIds
            deleteGate?.await()
            if (deleteThrows) throw IllegalStateException("delete exploded")
            return deleteResult
        }

        override suspend fun loadThumbnailBytes(item: PhotoItem, maxDim: Int): ByteArray? = null
        override suspend fun loadOriginalBytes(item: PhotoItem): ByteArray? = null
        override suspend fun prepareVideo(item: PhotoItem): VideoHandle? = null
        override suspend fun disposeVideo(handle: VideoHandle) {}

        override suspend fun setFavorite(fileId: Uuid, favorite: Boolean): Boolean {
            favoriteCalls += fileId to favorite
            if (favoriteThrows) throw IllegalStateException("favorite exploded")
            return favoriteResult
        }
        override suspend fun setArchived(fileIds: List<Uuid>, archived: Boolean): PhotoStatusResult = PhotoStatusResult()
        override suspend fun softDelete(fileIds: List<Uuid>): PhotoStatusResult = PhotoStatusResult()
        override suspend fun restore(fileIds: List<Uuid>): PhotoStatusResult = PhotoStatusResult()
        override suspend fun permanentDelete(fileIds: List<Uuid>): Boolean = true
        override suspend fun loadFavoritesPage(cursor: String?, limit: Int): FavoritesPage = FavoritesPage(emptyList(), null)
        override suspend fun loadArchivedPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> = emptyList()
        override suspend fun loadTrashPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> = emptyList()
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

    // Newest first, mirroring the userDate DESC lists the hosts hand over.
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
    fun initialIndex_isClampedIntoItems() {
        val repo = RecordingDeleteRepository()
        assertEquals(2, ViewerViewModel(listOf(p3, p2, p1), 99, repo).state.value.index)
        assertEquals(0, ViewerViewModel(listOf(p3, p2, p1), -4, repo).state.value.index)
        assertEquals(0, ViewerViewModel(emptyList(), 5, repo).state.value.index)
    }

    @Test
    fun setIndex_clampsOutOfRangeAndNegative() {
        val vm = ViewerViewModel(listOf(p3, p2, p1), 0, RecordingDeleteRepository())

        vm.setIndex(1)
        assertEquals(1, vm.state.value.index)
        assertEquals(p2, vm.state.value.current)

        vm.setIndex(99)
        assertEquals(2, vm.state.value.index)

        vm.setIndex(-1)
        assertEquals(0, vm.state.value.index)
    }

    @Test
    fun setIndex_onEmptyList_isNoOp() {
        val vm = ViewerViewModel(emptyList(), 0, RecordingDeleteRepository())
        vm.setIndex(3)
        assertEquals(0, vm.state.value.index)
        assertEquals(null, vm.state.value.current)
    }

    @Test
    fun deleteCurrent_removesItemAndKeepsIndexOnNext() = runTest(dispatcher) {
        val repo = RecordingDeleteRepository()
        val vm = ViewerViewModel(listOf(p3, p2, p1), 1, repo)
        val events = mutableListOf<ViewerEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.deleteCurrentAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p3, p1), state.items)
        assertEquals(1, state.index)
        assertEquals(p1, state.current)
        assertTrue(state.deletedAny)
        assertFalse(state.isDeleting)
        assertEquals(listOf(listOf(p2.fileId)), repo.deletedBatches)
        assertTrue(events.isEmpty()) // list not empty → no Closed
        collector.cancel()
    }

    @Test
    fun deleteCurrent_atLastElement_clampsIndexToNewLast() = runTest(dispatcher) {
        val repo = RecordingDeleteRepository()
        val vm = ViewerViewModel(listOf(p3, p2, p1), 2, repo)
        advanceUntilIdle()

        vm.deleteCurrentAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p3, p2), state.items)
        assertEquals(1, state.index)
        assertEquals(p2, state.current)
    }

    @Test
    fun deleteCurrent_onlyItem_emptiesListAndEmitsClosed() = runTest(dispatcher) {
        val repo = RecordingDeleteRepository()
        val vm = ViewerViewModel(listOf(p1), 0, repo)
        val events = mutableListOf<ViewerEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.deleteCurrentAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.items.isEmpty())
        assertEquals(0, state.index)
        assertEquals(null, state.current)
        assertTrue(state.deletedAny)
        assertEquals(listOf<ViewerEvent>(ViewerEvent.Closed), events)
        collector.cancel()
    }

    @Test
    fun deleteCurrent_onRepositoryFailure_emitsErrorAndMutatesNothing() = runTest(dispatcher) {
        val repo = RecordingDeleteRepository(deleteResult = false)
        val vm = ViewerViewModel(listOf(p3, p2, p1), 1, repo)
        val events = mutableListOf<ViewerEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.deleteCurrentAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p3, p2, p1), state.items)
        assertEquals(1, state.index)
        assertFalse(state.deletedAny)
        assertFalse(state.isDeleting)
        assertTrue(events.single() is ViewerEvent.Error)
        collector.cancel()
    }

    @Test
    fun deleteCurrent_onRepositoryThrow_emitsErrorAndMutatesNothing() = runTest(dispatcher) {
        val repo = RecordingDeleteRepository(deleteThrows = true)
        val vm = ViewerViewModel(listOf(p2, p1), 0, repo)
        val events = mutableListOf<ViewerEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.deleteCurrentAndWait()
        advanceUntilIdle()

        assertEquals(listOf(p2, p1), vm.state.value.items)
        assertFalse(vm.state.value.deletedAny)
        assertTrue(events.single() is ViewerEvent.Error)
        collector.cancel()
    }

    @Test
    fun deletedAny_flipsOnceAndStaysTrue() = runTest(dispatcher) {
        val repo = RecordingDeleteRepository()
        val vm = ViewerViewModel(listOf(p3, p2, p1), 0, repo)
        advanceUntilIdle()

        assertFalse(vm.state.value.deletedAny)
        vm.deleteCurrentAndWait()
        advanceUntilIdle()
        assertTrue(vm.state.value.deletedAny)

        vm.deleteCurrentAndWait()
        advanceUntilIdle()
        assertTrue(vm.state.value.deletedAny)
        assertEquals(listOf(p1), vm.state.value.items)
        assertEquals(2, repo.deletedBatches.size)
    }

    @Test
    fun deleteCurrent_whileDeleting_isNoOp() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = RecordingDeleteRepository(deleteGate = gate)
        val vm = ViewerViewModel(listOf(p2, p1), 0, repo)
        advanceUntilIdle()

        val inFlight = launch { vm.deleteCurrentAndWait() }
        advanceUntilIdle() // parked on the gate with isDeleting = true
        assertTrue(vm.state.value.isDeleting)

        vm.deleteCurrentAndWait() // guarded: returns without a second repository call
        assertEquals(1, repo.deletedBatches.size)

        gate.complete(Unit)
        advanceUntilIdle()
        inFlight.join()
        assertFalse(vm.state.value.isDeleting)
        assertEquals(listOf(p1), vm.state.value.items)
    }

    @Test
    fun isFavorite_derivedFromCurrentItem() = runTest(dispatcher) {
        val favored = p1.copy(isFavorite = true)
        val vm = ViewerViewModel(listOf(p2, favored), 0, RecordingDeleteRepository())

        assertFalse(vm.state.value.isFavorite)
        vm.setIndex(1)
        assertTrue(vm.state.value.isFavorite)
    }

    @Test
    fun toggleFavoriteCurrent_flipsOptimisticallyAndPersistsUpdatedItem() = runTest(dispatcher) {
        val repo = RecordingDeleteRepository()
        val vm = ViewerViewModel(listOf(p2, p1), 1, repo)
        advanceUntilIdle()

        vm.toggleFavoriteCurrentAndWait()
        advanceUntilIdle()

        assertTrue(vm.state.value.isFavorite)
        assertTrue(vm.state.value.items[1].isFavorite)
        assertEquals(listOf(p1.fileId to true), repo.favoriteCalls)
        assertEquals(1, repo.syncCount, "a successful favorite toggle must reconcile the local index")

        // Swiping away and back keeps the flip — it lives on the items list entry, not a side field.
        vm.setIndex(0)
        assertFalse(vm.state.value.isFavorite)
        vm.setIndex(1)
        assertTrue(vm.state.value.isFavorite)
    }

    @Test
    fun toggleFavoriteCurrent_onFailure_revertsAndEmitsError() = runTest(dispatcher) {
        val repo = RecordingDeleteRepository(favoriteResult = false)
        val vm = ViewerViewModel(listOf(p2, p1), 1, repo)
        val events = mutableListOf<ViewerEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleFavoriteCurrentAndWait()
        advanceUntilIdle()

        assertFalse(vm.state.value.isFavorite)
        assertFalse(vm.state.value.items[1].isFavorite)
        assertTrue(events.single() is ViewerEvent.Error)
        assertEquals(0, repo.syncCount, "a failed favorite toggle must not fire a background sync")
        collector.cancel()
    }

    @Test
    fun toggleFavoriteCurrent_onThrow_revertsAndEmitsError() = runTest(dispatcher) {
        val repo = RecordingDeleteRepository(favoriteThrows = true)
        val vm = ViewerViewModel(listOf(p2, p1), 1, repo)
        val events = mutableListOf<ViewerEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleFavoriteCurrentAndWait()
        advanceUntilIdle()

        assertFalse(vm.state.value.isFavorite)
        assertTrue(events.single() is ViewerEvent.Error)
        assertEquals(0, repo.syncCount, "a favorite toggle that throws must not fire a background sync")
        collector.cancel()
    }
}
