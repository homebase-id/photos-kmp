package id.homebase.photos.timeline

import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.photos.data.PhotosRepository
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.viewer.VideoHandle
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
import kotlin.uuid.Uuid

/**
 * Sync-completion reconcile: a [BackendEvent.DriveEvent.Stopped] round must refresh the newest
 * page in the VM — new server files appear even when the UI's refresh task died (iOS cancels
 * `.refreshable` tasks freely; QA 2026-07-05: rows landed in the index but the grid stayed stale
 * until relaunch).
 */
class TimelineSyncReloadTest {

    /** Fake repo whose newest page is swappable mid-test. */
    private class SwappablePageRepository(initial: List<PhotoItem>) : PhotosRepository {
        var pages: List<PhotoItem> = initial

        override fun observePhotos(): Flow<List<PhotoItem>> = MutableStateFlow(pages).asStateFlow()

        override suspend fun loadPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> =
            if (beforeUserDate == null) pages.take(limit) else emptyList()

        override suspend fun sync() {}

        override suspend fun deletePhotos(fileIds: List<Uuid>): Boolean = true

        override suspend fun loadThumbnailBytes(item: PhotoItem, maxDim: Int): ByteArray? = null
        override suspend fun loadOriginalBytes(item: PhotoItem): ByteArray? = null
        override suspend fun prepareVideo(item: PhotoItem): VideoHandle? = null
        override suspend fun disposeVideo(handle: VideoHandle) {}
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

    private val driveId = Uuid.random()
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stopped() = BackendEvent.DriveEvent.Stopped(
        driveId = driveId,
        totalCount = 1,
        result = BackendEvent.DriveResult.Completed,
    )

    @Test
    fun syncStopped_reloadsNewestPage() = runTest(dispatcher) {
        val old = item(1L)
        val repo = SwappablePageRepository(listOf(old))
        val bus = EventBus()
        val vm = TimelineViewModel(repo, bus)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.pagedItems.size)

        val fresh = item(2L)
        repo.pages = listOf(fresh, old)
        bus.emit(stopped())
        advanceUntilIdle()

        assertEquals(listOf(fresh, old), vm.state.value.pagedItems)
    }

    @Test
    fun syncStopped_withUnchangedPage_leavesStateUntouched() = runTest(dispatcher) {
        val repo = SwappablePageRepository(listOf(item(1L)))
        val bus = EventBus()
        val vm = TimelineViewModel(repo, bus)
        advanceUntilIdle()
        val before = vm.state.value

        bus.emit(stopped())
        advanceUntilIdle()

        // Same page content → no state churn (recompose-free no-op).
        assertEquals(before, vm.state.value)
    }

    @Test
    fun syncStopped_whenPaginatedDeep_skipsReload() = runTest(dispatcher) {
        // Two full pages loaded: the user has paginated past the newest page.
        val fullPage = List(TimelineViewModel.PAGE_SIZE) { item((1000 + it).toLong()) }
        val repo = object : PhotosRepository {
            var newest: List<PhotoItem> = fullPage
            override fun observePhotos(): Flow<List<PhotoItem>> = MutableStateFlow(newest).asStateFlow()
            override suspend fun loadPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> =
                if (beforeUserDate == null) newest.take(limit)
                else List(TimelineViewModel.PAGE_SIZE) { item((100 + it).toLong()) }
            override suspend fun sync() {}
            override suspend fun deletePhotos(fileIds: List<Uuid>): Boolean = true
            override suspend fun loadThumbnailBytes(item: PhotoItem, maxDim: Int): ByteArray? = null
            override suspend fun loadOriginalBytes(item: PhotoItem): ByteArray? = null
            override suspend fun prepareVideo(item: PhotoItem): VideoHandle? = null
            override suspend fun disposeVideo(handle: VideoHandle) {}
        }
        val bus = EventBus()
        val vm = TimelineViewModel(repo, bus)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        val deepCount = vm.state.value.pagedItems.size
        check(deepCount > TimelineViewModel.PAGE_SIZE)

        repo.newest = listOf(item(9999L)) + fullPage.drop(1)
        bus.emit(stopped())
        advanceUntilIdle()

        assertEquals(deepCount, vm.state.value.pagedItems.size, "deep pagination must not be clobbered by a live reload")
    }
}
