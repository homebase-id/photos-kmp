package id.homebase.photos.timeline

import id.homebase.photos.data.FavoritesPage
import id.homebase.photos.data.PhotoStatusResult
import id.homebase.photos.data.PhotosRepository
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.search.SearchCriteria
import id.homebase.photos.viewer.VideoHandle
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
 * Favorite/archive contract (Batch D): [TimelineViewModel.favoriteSelectedAndWait] flips
 * `isFavorite` in place and keeps items in the timeline; [TimelineViewModel.archiveSelectedAndWait]
 * mirrors [TimelineViewModel.deleteSelectedAndWait] — drops succeeded items, clears selection.
 */
class TimelineFavoriteArchiveTest {

    /** Fake repo: fixed newest page; records favorite/archive calls; failure knobs per test. */
    private class RecordingStatusRepository(
        private val pages: List<PhotoItem>,
        var favoriteResultFor: (Uuid) -> Boolean = { true },
        var setFavoriteThrows: Boolean = false,
        var archiveResult: PhotoStatusResult? = null, // null = succeed for every requested id
        var setArchivedThrows: Boolean = false,
    ) : PhotosRepository {
        val favoriteCalls = mutableListOf<Pair<Uuid, Boolean>>()
        val archiveCalls = mutableListOf<List<Uuid>>()
        var syncCount = 0
            private set

        override fun observePhotos(): Flow<List<PhotoItem>> = MutableStateFlow(pages).asStateFlow()

        override suspend fun loadPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> =
            if (beforeUserDate == null) pages.take(limit) else emptyList()

        override suspend fun sync() { syncCount++ }

        override suspend fun deletePhotos(fileIds: List<Uuid>): Boolean = true

        override suspend fun loadThumbnailBytes(item: PhotoItem, maxDim: Int): ByteArray? = null
        override suspend fun loadOriginalBytes(item: PhotoItem): ByteArray? = null
        override suspend fun prepareVideo(item: PhotoItem): VideoHandle? = null
        override suspend fun disposeVideo(handle: VideoHandle) {}

        override suspend fun setFavorite(fileId: Uuid, favorite: Boolean): Boolean {
            favoriteCalls += fileId to favorite
            if (setFavoriteThrows) throw IllegalStateException("favorite exploded")
            return favoriteResultFor(fileId)
        }

        override suspend fun setArchived(fileIds: List<Uuid>, archived: Boolean): PhotoStatusResult {
            archiveCalls += fileIds
            if (setArchivedThrows) throw IllegalStateException("archive exploded")
            return archiveResult ?: PhotoStatusResult(succeeded = fileIds)
        }

        override suspend fun softDelete(fileIds: List<Uuid>): PhotoStatusResult = PhotoStatusResult()
        override suspend fun restore(fileIds: List<Uuid>): PhotoStatusResult = PhotoStatusResult()
        override suspend fun permanentDelete(fileIds: List<Uuid>): Boolean = true
        override suspend fun loadFavoritesPage(cursor: String?, limit: Int): FavoritesPage =
            FavoritesPage(emptyList(), null)

        override suspend fun loadArchivedPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> = emptyList()
        override suspend fun loadTrashPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> = emptyList()
        override suspend fun search(criteria: SearchCriteria): List<PhotoItem> = emptyList()
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
    fun favoriteSelected_flipsIsFavoriteAndKeepsItemInTimeline() = runTest(dispatcher) {
        val repo = RecordingStatusRepository(listOf(p2, p1))
        val vm = TimelineViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<TimelineEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleSelection(p1)
        vm.favoriteSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(setOf(p2.fileId, p1.fileId), state.pagedItems.map { it.fileId }.toSet())
        assertTrue(state.pagedItems.first { it.fileId == p1.fileId }.isFavorite)
        assertFalse(state.pagedItems.first { it.fileId == p2.fileId }.isFavorite)
        assertTrue(state.sections.flatMap { it.items }.first { it.fileId == p1.fileId }.isFavorite)
        assertTrue(state.selectedIds.isEmpty())
        assertEquals(listOf(p1.fileId to true), repo.favoriteCalls)
        assertEquals(listOf<TimelineEvent>(TimelineEvent.Favorited(1)), events)
        assertEquals(1, repo.syncCount, "a successful favorite must reconcile the local index")
        collector.cancel()
    }

    @Test
    fun favoriteSelected_partialFailure_clearsSelectionAndEmitsError() = runTest(dispatcher) {
        val repo = RecordingStatusRepository(listOf(p2, p1), favoriteResultFor = { it != p1.fileId })
        val vm = TimelineViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<TimelineEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleSelection(p2)
        vm.toggleSelection(p1)
        vm.favoriteSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.pagedItems.first { it.fileId == p2.fileId }.isFavorite)
        assertFalse(state.pagedItems.first { it.fileId == p1.fileId }.isFavorite)
        assertTrue(state.selectedIds.isEmpty())
        assertEquals(TimelineEvent.Favorited(1), events.first())
        assertTrue(events.last() is TimelineEvent.Error)
        assertEquals(1, repo.syncCount, "a partially-successful favorite still reconciles the index")
        collector.cancel()
    }

    @Test
    fun favoriteSelected_onThrow_leavesStateUntouchedAndEmitsError() = runTest(dispatcher) {
        val repo = RecordingStatusRepository(listOf(p2, p1), setFavoriteThrows = true)
        val vm = TimelineViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<TimelineEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleSelection(p1)
        vm.favoriteSelectedAndWait()
        advanceUntilIdle()

        assertFalse(vm.state.value.pagedItems.first { it.fileId == p1.fileId }.isFavorite)
        assertTrue(events.single() is TimelineEvent.Error)
        assertEquals(0, repo.syncCount, "a failed favorite must not fire a background sync")
        collector.cancel()
    }

    @Test
    fun archiveSelected_dropsSucceededAndClearsSelection() = runTest(dispatcher) {
        val repo = RecordingStatusRepository(listOf(p2, p1))
        val vm = TimelineViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<TimelineEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleSelection(p1)
        vm.archiveSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p2), state.pagedItems)
        assertTrue(state.sections.flatMap { it.items }.none { it.fileId == p1.fileId })
        assertTrue(state.selectedIds.isEmpty())
        assertEquals(listOf(listOf(p1.fileId)), repo.archiveCalls)
        assertEquals(listOf<TimelineEvent>(TimelineEvent.Archived(1)), events)
        assertEquals(1, repo.syncCount, "a successful archive must reconcile the local index")
        collector.cancel()
    }

    @Test
    fun archiveSelected_partialFailure_keepsFailedItemButClearsSelectionAndEmitsError() = runTest(dispatcher) {
        val repo = RecordingStatusRepository(
            listOf(p2, p1),
            archiveResult = PhotoStatusResult(succeeded = emptyList(), failed = listOf(p1.fileId)),
        )
        val vm = TimelineViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<TimelineEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleSelection(p1)
        vm.archiveSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p2, p1), state.pagedItems)
        assertTrue(state.selectedIds.isEmpty())
        assertEquals(TimelineEvent.Archived(0), events.first())
        assertTrue(events.last() is TimelineEvent.Error)
        assertEquals(1, repo.syncCount, "a partially-successful archive still reconciles the index")
        collector.cancel()
    }

    @Test
    fun archiveSelected_onThrow_keepsSelectionAndEmitsError() = runTest(dispatcher) {
        val repo = RecordingStatusRepository(listOf(p2, p1), setArchivedThrows = true)
        val vm = TimelineViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<TimelineEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleSelection(p1)
        vm.archiveSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(p2, p1), state.pagedItems)
        assertTrue(state.isSelected(p1))
        assertTrue(events.single() is TimelineEvent.Error)
        assertEquals(0, repo.syncCount, "a failed archive must not fire a background sync")
        collector.cancel()
    }
}
