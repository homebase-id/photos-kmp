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
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Album detail contract (C3): title paints from the album name before the photo load lands;
 * photos group into month sections exactly like the timeline; selection mirrors the timeline's
 * (dashed-Uuid string keys, "any id selected" == selection mode) and drives remove-from-album,
 * which untags the photos without deleting them.
 */
class AlbumDetailViewModelTest {

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

    private fun repoWith(album: AlbumItem, photos: List<PhotoItem>) =
        FakeAlbumsRepository(albums = listOf(album), photosByAlbum = mapOf(album.albumId to photos))

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
        val roadtrip = album("Roadtrip")
        val photos = listOf(photo(jun28_2026), photo(jun14_2026), photo(may05_2026))
        val vm = AlbumDetailViewModel(roadtrip, repoWith(roadtrip, photos))
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

    // --- selection (parity with the timeline) ---------------------------------------------

    @Test
    fun toggleSelection_entersSelectionMode_andKeysAreDashedUuids() = runTest(dispatcher) {
        val roadtrip = album("Roadtrip")
        val a = photo(jun28_2026)
        val b = photo(jun14_2026)
        val vm = AlbumDetailViewModel(roadtrip, repoWith(roadtrip, listOf(a, b)))
        advanceUntilIdle()

        vm.toggleSelection(a)

        val state = vm.state.value
        assertTrue(state.inSelectionMode)
        assertTrue(state.isSelected(a))
        assertFalse(state.isSelected(b))
        assertEquals(setOf(a.fileId.toString()), state.selectedIds)
        assertTrue(state.selectedIds.single().contains("-"), "keys must be dashed, never bare hex")
        assertEquals(listOf(a), state.selectedPhotos)
    }

    @Test
    fun toggleSelection_samePhotoTwice_exitsSelectionMode() = runTest(dispatcher) {
        val roadtrip = album("Roadtrip")
        val a = photo(jun28_2026)
        val vm = AlbumDetailViewModel(roadtrip, repoWith(roadtrip, listOf(a)))
        advanceUntilIdle()

        vm.toggleSelection(a)
        vm.toggleSelection(a)

        assertFalse(vm.state.value.inSelectionMode)
    }

    @Test
    fun clearSelection_emptiesSelectedIds() = runTest(dispatcher) {
        val roadtrip = album("Roadtrip")
        val a = photo(jun28_2026)
        val b = photo(jun14_2026)
        val vm = AlbumDetailViewModel(roadtrip, repoWith(roadtrip, listOf(a, b)))
        advanceUntilIdle()

        vm.toggleSelection(a)
        vm.toggleSelection(b)
        vm.clearSelection()

        assertTrue(vm.state.value.selectedIds.isEmpty())
        assertFalse(vm.state.value.inSelectionMode)
    }

    // --- remove from album -----------------------------------------------------------------

    @Test
    fun removeSelected_untagsThePhotosAndPrunesTheGrid() = runTest(dispatcher) {
        val roadtrip = album("Roadtrip")
        val a = photo(jun28_2026)
        val b = photo(jun14_2026)
        val repo = repoWith(roadtrip, listOf(a, b))
        val vm = AlbumDetailViewModel(roadtrip, repo)
        val events = mutableListOf<AlbumDetailEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleSelection(a)
        vm.removeSelectedAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(b), state.photos)
        assertEquals(listOf("June 2026"), state.sections.map { it.title })
        assertTrue(state.selectedIds.isEmpty())
        assertFalse(state.isRemoving)
        // Untag only — the photo file itself is never deleted.
        assertEquals(listOf(roadtrip.albumId to listOf(a.fileId)), repo.removeCalls)
        assertEquals(listOf<AlbumDetailEvent>(AlbumDetailEvent.Removed(1)), events)
        collector.cancel()
    }

    @Test
    fun removeSelected_partialFailure_keepsTheOnesThatDidNotLand() = runTest(dispatcher) {
        val roadtrip = album("Roadtrip")
        val a = photo(jun28_2026)
        val b = photo(jun14_2026)
        val repo = repoWith(roadtrip, listOf(a, b))
        repo.failMembershipFor = setOf(b.fileId)
        val vm = AlbumDetailViewModel(roadtrip, repo)
        val events = mutableListOf<AlbumDetailEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleSelection(a)
        vm.toggleSelection(b)
        vm.removeSelectedAndWait()
        advanceUntilIdle()

        assertEquals(listOf(b), vm.state.value.photos, "only the failed one stays in the album")
        assertEquals(listOf(AlbumDetailEvent.Removed(1)), events.filterIsInstance<AlbumDetailEvent.Removed>())
        assertTrue(events.any { it is AlbumDetailEvent.Error })
        collector.cancel()
    }

    @Test
    fun removeSelected_withNothingSelected_isANoOp() = runTest(dispatcher) {
        val roadtrip = album("Roadtrip")
        val repo = repoWith(roadtrip, listOf(photo(jun28_2026)))
        val vm = AlbumDetailViewModel(roadtrip, repo)
        advanceUntilIdle()

        vm.removeSelectedAndWait()
        advanceUntilIdle()

        assertTrue(repo.removeCalls.isEmpty())
    }

    @Test
    fun removeSelected_failure_emitsErrorAndKeepsTheGrid() = runTest(dispatcher) {
        val roadtrip = album("Roadtrip")
        val a = photo(jun28_2026)
        val repo = repoWith(roadtrip, listOf(a))
        val vm = AlbumDetailViewModel(roadtrip, repo)
        val events = mutableListOf<AlbumDetailEvent>()
        val collector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.toggleSelection(a)
        repo.writeThrows = true
        vm.removeSelectedAndWait()
        advanceUntilIdle()

        assertEquals(listOf(a), vm.state.value.photos)
        assertFalse(vm.state.value.isRemoving, "a failed remove must not wedge later ones")
        assertTrue(events.single() is AlbumDetailEvent.Error)
        collector.cancel()
    }
}
