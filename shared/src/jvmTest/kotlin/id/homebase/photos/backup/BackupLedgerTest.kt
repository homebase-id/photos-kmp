package id.homebase.photos.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * BackupLedger over the real (in-memory) KeyValue store — the same table the production ledger
 * uses, so dedup keying is exercised for real. Each test uses distinct assetId literals so the
 * JVM-wide shared DB can't cross-contaminate between tests.
 */
class BackupLedgerTest {

    private lateinit var ledger: BackupLedger

    @BeforeTest
    fun setUp() {
        try {
            runBlocking { DatabaseManager.initialize { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) } }
        } catch (_: IllegalStateException) {
            // Already initialized by a prior test sharing this JVM — reuse it.
        }
        ledger = BackupLedger(DatabaseManager.appDb.keyValue)
    }

    @Test
    fun unknownAsset_returnsNull() = runTest {
        assertNull(ledger.backedUpFileId("ledger-unknown-asset"))
    }

    @Test
    fun recordThenRead_returnsRecordedId() = runTest {
        val assetId = "ledger-basic-asset"
        val fileId = Uuid.parse("11111111-2222-3333-4444-555555555555")

        ledger.record(assetId, fileId)

        assertEquals(fileId, ledger.backedUpFileId(assetId))
    }

    @Test
    fun record_isIdempotentUpsert() = runTest {
        val assetId = "ledger-upsert-asset"
        val first = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000001")
        val second = Uuid.parse("bbbbbbbb-0000-0000-0000-000000000002")

        ledger.record(assetId, first)
        ledger.record(assetId, second)

        assertEquals(second, ledger.backedUpFileId(assetId), "second record must overwrite the first")
    }

    @Test
    fun distinctAssets_doNotCollide() = runTest {
        val idA = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
        val idB = Uuid.parse("00000000-0000-0000-0000-0000000000bb")

        ledger.record("ledger-distinct-A", idA)
        ledger.record("ledger-distinct-B", idB)

        assertEquals(idA, ledger.backedUpFileId("ledger-distinct-A"))
        assertEquals(idB, ledger.backedUpFileId("ledger-distinct-B"))
    }
}
