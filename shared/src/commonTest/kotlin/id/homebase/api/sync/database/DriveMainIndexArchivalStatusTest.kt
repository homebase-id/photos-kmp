package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Batch D timeline regression: the Photos timeline (`selectPhotosPage`) must show ONLY
 * archivalStatus=0 (None) rows — archived (1) and trashed (2) photos disappear from it — while a
 * dedicated per-status page (`selectPhotosByArchivalStatusPage`) is how the Archive/Trash screens
 * read the ones the timeline hides. Restoring a row (archivalStatus back to 0) must bring it back.
 */
class DriveMainIndexArchivalStatusTest {

    private var driver: SqlDriver? = null
    private lateinit var db: OdinDatabase

    @BeforeTest
    fun setup() {
        driver = createInMemoryDatabase()
        db = TestDatabaseFactory.createTestDatabase(driver)
    }

    @AfterTest
    fun tearDown() {
        driver?.close()
    }

    private val identityId = Uuid.random()
    private val driveId = Uuid.random()
    private val photoFileType = 0L

    // upsertDriveMainIndex only applies an ON CONFLICT update when excluded.modified is STRICTLY
    // greater than the stored value — pass a bumped [modified] when re-projecting the same fileId
    // (e.g. simulating a restore), or the "update" silently no-ops.
    private fun insertPhoto(fileId: Uuid, archivalStatus: Long, userDate: Long, modified: Long = userDate) {
        db.driveMainIndexQueries.upsertDriveMainIndex(
            identityId = identityId,
            driveId = driveId,
            fileId = fileId,
            uniqueId = Uuid.random(),
            globalTransitId = Uuid.random(),
            groupId = null,
            senderId = null,
            originalAuthor = null,
            fileType = photoFileType,
            dataType = 0L,
            archivalStatus = archivalStatus,
            fileState = 1L, // Active
            historyStatus = 0L,
            userDate = userDate,
            created = userDate,
            modified = modified,
            fileSystemType = 0L,
            jsonHeader = """{"fileId":"$fileId","archivalStatus":$archivalStatus}""",
        )
    }

    @Test
    fun selectPhotosPage_excludesArchivedAndTrashedRows() = runTest {
        val none = Uuid.random()
        val archived = Uuid.random()
        val trashed = Uuid.random()
        insertPhoto(none, archivalStatus = 0, userDate = 300L)
        insertPhoto(archived, archivalStatus = 1, userDate = 200L)
        insertPhoto(trashed, archivalStatus = 2, userDate = 100L)

        // Positional: SQLDelight infers per-param names from SQL context (e.g. `value` for LIMIT),
        // not from the wrapper's kwarg names — positional calls avoid coupling to that inference.
        val page = db.driveMainIndexQueries.selectPhotosPage(
            identityId, driveId, photoFileType, Long.MAX_VALUE, 10L,
        ).executeAsList()

        assertEquals(1, page.size)
        assertEquals(listOf("""{"fileId":"$none","archivalStatus":0}"""), page)
    }

    @Test
    fun selectPhotosPage_afterRestore_reincludesTheRow() = runTest {
        val fileId = Uuid.random()
        insertPhoto(fileId, archivalStatus = 2, userDate = 100L)
        assertEquals(
            0,
            db.driveMainIndexQueries.selectPhotosPage(
                identityId, driveId, photoFileType, Long.MAX_VALUE, 10L,
            ).executeAsList().size,
        )

        // Restore: archivalStatus back to None(0), same fileId (upsert re-projects the row).
        insertPhoto(fileId, archivalStatus = 0, userDate = 100L, modified = 200L)

        val page = db.driveMainIndexQueries.selectPhotosPage(
            identityId, driveId, photoFileType, Long.MAX_VALUE, 10L,
        ).executeAsList()
        assertEquals(1, page.size)
    }

    @Test
    fun selectPhotosByArchivalStatusPage_returnsOnlyTheRequestedStatus() = runTest {
        val none = Uuid.random()
        val archived = Uuid.random()
        val trashed = Uuid.random()
        insertPhoto(none, archivalStatus = 0, userDate = 300L)
        insertPhoto(archived, archivalStatus = 1, userDate = 200L)
        insertPhoto(trashed, archivalStatus = 2, userDate = 100L)

        val archivedPage = db.driveMainIndexQueries.selectPhotosByArchivalStatusPage(
            identityId, driveId, photoFileType, 1L, Long.MAX_VALUE, 10L,
        ).executeAsList()
        assertEquals(listOf("""{"fileId":"$archived","archivalStatus":1}"""), archivedPage)

        val trashedPage = db.driveMainIndexQueries.selectPhotosByArchivalStatusPage(
            identityId, driveId, photoFileType, 2L, Long.MAX_VALUE, 10L,
        ).executeAsList()
        assertEquals(listOf("""{"fileId":"$trashed","archivalStatus":2}"""), trashedPage)
    }

    @Test
    fun selectPhotosByArchivalStatusPage_ordersNewestUserDateFirst() = runTest {
        val older = Uuid.random()
        val newer = Uuid.random()
        insertPhoto(older, archivalStatus = 1, userDate = 100L)
        insertPhoto(newer, archivalStatus = 1, userDate = 200L)

        val page = db.driveMainIndexQueries.selectPhotosByArchivalStatusPage(
            identityId, driveId, photoFileType, 1L, Long.MAX_VALUE, 10L,
        ).executeAsList()

        assertEquals(listOf("""{"fileId":"$newer","archivalStatus":1}""", """{"fileId":"$older","archivalStatus":1}"""), page)
    }
}
