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
 * Albums list contract (C3): names land in a first emission with null covers so the grid
 * paints immediately; covers resolve concurrently and land in a second emission.
 */
class AlbumsViewModelTest {

    /** Fake repo: fixed albums + per-album photos; failure/gating knobs per test. */
    private class FakeAlbumsRepository(
        private val albums: List<AlbumItem> = emptyList(),
        private val photosByAlbum: Map<Uuid, List<PhotoItem>> = emptyMap(),
        var loadAlbumsThrows: Boolean = false,
        val photosGate: CompletableDeferred<Unit>? = null,
    ) : AlbumsRepository {
        override suspend fun loadAlbums(): List<AlbumItem> {
            if (loadAlbumsThrows) throw IllegalStateException("albums exploded")
            return albums
        }

        override suspend fun loadAlbumPhotos(albumId: Uuid): List<PhotoItem> {
            photosGate?.await()
            return photosByAlbum[albumId] ?: emptyList()
        }
    }

    private fun album(name: String, coverFileId: Uuid? = null): AlbumItem = AlbumItem(
        fileId = Uuid.random(),
        albumId = Uuid.random(),
        name = name,
        coverFileId = coverFileId,
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
    fun loadsAlbums_thenResolvesCovers() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val trip = album("Trip")
        val hikes = album("Hikes")
        val tripPhoto = photo(2_000L)
        val hikesPhoto = photo(1_000L)
        val vm = AlbumsViewModel(
            FakeAlbumsRepository(
                albums = listOf(trip, hikes),
                photosByAlbum = mapOf(
                    trip.albumId to listOf(tripPhoto),
                    hikes.albumId to listOf(hikesPhoto),
                ),
                photosGate = gate,
            ),
        )
        advanceUntilIdle() // albums loaded; cover resolution parked on the gate

        val namesFirst = vm.state.value
        assertFalse(namesFirst.isLoading)
        assertEquals(listOf("Trip", "Hikes"), namesFirst.albums.map { it.album.name })
        assertTrue(namesFirst.albums.all { it.cover == null })

        gate.complete(Unit)
        advanceUntilIdle()

        val withCovers = vm.state.value
        assertEquals(tripPhoto, withCovers.albums[0].cover)
        assertEquals(hikesPhoto, withCovers.albums[1].cover)
    }

    @Test
    fun coverPrefersCoverFileId_elseFirstPhoto() = runTest(dispatcher) {
        val first = photo(2_000L)
        val second = photo(1_000L)
        val pinned = album("Pinned cover", coverFileId = second.fileId)
        val unpinned = album("First photo cover")
        val vm = AlbumsViewModel(
            FakeAlbumsRepository(
                albums = listOf(pinned, unpinned),
                photosByAlbum = mapOf(
                    pinned.albumId to listOf(first, second),
                    unpinned.albumId to listOf(first, second),
                ),
            ),
        )
        advanceUntilIdle()

        val summaries = vm.state.value.albums
        assertEquals(second, summaries[0].cover, "explicit coverFileId wins")
        assertEquals(first, summaries[1].cover, "no coverFileId falls back to the newest photo")
    }

    @Test
    fun repositoryFailure_setsError() = runTest(dispatcher) {
        val vm = AlbumsViewModel(FakeAlbumsRepository(loadAlbumsThrows = true))
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertNotNull(state.error)
        assertTrue(state.albums.isEmpty())
    }
}
