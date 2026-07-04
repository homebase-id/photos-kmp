package id.homebase.photos.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * BackupViewModel folder-picker flow over a REAL manager + REAL (in-memory) selection store. Proves
 * loadFolders() overlays the persisted selection onto the folder rows and onFolderToggled() both
 * persists (shared-side) and reflects into the exposed UI state. viewModelScope is driven by the
 * test dispatcher (setMain).
 */
class BackupViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var ledger: BackupLedger
    private lateinit var selectionStore: BackupFolderSelectionStore
    private lateinit var enabledStore: BackupEnabledStore
    private lateinit var builder: PhotoFileBuilder

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        try {
            runBlocking { DatabaseManager.initialize { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) } }
        } catch (_: IllegalStateException) {
        }
        ledger = BackupLedger(DatabaseManager.appDb.keyValue)
        selectionStore = BackupFolderSelectionStore(DatabaseManager.appDb.keyValue)
        enabledStore = BackupEnabledStore(DatabaseManager.appDb.keyValue)
        runBlocking { selectionStore.setSelected(emptySet()) }
        builder = PhotoFileBuilder(
            fileOps = JvmFileOperationsProvider(),
            driveId = Uuid.parse("6483b7b1-f71b-d43e-b689-6c86148668cc"),
            zoneProvider = { TimeZone.UTC },
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeFoldersCrawler(val list: List<LibraryFolder>) : PhotoLibraryCrawler {
        override suspend fun folders() = list
        override suspend fun assets(folderIds: Set<String>): List<LibraryAsset> = emptyList()
        override suspend fun readBytes(asset: LibraryAsset): ByteArray? = null
    }

    private val noopUploader = object : PhotoUploadEnqueuer {
        override suspend fun enqueue(request: UploadFileRequest): Boolean = true
    }

    private val sampleFolders = listOf(
        LibraryFolder("A", "Camera", 2),
        LibraryFolder("B", "Screenshots", 1),
    )

    /**
     * The ViewModel fires its work on viewModelScope, but the KeyValue store hops to the REAL DB
     * dispatchers (DatabaseManager.dispatcher/readDispatcher on background threads) — so a single
     * advanceUntilIdle() on the test scheduler can return before that off-thread write lands and
     * re-enters viewModelScope. Interleave scheduler advances with brief real waits until the
     * observable UI state holds (or fail on timeout). Pure synchronization barrier — the caller's
     * assertions are unchanged.
     */
    private fun TestScope.awaitUiState(
        vm: BackupViewModel,
        timeoutMs: Long = 5_000,
        predicate: (BackupUiState) -> Boolean,
    ) {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadlineNs) {
            advanceUntilIdle()
            if (predicate(vm.state.value)) return
            Thread.sleep(5)
        }
        advanceUntilIdle()
        check(predicate(vm.state.value)) {
            "timed out after ${timeoutMs}ms waiting for BackupUiState; last = ${vm.state.value}"
        }
    }

    @Test
    fun loadFolders_reflectsFoldersWithPersistedSelection() = runTest(dispatcher) {
        selectionStore.setSelected(setOf("A"))
        val manager = BackupManager(
            FakeFoldersCrawler(sampleFolders), ledger, builder, noopUploader, selectionStore, enabledStore, backgroundScope,
        )
        val vm = BackupViewModel(manager)
        advanceUntilIdle()

        vm.loadFolders()
        awaitUiState(vm) { it.folders.size == 2 }

        val folders = vm.state.value.folders
        assertEquals(2, folders.size)
        val a = folders.first { it.folderId == "A" }
        assertEquals("Camera", a.name)
        assertEquals(2, a.photoCount)
        assertTrue(a.selected, "the persisted selection marks folder A checked")
        assertFalse(folders.first { it.folderId == "B" }.selected)
    }

    @Test
    fun onFolderToggled_persistsAndReflectsInUiState() = runTest(dispatcher) {
        val manager = BackupManager(
            FakeFoldersCrawler(sampleFolders), ledger, builder, noopUploader, selectionStore, enabledStore, backgroundScope,
        )
        val vm = BackupViewModel(manager)
        advanceUntilIdle()
        vm.loadFolders()
        awaitUiState(vm) { it.folders.size == 2 }

        vm.onFolderToggled("B")
        awaitUiState(vm) { it.folders.firstOrNull { f -> f.folderId == "B" }?.selected == true }

        assertEquals(setOf("B"), selectionStore.selected(), "toggle persists to the store")
        assertTrue(vm.state.value.folders.first { it.folderId == "B" }.selected)
        assertEquals(1, vm.state.value.selectedFolderCount)

        vm.onFolderToggled("B")
        awaitUiState(vm) { it.folders.firstOrNull { f -> f.folderId == "B" }?.selected == false }

        assertEquals(emptySet(), selectionStore.selected())
        assertFalse(vm.state.value.folders.first { it.folderId == "B" }.selected)
        assertEquals(0, vm.state.value.selectedFolderCount)
    }
}
