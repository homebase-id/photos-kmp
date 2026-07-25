package id.homebase.photos.albums

import id.homebase.photos.domain.AlbumItem
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Albums list contract (C3): names land in a first emission with null covers so the grid
 * paints immediately; covers resolve concurrently and land in a second emission. Mutations
 * (C-Batch) patch the grid optimistically and then reconcile with a sync + reload, because a
 * server-side album write doesn't reach the local index on its own.
 */
class AlbumsViewModelTest {

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
    fun coverPrefersCoverFileId_elseNewestPhoto() = runTest(dispatcher) {
        val newest = photo(2_000L)
        val pinnedPhoto = photo(1_000L)
        val pinned = album("Pinned cover", coverFileId = pinnedPhoto.fileId)
        val unpinned = album("Newest photo cover")
        val vm = AlbumsViewModel(
            FakeAlbumsRepository(
                albums = listOf(pinned, unpinned),
                photosByAlbum = mapOf(
                    pinned.albumId to listOf(newest, pinnedPhoto),
                    unpinned.albumId to listOf(newest, pinnedPhoto),
                ),
                localPhotos = mapOf(pinnedPhoto.fileId to pinnedPhoto),
            ),
        )
        advanceUntilIdle()

        val summaries = vm.state.value.albums
        assertEquals(pinnedPhoto, summaries[0].cover, "explicit coverFileId wins")
        assertEquals(newest, summaries[1].cover, "no coverFileId falls back to the newest photo")
    }

    @Test
    fun pinnedCoverThatIsNotSyncedYet_fallsBackToNewestPhoto() = runTest(dispatcher) {
        val newest = photo(2_000L)
        val pinned = album("Pinned", coverFileId = Uuid.random())
        val vm = AlbumsViewModel(
            FakeAlbumsRepository(
                albums = listOf(pinned),
                photosByAlbum = mapOf(pinned.albumId to listOf(newest)),
                localPhotos = emptyMap(), // the pinned cover isn't in the local index
            ),
        )
        advanceUntilIdle()

        assertEquals(newest, vm.state.value.albums.single().cover)
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

    // --- mutations -----------------------------------------------------------------------

    @Test
    fun createAlbum_insertsOptimistically_thenSyncsAndReloads() = runTest(dispatcher) {
        val repo = FakeAlbumsRepository(albums = listOf(album("Trip")))
        val vm = AlbumsViewModel(repo)
        val events = mutableListOf<AlbumsEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        val created = vm.createAlbumAndWait("Roadtrip")
        advanceUntilIdle()

        assertNotNull(created)
        assertEquals("Roadtrip", created.name)
        assertTrue(vm.state.value.albums.any { it.album.name == "Roadtrip" })
        assertFalse(vm.state.value.isMutating)
        assertEquals(listOf(AlbumsEvent.Created(created)), events)
        assertEquals(1, repo.syncCount, "the new album file only reaches the index via sync")
        collector.cancel()
    }

    @Test
    fun createAlbum_trimsTheName() = runTest(dispatcher) {
        val repo = FakeAlbumsRepository()
        val vm = AlbumsViewModel(repo)
        advanceUntilIdle()

        vm.createAlbumAndWait("  Roadtrip  ")
        advanceUntilIdle()

        assertEquals("Roadtrip", repo.albums.single().name)
    }

    @Test
    fun createAlbum_failure_emitsErrorAndLeavesTheGridAlone() = runTest(dispatcher) {
        val repo = FakeAlbumsRepository(albums = listOf(album("Trip")), writeThrows = true)
        val vm = AlbumsViewModel(repo)
        val events = mutableListOf<AlbumsEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        val created = vm.createAlbumAndWait("Roadtrip")
        advanceUntilIdle()

        assertNull(created)
        assertEquals(listOf("Trip"), vm.state.value.albums.map { it.album.name })
        assertFalse(vm.state.value.isMutating, "a failed write must not wedge later ones")
        assertTrue(events.single() is AlbumsEvent.Error)
        assertEquals(0, repo.syncCount)
        collector.cancel()
    }

    @Test
    fun rename_replacesTheAlbumInState() = runTest(dispatcher) {
        val trip = album("Trip")
        val repo = FakeAlbumsRepository(albums = listOf(trip))
        val vm = AlbumsViewModel(repo)
        advanceUntilIdle()

        val renamed = vm.renameAndWait(trip, "Roadtrip")
        advanceUntilIdle()

        assertEquals("Roadtrip", renamed?.name)
        assertEquals(trip.fileId, renamed?.fileId)
        assertEquals(trip.albumId, renamed?.albumId, "renaming must never touch the album tag")
        assertEquals(listOf("Roadtrip"), vm.state.value.albums.map { it.album.name })
    }

    @Test
    fun delete_dropsTheAlbumFromState() = runTest(dispatcher) {
        val trip = album("Trip")
        val hikes = album("Hikes")
        val repo = FakeAlbumsRepository(albums = listOf(trip, hikes))
        val vm = AlbumsViewModel(repo)
        val events = mutableListOf<AlbumsEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        assertTrue(vm.deleteAndWait(trip))
        advanceUntilIdle()

        assertEquals(listOf("Hikes"), vm.state.value.albums.map { it.album.name })
        assertEquals(listOf(AlbumsEvent.Deleted(trip)), events)
        collector.cancel()
    }

    @Test
    fun delete_serverRefusal_isReportedAndKeepsTheAlbum() = runTest(dispatcher) {
        val trip = album("Trip")
        val repo = FakeAlbumsRepository(albums = listOf(trip), deleteSucceeds = false)
        val vm = AlbumsViewModel(repo)
        val events = mutableListOf<AlbumsEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        assertFalse(vm.deleteAndWait(trip))
        advanceUntilIdle()

        assertEquals(listOf("Trip"), vm.state.value.albums.map { it.album.name })
        assertTrue(events.single() is AlbumsEvent.Error)
        collector.cancel()
    }

    @Test
    fun setCover_pinsTheCoverOnTheAlbum() = runTest(dispatcher) {
        val trip = album("Trip")
        val cover = photo(2_000L)
        val repo = FakeAlbumsRepository(
            albums = listOf(trip),
            photosByAlbum = mapOf(trip.albumId to listOf(cover)),
            localPhotos = mapOf(cover.fileId to cover),
        )
        val vm = AlbumsViewModel(repo)
        advanceUntilIdle()

        val updated = vm.setCoverAndWait(trip, cover.fileId)
        advanceUntilIdle()

        assertEquals(cover.fileId, updated?.coverFileId)
        assertEquals(cover.fileId, vm.state.value.albums.single().album.coverFileId)
    }

    @Test
    fun addToAlbum_reportsThePerFileSplit() = runTest(dispatcher) {
        val trip = album("Trip")
        val ok = Uuid.random()
        val broken = Uuid.random()
        val repo = FakeAlbumsRepository(albums = listOf(trip), failMembershipFor = setOf(broken))
        val vm = AlbumsViewModel(repo)
        val events = mutableListOf<AlbumsEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        val result = vm.addToAlbumAndWait(trip.albumId, listOf(ok, broken))
        advanceUntilIdle()

        assertEquals(listOf(ok), result?.succeeded)
        assertEquals(listOf(broken), result?.failed)
        assertFalse(result!!.isCompleteSuccess)
        assertEquals(listOf(trip.albumId to listOf(ok, broken)), repo.addCalls)
        assertEquals(listOf(AlbumsEvent.PhotosAdded(trip.albumId, added = 1, failed = 1)), events)
        collector.cancel()
    }

    @Test
    fun createAlbumWithPhotos_createsThenTagsIntoTheNewAlbum() = runTest(dispatcher) {
        val repo = FakeAlbumsRepository()
        val vm = AlbumsViewModel(repo)
        advanceUntilIdle()
        val ids = listOf(Uuid.random(), Uuid.random())

        val created = vm.createAlbumWithPhotosAndWait("Roadtrip", ids)
        advanceUntilIdle()

        assertNotNull(created)
        assertEquals(listOf(created.albumId to ids), repo.addCalls)
    }
}
