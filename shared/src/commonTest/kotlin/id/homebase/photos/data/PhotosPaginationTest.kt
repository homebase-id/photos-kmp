package id.homebase.photos.data

import id.homebase.photos.domain.PhotoItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PhotosPaginationTest {

    private fun item(userDate: Long): PhotoItem = PhotoItem(
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

    @Test
    fun mockRepositorySeedsAcrossRoughlyFourMonths() = runTest {
        val repo = MockPhotosRepository()
        val all = repo.loadPage(beforeUserDate = null, limit = 1000)
        assertTrue(all.size >= 50, "expected ~60 seeded items, got ${all.size}")

        val months = all.map {
            val ldt = millisToUtcDateTimeForTest(it.userDate)
            ldt.year * 100 + ldt.monthNumberForTest
        }.toSet()
        assertTrue(months.size >= 3, "expected items spread across ~4 months, got ${months.size}")
    }

    @Test
    fun loadPageReturnsUserDateDescending() = runTest {
        val repo = MockPhotosRepository()
        val page = repo.loadPage(beforeUserDate = null, limit = 20)
        val dates = page.map { it.userDate }
        assertEquals(dates.sortedDescending(), dates, "page must be userDate DESC")
    }

    @Test
    fun beforeCursorPaginatesStrictlyOlder() = runTest {
        val repo = MockPhotosRepository()
        val first = repo.loadPage(beforeUserDate = null, limit = 10)
        assertEquals(10, first.size)

        val cursor = first.last().userDate
        val second = repo.loadPage(beforeUserDate = cursor, limit = 10)

        // Every item on the next page is strictly older than the cursor (no overlap, no dupes).
        assertTrue(second.all { it.userDate < cursor }, "second page must be strictly older than cursor")
        val firstIds = first.map { it.fileId }.toSet()
        assertTrue(second.none { it.fileId in firstIds }, "pages must not overlap")
        // And the second page is itself DESC ordered.
        assertEquals(second.map { it.userDate }.sortedDescending(), second.map { it.userDate })
    }

    @Test
    fun loadPageHonoursLimit() = runTest {
        val repo = MockPhotosRepository()
        assertEquals(5, repo.loadPage(beforeUserDate = null, limit = 5).size)
    }

    @Test
    fun pagingToEndReturnsEmpty() = runTest {
        val repo = MockPhotosRepository()
        val all = repo.loadPage(beforeUserDate = null, limit = 10_000)
        val oldest = all.last().userDate
        assertTrue(repo.loadPage(beforeUserDate = oldest, limit = 10).isEmpty())
    }
}
