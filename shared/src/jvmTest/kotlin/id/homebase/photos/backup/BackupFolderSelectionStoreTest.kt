package id.homebase.photos.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BackupFolderSelectionStore over the real (in-memory) KeyValue table — the same table the
 * production store uses. The store keys off ONE fixed key, so a per-test reset in setUp keeps the
 * JVM-wide shared DB from carrying a selection between tests.
 */
class BackupFolderSelectionStoreTest {

    private lateinit var store: BackupFolderSelectionStore

    @BeforeTest
    fun setUp() {
        try {
            runBlocking { DatabaseManager.initialize { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) } }
        } catch (_: IllegalStateException) {
        }
        store = BackupFolderSelectionStore(DatabaseManager.appDb.keyValue)
        runBlocking { store.setSelected(emptySet()) }
    }

    @Test
    fun default_isEmpty() = runTest {
        // Fresh key never written → the D6 default: nothing selected.
        store.setSelected(emptySet())
        assertEquals(emptySet(), store.selected())
    }

    @Test
    fun setThenRead_roundTripsMultipleFolders() = runTest {
        // Numeric MediaStore BUCKET_IDs, incl. a negative one (the signed-hash case).
        val ids = setOf("17", "-1739773001", "42")
        store.setSelected(ids)
        assertEquals(ids, store.selected())
    }

    @Test
    fun setSelected_replacesPreviousValue() = runTest {
        store.setSelected(setOf("1", "2", "3"))
        store.setSelected(setOf("9"))
        assertEquals(setOf("9"), store.selected(), "a later write replaces the whole selection")
    }

    @Test
    fun setSelected_empty_clearsSelection() = runTest {
        store.setSelected(setOf("1", "2"))
        store.setSelected(emptySet())
        assertEquals(emptySet(), store.selected())
    }

    @Test
    fun toggle_addsThenRemovesAndReturnsUpdatedSet() = runTest {
        assertEquals(setOf("camera"), store.toggle("camera"))
        assertEquals(setOf("camera"), store.selected())

        assertEquals(setOf("camera", "shots"), store.toggle("shots"))
        assertEquals(setOf("camera", "shots"), store.selected())

        assertEquals(setOf("shots"), store.toggle("camera"), "toggling a selected folder removes it")
        assertEquals(setOf("shots"), store.selected())
    }
}
