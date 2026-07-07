package id.homebase.photos.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.image.ImageTestHelper
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
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
 * BackupManager state machine with a fake crawler + fake uploader over the REAL builder, REAL
 * (in-memory) ledger, and REAL (in-memory) folder-selection store. Covers folder-selective backup
 * (D6): empty selection uploads nothing, a non-empty selection crawls only those folders. Plus
 * dedup-skip, progress counts, cooperative toggle-off mid-run, and per-item failure isolation.
 * Unique assetId prefixes + a per-test selection reset keep the JVM-wide DB from cross-contaminating.
 */
class BackupManagerTest {

    private lateinit var ledger: BackupLedger
    private lateinit var selectionStore: BackupFolderSelectionStore
    private lateinit var enabledStore: BackupEnabledStore
    private lateinit var builder: PhotoFileBuilder
    private val image: ByteArray by lazy { ImageTestHelper.loadImage("dice.png") }

    // A REAL (off-test-scheduler) scope for the fire-and-forget persist in setEnabled(): the write
    // hops to the real DB dispatcher, which the test scheduler can't drive to completion, so
    // backgroundScope-launched writes never land. A real scope runs them on real threads immediately.
    private val persistScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() {
        persistScope.cancel()
    }

    @BeforeTest
    fun setUp() {
        try {
            runBlocking { DatabaseManager.initialize { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) } }
        } catch (_: IllegalStateException) {
        }
        ledger = BackupLedger(DatabaseManager.appDb.keyValue)
        selectionStore = BackupFolderSelectionStore(DatabaseManager.appDb.keyValue)
        enabledStore = BackupEnabledStore(DatabaseManager.appDb.keyValue)
        // Both stores key off a single fixed key shared across the JVM-wide DB — reset them so a prior
        // test's selection / enabled flag can't leak into this one.
        runBlocking {
            selectionStore.setSelected(emptySet())
            enabledStore.setEnabled(false)
        }
        builder = PhotoFileBuilder(
            fileOps = JvmFileOperationsProvider(),
            driveId = Uuid.parse("6483b7b1-f71b-d43e-b689-6c86148668cc"),
            zoneProvider = { TimeZone.UTC },
        )
    }

    private fun assets(prefix: String, n: Int) = (1..n).map {
        LibraryAsset(
            deviceAssetId = "$prefix-$it",
            fileName = "$prefix-$it.png",
            mimeType = "image/png",
            takenAtMillis = 1_000_000L + it,
            addedAtMillis = null,
            sizeBytes = image.size.toLong(),
        )
    }

    /** All assets under one folder "f"; assets(folderIds) returns them iff "f" is selected. */
    private inner class FakeCrawler(
        val byFolder: Map<String, List<LibraryAsset>>,
        val folderList: List<LibraryFolder> = emptyList(),
        val bytesFor: (LibraryAsset) -> ByteArray? = { image },
        val posterFor: (LibraryAsset) -> ByteArray? = { null },
    ) : PhotoLibraryCrawler {
        var readCount = 0
        var assetsCallCount = 0
        var lastFolderIds: Set<String>? = null
        override suspend fun folders() = folderList
        override suspend fun assets(folderIds: Set<String>): List<LibraryAsset> {
            assetsCallCount++
            lastFolderIds = folderIds
            return folderIds.flatMap { byFolder[it].orEmpty() }
        }
        override suspend fun readBytes(asset: LibraryAsset): ByteArray? {
            readCount++
            return bytesFor(asset)
        }
        override suspend fun readPosterFrame(asset: LibraryAsset): ByteArray? = posterFor(asset)
    }

    private fun singleFolder(
        list: List<LibraryAsset>,
        folderId: String = "f",
        bytesFor: (LibraryAsset) -> ByteArray? = { image },
    ) = FakeCrawler(byFolder = mapOf(folderId to list), bytesFor = bytesFor)

    private class FakeUploader(
        var behavior: suspend (UploadFileRequest) -> Boolean = { true },
    ) : PhotoUploadEnqueuer {
        val requests = mutableListOf<UploadFileRequest>()
        override suspend fun enqueue(request: UploadFileRequest): Boolean {
            requests.add(request)
            return behavior(request)
        }
    }

    @Test
    fun emptySelection_completesImmediatelyWithoutCrawlingAssets() = runTest {
        // No folders selected (the D6 default) — nothing must upload and no asset crawl runs.
        val crawler = singleFolder(assets("mgr-empty", 3))
        val uploader = FakeUploader()
        val manager = BackupManager(crawler, ledger, builder, uploader, selectionStore, enabledStore, backgroundScope, now = { 7L })

        manager.backupNow()

        val s = manager.state.value
        assertEquals(0, s.total)
        assertEquals(0, s.done)
        assertEquals(0, s.selectedFolderCount)
        assertFalse(s.running)
        assertEquals(7L, s.lastCompletedAt, "an empty pass still stamps a completion time")
        assertEquals(0, crawler.assetsCallCount, "assets() must never be crawled when nothing is selected")
        assertEquals(0, crawler.readCount)
        assertTrue(uploader.requests.isEmpty())
    }

    @Test
    fun selectedSubset_enqueuesOnlyThoseFoldersAssets() = runTest {
        val a = assets("mgr-sub-A", 2)
        val b = assets("mgr-sub-B", 3)
        val crawler = FakeCrawler(byFolder = mapOf("A" to a, "B" to b))
        val uploader = FakeUploader()
        selectionStore.setSelected(setOf("A")) // only folder A selected

        val manager = BackupManager(crawler, ledger, builder, uploader, selectionStore, enabledStore, backgroundScope)
        manager.backupNow()

        val s = manager.state.value
        assertEquals(setOf("A"), crawler.lastFolderIds, "only the selected folder is crawled")
        assertEquals(1, s.selectedFolderCount)
        assertEquals(2, s.total, "only folder A's 2 assets are in scope")
        assertEquals(2, s.done)
        assertEquals(2, uploader.requests.size)
        // A's assets are recorded; B's are never touched.
        assertNotNull(ledger.backedUpFileId("mgr-sub-A-1"))
        assertNull(ledger.backedUpFileId("mgr-sub-B-1"), "an unselected folder's assets never back up")
    }

    @Test
    fun toggleFolder_persistsSelectionAndReflectsCount() = runTest {
        val manager = BackupManager(singleFolder(emptyList()), ledger, builder, FakeUploader(), selectionStore, enabledStore, backgroundScope)

        assertEquals(setOf("cam"), manager.toggleFolder("cam"))
        assertEquals(setOf("cam"), selectionStore.selected(), "toggle persists to the store")
        assertEquals(1, manager.state.value.selectedFolderCount)

        assertEquals(emptySet(), manager.toggleFolder("cam"))
        assertEquals(emptySet(), selectionStore.selected())
        assertEquals(0, manager.state.value.selectedFolderCount)
    }

    @Test
    fun progress_countsEveryAssetAndEnqueuesEach() = runTest {
        val crawler = singleFolder(assets("mgr-progress", 3))
        val uploader = FakeUploader()
        selectionStore.setSelected(setOf("f"))
        val manager = BackupManager(crawler, ledger, builder, uploader, selectionStore, enabledStore, backgroundScope, now = { 42L })

        manager.backupNow()

        val s = manager.state.value
        assertEquals(3, s.total)
        assertEquals(3, s.done)
        assertFalse(s.running)
        assertEquals(3, uploader.requests.size)
        assertEquals(42L, s.lastCompletedAt)
    }

    @Test
    fun dedup_skipsAlreadyBackedUpWithoutReadingOrUploading() = runTest {
        // Pre-seed the ledger for #2 and #4 — those must be skipped (no read, no enqueue).
        ledger.record("mgr-dedup-2", Uuid.parse("00000000-0000-0000-0000-000000000002"))
        ledger.record("mgr-dedup-4", Uuid.parse("00000000-0000-0000-0000-000000000004"))

        val crawler = singleFolder(assets("mgr-dedup", 5))
        val uploader = FakeUploader()
        selectionStore.setSelected(setOf("f"))
        val manager = BackupManager(crawler, ledger, builder, uploader, selectionStore, enabledStore, backgroundScope)

        manager.backupNow()

        assertEquals(5, manager.state.value.total)
        assertEquals(5, manager.state.value.done, "skips still count toward done")
        assertEquals(3, uploader.requests.size, "only the 3 un-backed-up assets upload")
        assertEquals(3, crawler.readCount, "skipped assets are never read")
        // The 3 fresh ones are now recorded.
        assertNotNull(ledger.backedUpFileId("mgr-dedup-1"))
        assertNotNull(ledger.backedUpFileId("mgr-dedup-3"))
        assertNotNull(ledger.backedUpFileId("mgr-dedup-5"))
    }

    @Test
    fun video_buildsThumbnailsFromPosterAndPayloadFromVideoBytes() = runTest {
        val videoBytes = "not-an-image-just-raw-video-bytes".toByteArray() // not decodable as an image
        val clip = LibraryAsset(
            deviceAssetId = "vid:9", fileName = "clip.mp4", mimeType = "video/mp4",
            takenAtMillis = 2_000_000L, addedAtMillis = null, sizeBytes = videoBytes.size.toLong(),
        )
        // Poster is a real decodable image (dice.png) so the thumbnail pipeline runs on it, not the video.
        val crawler = FakeCrawler(mapOf("f" to listOf(clip)), bytesFor = { videoBytes }, posterFor = { image })
        val uploader = FakeUploader()
        selectionStore.setSelected(setOf("f"))
        val manager = BackupManager(crawler, ledger, builder, uploader, selectionStore, enabledStore, backgroundScope)

        manager.backupNow()

        val req = uploader.requests.single()
        assertEquals("video/mp4", req.payloads.single().contentType, "payload MIME is the video's")
        assertEquals(
            deterministicPhotoUniqueId(videoBytes), req.metadata.appData.uniqueId,
            "dedup id hashes the ORIGINAL video bytes, not the poster",
        )
        assertTrue(req.thumbnails.isNotEmpty(), "thumbnails come from the poster frame")
    }

    @Test
    fun video_overMaxSize_isSkippedWithoutReadingOrUploading() = runTest {
        val huge = LibraryAsset(
            deviceAssetId = "vid:99", fileName = "huge.mp4", mimeType = "video/mp4",
            takenAtMillis = 3_000_000L, addedAtMillis = null, sizeBytes = 201L * 1024 * 1024,
        )
        val crawler = FakeCrawler(mapOf("f" to listOf(huge)), bytesFor = { image }, posterFor = { image })
        val uploader = FakeUploader()
        selectionStore.setSelected(setOf("f"))
        val manager = BackupManager(crawler, ledger, builder, uploader, selectionStore, enabledStore, backgroundScope)

        manager.backupNow()

        assertEquals(0, uploader.requests.size, "an oversize video never uploads")
        assertEquals(0, crawler.readCount, "and is skipped before its bytes are read")
        assertNotNull(manager.state.value.lastError, "the skip surfaces as an error")
    }

    @Test
    fun toggleOffMidRun_stopsAfterCurrentItem() = runTest {
        val crawler = singleFolder(assets("mgr-toggle", 5))
        val uploader = FakeUploader()
        selectionStore.setSelected(setOf("f"))
        val manager = BackupManager(crawler, ledger, builder, uploader, selectionStore, enabledStore, backgroundScope)
        // Flip the toggle off from inside the first enqueue; the loop must stop before item 2.
        uploader.behavior = { manager.setEnabled(false); true }

        manager.backupNow()

        assertEquals(1, uploader.requests.size, "no item starts after the toggle goes off")
        assertEquals(1, manager.state.value.done)
        assertFalse(manager.state.value.running)
    }

    @Test
    fun itemError_isRecordedNotThrown_andRunContinues() = runTest {
        val crawler = singleFolder(assets("mgr-error", 4))
        // Only the FIRST enqueue explodes; the rest succeed. backupNow must not propagate the throw.
        var calls = 0
        val uploader = FakeUploader(behavior = { if (++calls == 1) error("boom") else true })
        selectionStore.setSelected(setOf("f"))
        val manager = BackupManager(crawler, ledger, builder, uploader, selectionStore, enabledStore, backgroundScope)

        manager.backupNow() // must return normally

        val s = manager.state.value
        assertNotNull(s.lastError)
        assertTrue(s.lastError!!.contains("boom"), "the failure reason is surfaced: ${s.lastError}")
        assertEquals(4, uploader.requests.size, "every asset is still attempted")
        assertEquals(3, s.done, "the failed item is not counted done; the other 3 are")
        assertFalse(s.running)
    }

    @Test
    fun setEnabledTrue_persists_andFreshManagerRestoresEnabledAndCount() = runTest {
        selectionStore.setSelected(setOf("A", "B"))
        val m1 = BackupManager(singleFolder(emptyList()), ledger, builder, FakeUploader(), selectionStore, enabledStore, persistScope)

        m1.setEnabled(true)
        awaitStored(true) // setEnabled persists on `scope` (off-thread DB write) — wait for it to land

        // A fresh manager models a new process: restore() reflects the persisted flag + selection
        // count WITHOUT starting a pass.
        val m2 = BackupManager(singleFolder(emptyList()), ledger, builder, FakeUploader(), selectionStore, enabledStore, persistScope)
        m2.restore()

        assertTrue(m2.state.value.enabled, "restore reflects the persisted enabled=true")
        assertEquals(2, m2.state.value.selectedFolderCount, "restore reflects the persisted selection count")
        assertFalse(m2.state.value.running, "restore must never start a pass")
    }

    @Test
    fun setEnabledFalse_persistsFalse() = runTest {
        enabledStore.setEnabled(true) // start from a persisted "on"
        val m1 = BackupManager(singleFolder(emptyList()), ledger, builder, FakeUploader(), selectionStore, enabledStore, persistScope)

        m1.setEnabled(false)
        awaitStored(false)

        val m2 = BackupManager(singleFolder(emptyList()), ledger, builder, FakeUploader(), selectionStore, enabledStore, persistScope)
        m2.restore()
        assertFalse(m2.state.value.enabled, "restore reflects the persisted enabled=false")
    }

    /**
     * setEnabled persists on the manager's `scope` (a launch that hops to the REAL DB write dispatcher),
     * so the test scheduler alone can't see it land. Interleave scheduler advances with brief real waits
     * until the persisted flag matches (or fail on timeout) — same barrier idiom as BackupViewModelTest.
     */
    private fun TestScope.awaitStored(expected: Boolean, timeoutMs: Long = 5_000) {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadlineNs) {
            advanceUntilIdle()
            if (runBlocking { enabledStore.enabled() } == expected) return
            Thread.sleep(5)
        }
        check(runBlocking { enabledStore.enabled() } == expected) {
            "timed out after ${timeoutMs}ms waiting for persisted enabled=$expected"
        }
    }

    @Test
    fun unreadableAsset_recordsErrorAndSkips() = runTest {
        val list = assets("mgr-unreadable", 2)
        val crawler = singleFolder(list) { asset -> if (asset.deviceAssetId == "mgr-unreadable-1") null else image }
        val uploader = FakeUploader()
        selectionStore.setSelected(setOf("f"))
        val manager = BackupManager(crawler, ledger, builder, uploader, selectionStore, enabledStore, backgroundScope)

        manager.backupNow()

        assertEquals(1, uploader.requests.size, "the unreadable asset is skipped, the other uploads")
        assertNotNull(manager.state.value.lastError)
        assertEquals(1, manager.state.value.done)
    }
}
