package id.homebase.photos.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BackupEnabledStore over the real (in-memory) KeyValue table — the same table the production store
 * uses. The store keys off ONE fixed key, so a per-test reset in setUp keeps the JVM-wide shared DB
 * from carrying a flag between tests.
 */
class BackupEnabledStoreTest {

    private lateinit var store: BackupEnabledStore

    @BeforeTest
    fun setUp() {
        try {
            runBlocking { DatabaseManager.initialize { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) } }
        } catch (_: IllegalStateException) {
        }
        store = BackupEnabledStore(DatabaseManager.appDb.keyValue)
        runBlocking { store.setEnabled(false) }
    }

    @Test
    fun default_isFalse() = runTest {
        store.setEnabled(false)
        assertFalse(store.enabled(), "backup is off until deliberately enabled")
    }

    @Test
    fun setTrue_roundTrips() = runTest {
        store.setEnabled(true)
        assertTrue(store.enabled())
    }

    @Test
    fun setFalse_replacesPreviousTrue() = runTest {
        store.setEnabled(true)
        store.setEnabled(false)
        assertFalse(store.enabled(), "a later write replaces the persisted flag")
    }
}
