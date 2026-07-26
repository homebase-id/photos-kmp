package id.homebase.photos.search

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.photos.albums.FakeAlbumsRepository
import id.homebase.photos.domain.AlbumItem
import id.homebase.photos.domain.PhotoItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * SearchViewModel over a REAL (in-memory) RecentSearchesStore — same convention as
 * BackupViewModelTest for a VM backed by a KeyValue-table store — plus fake
 * Photos/Albums repositories so the criteria composition and album-name resolution are
 * asserted without any network/DB round-trip on the photo side.
 */
class SearchViewModelTest {

    private fun item(userDate: Long, isVideo: Boolean = false): PhotoItem = PhotoItem(
        fileId = Uuid.random(),
        uniqueId = Uuid.random(),
        userDate = userDate,
        isVideo = isVideo,
        pixelWidth = 900,
        pixelHeight = 1200,
        previewPlaceholder = null,
        driveId = Uuid.random(),
        payloadKey = "dflt_key",
    )

    private val dispatcher = StandardTestDispatcher()
    private lateinit var recentStore: RecentSearchesStore

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        try {
            runBlocking { DatabaseManager.initialize { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) } }
        } catch (_: IllegalStateException) {
        }
        recentStore = RecentSearchesStore(DatabaseManager.appDb.keyValue)
        runBlocking { recentStore.clear() }
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * The VM's fire-and-forget intents run on viewModelScope, but [RecentSearchesStore] hops to
     * the REAL DB dispatchers (background threads) — so a single advanceUntilIdle() on the test
     * scheduler can return before that off-thread write lands and re-enters viewModelScope.
     * Interleave scheduler advances with brief real waits, same fix as BackupViewModelTest's
     * awaitUiState. Also avoids leaving an orphaned coroutine to resume after resetMain() and
     * blow up some unrelated later test.
     */
    private fun TestScope.awaitState(
        vm: SearchViewModel,
        timeoutMs: Long = 5_000,
        predicate: (SearchUiState) -> Boolean,
    ) {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadlineNs) {
            advanceUntilIdle()
            if (predicate(vm.state.value)) return
            Thread.sleep(5)
        }
        advanceUntilIdle()
        check(predicate(vm.state.value)) {
            "timed out after ${timeoutMs}ms waiting for SearchUiState; last = ${vm.state.value}"
        }
    }

    /**
     * Every [SearchViewModel] construction fires an unconditional `init` load of recents — a real
     * DB round-trip even when a test doesn't care about it. Without settling it, that coroutine
     * can still be in flight when the test method returns; it then resumes on Main after this
     * test's tearDown() has already resetMain()'d, throwing "Module with the Main dispatcher is
     * missing" — attributed, confusingly, to whichever test happens to be running at that later
     * moment. A couple of real-time interleavings drain it before we move on.
     */
    private fun TestScope.settle() {
        advanceUntilIdle()
        Thread.sleep(20)
        advanceUntilIdle()
    }

    @Test
    fun init_loadsRecentsFromStore() = runTest(dispatcher) {
        // Direct suspend calls (not a nested runBlocking, not fire-and-forget) so the real DB
        // dispatcher hop is awaited properly by this coroutine — mirrors how BackupViewModelTest
        // seeds its store straight inside runTest.
        recentStore.push("beach")
        recentStore.push("lake")

        val vm = SearchViewModel(FakeSearchPhotosRepository(), FakeAlbumsRepository(), recentStore)
        awaitState(vm) { it.recent.isNotEmpty() }

        assertEquals(listOf("lake", "beach"), vm.state.value.recent)
    }

    @Test
    fun onQueryChange_isStateOnly_doesNotSearch() = runTest(dispatcher) {
        val repo = FakeSearchPhotosRepository()
        val vm = SearchViewModel(repo, FakeAlbumsRepository(), recentStore)
        settle()

        vm.onQueryChange("sunset")
        advanceUntilIdle()

        assertEquals("sunset", vm.state.value.query)
        assertTrue(repo.searchCalls.isEmpty(), "typing alone must not run a search")
        assertTrue(vm.state.value.sections.isEmpty())
    }

    @Test
    fun setDateRange_nonIdle_runsSearchWithDateCriteria() = runTest(dispatcher) {
        val results = listOf(item(2_000L))
        val repo = FakeSearchPhotosRepository(defaultResults = results)
        val vm = SearchViewModel(repo, FakeAlbumsRepository(), recentStore)
        settle()

        vm.setDateRange(1_000L, 3_000L)
        advanceUntilIdle()

        assertEquals(SearchCriteria(fromUserDate = 1_000L, toUserDate = 3_000L), repo.searchCalls.single())
        assertTrue(vm.state.value.hasSearched)
        assertEquals(results, vm.state.value.sections.flatMap { it.items })
    }

    @Test
    fun overlappingFilterChanges_cancelsStaleSearch_lastRequestWins() = runTest(dispatcher) {
        // First search parks on the gate (simulating a slow round-trip) BEFORE it ever reads a
        // result — a second filter change fires before it resolves. The stale search must be
        // cancelled, so it never reaches (and never consumes) resultsSequence; only the fresh
        // second call does.
        val gate = CompletableDeferred<Unit>()
        val freshItem = item(1L)
        val repo = FakeSearchPhotosRepository(
            resultsSequence = mutableListOf(listOf(freshItem)),
            firstCallGate = gate,
        )
        val vm = SearchViewModel(repo, FakeAlbumsRepository(), recentStore)
        settle()

        vm.setDateRange(1L, 100L) // search #1 launches, parks on the gate before returning
        advanceUntilIdle()
        assertEquals(1, repo.searchCalls.size)
        assertTrue(vm.state.value.isSearching, "first search still in flight")

        vm.setDateRange(200L, 300L) // cancels #1 before it resolves, launches #2 (no gate — call 2)
        advanceUntilIdle()

        assertEquals(2, repo.searchCalls.size)
        assertFalse(vm.state.value.isSearching)
        assertEquals(listOf(freshItem), vm.state.value.sections.flatMap { it.items }, "the later request wins")
        assertEquals(200L, vm.state.value.fromUserDate)
        assertEquals(300L, vm.state.value.toUserDate)

        gate.complete(Unit) // release the cancelled first call's continuation, if anything resumes it
        advanceUntilIdle()

        // The stale search must not clobber the fresh result once its (now-cancelled) coroutine
        // tries to resume.
        assertEquals(listOf(freshItem), vm.state.value.sections.flatMap { it.items })
        assertEquals(200L, vm.state.value.fromUserDate)
    }

    @Test
    fun setTypeFilter_nonIdle_runsSearchWithIsVideoCriteria() = runTest(dispatcher) {
        val repo = FakeSearchPhotosRepository()
        val vm = SearchViewModel(repo, FakeAlbumsRepository(), recentStore)
        settle()

        vm.setTypeFilter(TypeFilter.VIDEOS)
        advanceUntilIdle()

        assertEquals(SearchCriteria(isVideo = true), repo.searchCalls.single())
    }

    @Test
    fun setAlbumFilter_nonIdle_runsSearchWithAlbumIdCriteria() = runTest(dispatcher) {
        val album = AlbumItem(fileId = Uuid.random(), albumId = Uuid.random(), name = "Ski Trip", coverFileId = null)
        val albumPhoto = item(5_000L)
        val repo = FakeSearchPhotosRepository(resultsByAlbum = mapOf(album.albumId to listOf(albumPhoto)))
        val vm = SearchViewModel(repo, FakeAlbumsRepository(albums = listOf(album)), recentStore)
        settle()

        vm.setAlbumFilter(album)
        advanceUntilIdle()

        assertEquals(listOf(album.albumId), repo.searchCalls.single().albumIds)
        assertEquals(listOf(albumPhoto), vm.state.value.sections.flatMap { it.items })
    }

    @Test
    fun submitAndWait_withAlbumNameMatchingQuery_resolvesToAlbumIdsAndPushesRecent() = runTest(dispatcher) {
        val album = AlbumItem(fileId = Uuid.random(), albumId = Uuid.random(), name = "Summer Trip", coverFileId = null)
        val repo = FakeSearchPhotosRepository(resultsByAlbum = mapOf(album.albumId to listOf(item(1L))))
        val vm = SearchViewModel(repo, FakeAlbumsRepository(albums = listOf(album)), recentStore)
        settle()

        vm.onQueryChange("summer")
        vm.submitAndWait()
        advanceUntilIdle()

        assertEquals(listOf(album.albumId), repo.searchCalls.single().albumIds)
        assertEquals(listOf("summer"), vm.state.value.recent)
        assertEquals(listOf("summer"), recentStore.load())
    }

    @Test
    fun submitAndWait_blankQueryNoFilters_resetsToIdleAndClearsSections() = runTest(dispatcher) {
        val repo = FakeSearchPhotosRepository(defaultResults = listOf(item(1L)))
        val vm = SearchViewModel(repo, FakeAlbumsRepository(), recentStore)
        settle()

        vm.setDateRange(1L, 2L) // non-idle: search runs, sections populate
        advanceUntilIdle()
        assertTrue(vm.state.value.sections.isNotEmpty())

        vm.setDateRange(null, null) // back to idle
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.isIdle)
        assertTrue(state.sections.isEmpty())
        assertTrue(!state.hasSearched)
    }

    @Test
    fun submitAndWait_repositoryThrows_setsErrorAndClearsSearching() = runTest(dispatcher) {
        val repo = FakeSearchPhotosRepository(searchThrows = true)
        val vm = SearchViewModel(repo, FakeAlbumsRepository(), recentStore)
        settle()

        vm.onQueryChange("oops")
        vm.submitAndWait()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.error != null)
        assertTrue(!state.isSearching)
        assertTrue(state.hasSearched)
    }

    @Test
    fun clearFilters_resetsChipsAndIdles() = runTest(dispatcher) {
        val album = AlbumItem(fileId = Uuid.random(), albumId = Uuid.random(), name = "Ski Trip", coverFileId = null)
        val repo = FakeSearchPhotosRepository(resultsByAlbum = mapOf(album.albumId to listOf(item(1L))))
        val vm = SearchViewModel(repo, FakeAlbumsRepository(albums = listOf(album)), recentStore)
        settle()

        vm.setAlbumFilter(album)
        vm.setTypeFilter(TypeFilter.VIDEOS)
        advanceUntilIdle()

        vm.clearFilters()
        advanceUntilIdle()

        val state = vm.state.value
        assertNull(state.albumFilter)
        assertEquals(TypeFilter.ALL, state.typeFilter)
        assertNull(state.fromUserDate)
        assertNull(state.toUserDate)
        assertTrue(state.isIdle)
        assertTrue(state.sections.isEmpty())
    }

    @Test
    fun clearRecent_clearsStoreAndState() = runTest(dispatcher) {
        recentStore.push("camera")

        val vm = SearchViewModel(FakeSearchPhotosRepository(), FakeAlbumsRepository(), recentStore)
        awaitState(vm) { it.recent.isNotEmpty() }
        assertEquals(listOf("camera"), vm.state.value.recent)

        vm.clearRecent()
        awaitState(vm) { it.recent.isEmpty() }

        assertTrue(vm.state.value.recent.isEmpty())
        assertTrue(recentStore.load().isEmpty())
    }
}
