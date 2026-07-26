package id.homebase.photos.search

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RecentSearchesStore over the real (in-memory) KeyValue table, same as BackupFolderSelectionStore
 * gets tested. The store keys off ONE fixed key, so a per-test reset in setUp keeps the JVM-wide
 * shared DB from carrying recents between tests.
 */
class RecentSearchesStoreTest {

    private lateinit var store: RecentSearchesStore

    @BeforeTest
    fun setUp() {
        try {
            runBlocking { DatabaseManager.initialize { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) } }
        } catch (_: IllegalStateException) {
        }
        store = RecentSearchesStore(DatabaseManager.appDb.keyValue)
        runBlocking { store.clear() }
    }

    @Test
    fun default_isEmpty() = runTest {
        assertEquals(emptyList(), store.load())
    }

    @Test
    fun push_prependsMostRecentFirst() = runTest {
        store.push("beach")
        store.push("mountains")
        store.push("city")

        assertEquals(listOf("city", "mountains", "beach"), store.load())
    }

    @Test
    fun push_dedupesCaseInsensitively_movingTheDupeToFront() = runTest {
        store.push("Paris")
        store.push("Tokyo")
        store.push("paris")

        assertEquals(listOf("paris", "Tokyo"), store.load(), "the re-searched query moves to the front, no duplicate entry")
    }

    @Test
    fun push_blankQuery_isANoOp() = runTest {
        store.push("camera")
        store.push("   ")

        assertEquals(listOf("camera"), store.load())
    }

    @Test
    fun push_beyondCap_dropsTheOldest() = runTest {
        (1..12).forEach { store.push("q$it") }

        val loaded = store.load()
        assertEquals(10, loaded.size)
        assertEquals((3..12).map { "q$it" }.reversed(), loaded, "newest 10 survive, oldest 2 dropped")
    }

    @Test
    fun clear_emptiesTheList() = runTest {
        store.push("camera")
        store.clear()

        assertEquals(emptyList(), store.load())
    }
}
