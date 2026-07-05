package id.homebase.photos.albums

import id.homebase.photos.data.AlbumsRepository
import id.homebase.photos.domain.AlbumItem
import id.homebase.photos.domain.PhotoItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Album detail contract (C3): title paints from the album name before the photo load
 * lands; photos group into month sections exactly like the timeline.
 */
class AlbumDetailViewModelTest {

    /** Fake repo: fixed photos for one album; failure/gating knobs per test. */
    private class FakeAlbumsRepository(
        private val photos: List<PhotoItem> = emptyList(),
        var loadPhotosThrows: Boolean = false,
        val photosGate: CompletableDeferred<Unit>? = null,
    ) : AlbumsRepository {
        override suspend fun loadAlbums(): List<AlbumItem> = emptyList()

        override suspend fun loadAlbumPhotos(albumId: Uuid): List<PhotoItem> {
            photosGate?.await()
            if (loadPhotosThrows) throw IllegalStateException("photos exploded")
            return photos
        }
    }

    private fun album(name: String): AlbumItem = AlbumItem(
        fileId = Uuid.random(),
        albumId = Uuid.random(),
        name = name,
        coverFileId = null,
    )

    private fun photo(userDate: Long): PhotoItem = PhotoItem(
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

    // Reference epoch-millis (UTC noon) on known days — same anchors as TimelineGroupingTest.
    private val jun28_2026 = 1_782_648_000_000L // 2026-06-28T12:00Z
    private val jun14_2026 = 1_781_438_400_000L // 2026-06-14T12:00Z
    private val may05_2026 = 1_777_982_400_000L // 2026-05-05T12:00Z

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
    fun titleSeededFromAlbumName_beforeLoadCompletes() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = AlbumDetailViewModel(album("Roadtrip"), FakeAlbumsRepository(photosGate = gate))

        assertEquals("Roadtrip", vm.state.value.title)
        assertTrue(vm.state.value.isLoading)

        gate.complete(Unit) // unpark so no coroutine outlives the test
        advanceUntilIdle()
    }

    @Test
    fun loadGroupsPhotosIntoMonthSections() = runTest(dispatcher) {
        val photos = listOf(photo(jun28_2026), photo(jun14_2026), photo(may05_2026))
        val vm = AlbumDetailViewModel(album("Roadtrip"), FakeAlbumsRepository(photos = photos))
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertEquals(photos, state.photos)
        assertEquals(listOf("June 2026", "May 2026"), state.sections.map { it.title })
        assertEquals(listOf(2, 1), state.sections.map { it.items.size })
    }

    @Test
    fun failure_setsError() = runTest(dispatcher) {
        val vm = AlbumDetailViewModel(album("Roadtrip"), FakeAlbumsRepository(loadPhotosThrows = true))
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertNotNull(state.error)
        assertTrue(state.photos.isEmpty())
    }
}
