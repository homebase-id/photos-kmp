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
import kotlin.uuid.Uuid

/**
 * First-launch auto-sync contract (plan 009 §5): on an EMPTY first page the VM kicks exactly
 * one awaited [PhotosRepository.sync]; a non-empty first page kicks none. Counts sync calls via
 * a hand-rolled fake so the mock repository stays a pure fixture.
 */
class TimelineFirstLaunchSyncTest {

    /** Fake repo: serves [pages] on the newest page (null cursor), empty otherwise; counts sync(). */
    private class CountingSyncRepository(private val pages: List<PhotoItem>) : PhotosRepository {
        var syncCalls = 0
            private set

        override fun observePhotos(): Flow<List<PhotoItem>> = MutableStateFlow(pages).asStateFlow()

        override suspend fun loadPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> =
            if (beforeUserDate == null) pages.take(limit) else emptyList()

        override suspend fun sync() {
            syncCalls++
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
    fun emptyFirstPageTriggersExactlyOneSync() = runTest(dispatcher) {
        val repo = CountingSyncRepository(pages = emptyList())
        TimelineViewModel(repo)
        advanceUntilIdle()

        assertEquals(1, repo.syncCalls, "empty first page must trigger exactly one awaited sync")
    }

    @Test
    fun nonEmptyFirstPageDoesNotSync() = runTest(dispatcher) {
        val repo = CountingSyncRepository(pages = listOf(item(1_700_000_000_000L)))
        TimelineViewModel(repo)
        advanceUntilIdle()

        assertEquals(0, repo.syncCalls, "non-empty first page must not trigger a sync")
    }
}
