package id.homebase.photos.timeline

import id.homebase.photos.data.PhotosRepository
import id.homebase.photos.domain.PhotoItem
import kotlinx.coroutines.CancellationException
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
 * Refresh/pagination must never surface coroutine cancellation as a user error or drop
 * already-loaded content (iOS .refreshable cancels freely — QA bug 2026-07-05: a cancelled
 * pull-to-refresh wiped the grid and showed "StandaloneCoroutine was cancelled").
 */
class TimelineRefreshResilienceTest {

    /** Fake repo: serves [pages] on the newest page; sync/load throw whatever is scripted. */
    private class ScriptableRepository(private val pages: List<PhotoItem>) : PhotosRepository {
        var syncError: Throwable? = null
        var loadError: Throwable? = null

        override fun observePhotos(): Flow<List<PhotoItem>> = MutableStateFlow(pages).asStateFlow()

        override suspend fun loadPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> {
            loadError?.let { throw it }
            return if (beforeUserDate == null) pages.take(limit) else emptyList()
        }

        override suspend fun sync() {
            syncError?.let { throw it }
        }

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
    fun cancelledSync_keepsContent_emitsNoError_andRethrows() = runTest(dispatcher) {
        val repo = ScriptableRepository(listOf(item(2L), item(1L)))
        val vm = TimelineViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<TimelineEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle() // subscribe before acting — no-replay flow drops unobserved emissions

        repo.syncError = CancellationException("pull-to-refresh cancelled")
        val refresh = launch { vm.refreshAndWait() }
        advanceUntilIdle()

        assertTrue(refresh.isCancelled, "CancellationException must propagate, not be swallowed")
        assertEquals(2, vm.state.value.pagedItems.size, "content must survive a cancelled refresh")
        assertFalse(vm.state.value.isLoading, "a dead refresh must not leave isLoading stuck")
        assertTrue(events.isEmpty(), "cancellation is not a user-facing error")
        collector.cancel()
    }

    @Test
    fun cancelledLoad_keepsContent_emitsNoError_andRethrows() = runTest(dispatcher) {
        val repo = ScriptableRepository(listOf(item(2L), item(1L)))
        val vm = TimelineViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<TimelineEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle() // subscribe before acting — no-replay flow drops unobserved emissions

        repo.loadError = CancellationException("pull-to-refresh cancelled")
        val refresh = launch { vm.refreshAndWait() }
        advanceUntilIdle()

        assertTrue(refresh.isCancelled)
        assertEquals(2, vm.state.value.pagedItems.size)
        assertFalse(vm.state.value.isLoading)
        assertTrue(events.isEmpty())
        collector.cancel()
    }

    @Test
    fun failedReload_keepsExistingContent_andEmitsError() = runTest(dispatcher) {
        val repo = ScriptableRepository(listOf(item(2L), item(1L)))
        val vm = TimelineViewModel(repo)
        advanceUntilIdle()
        val events = mutableListOf<TimelineEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle() // subscribe before acting — no-replay flow drops unobserved emissions

        repo.loadError = RuntimeException("boom")
        vm.refreshAndWait()
        advanceUntilIdle()

        assertEquals(2, vm.state.value.pagedItems.size, "a failed reload must not wipe the grid")
        assertTrue(vm.state.value.sections.isNotEmpty())
        assertFalse(vm.state.value.isLoading)
        assertTrue(events.any { it is TimelineEvent.Error && it.message == "boom" })
        collector.cancel()
    }

    @Test
    fun failedPagination_resetsIsPaginating_andKeepsContent() = runTest(dispatcher) {
        // A full first page so loadMore() has a cursor and endReached is false.
        val fullPage = List(TimelineViewModel.PAGE_SIZE) { item((it + 1).toLong()) }
        val repo = ScriptableRepository(fullPage)
        val vm = TimelineViewModel(repo)
        advanceUntilIdle()

        repo.loadError = RuntimeException("boom")
        vm.loadMore()
        advanceUntilIdle()

        assertFalse(vm.state.value.isPaginating, "failed pagination must clear the footer spinner")
        assertEquals(TimelineViewModel.PAGE_SIZE, vm.state.value.pagedItems.size)
    }
}
