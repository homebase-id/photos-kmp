package id.homebase.photos.data

import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.search.SearchCriteria
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * [applySearchFilters] is the post-fetch predicate [PhotosRepositoryImpl.search] applies to BOTH
 * the album branch and the local date-range branch. Regression coverage for the bug where the
 * album branch silently ignored the date-range chip (the album server query has no date bound
 * of its own) — a user picking an album AND a date range must only see that album's photos
 * inside the range, not the whole album.
 */
class PhotosRepositorySearchFilterTest {

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

    @Test
    fun albumSourcedItems_outsideDateRange_areFiltered() {
        // Simulates the album branch's fetch: items from an album server query, some outside the
        // requested date range — the exact scenario the album query itself cannot filter.
        val inRange = item(1_500L)
        val tooOld = item(500L)
        val tooNew = item(5_000L)
        val criteria = SearchCriteria(fromUserDate = 1_000L, toUserDate = 2_000L, albumIds = listOf(Uuid.random()))

        val result = applySearchFilters(listOf(inRange, tooOld, tooNew), criteria, cap = 500)

        assertEquals(listOf(inRange), result)
    }

    @Test
    fun isVideoNull_keepsBothTypes() {
        val photo = item(1L, isVideo = false)
        val video = item(2L, isVideo = true)

        val result = applySearchFilters(listOf(photo, video), SearchCriteria(), cap = 500)

        assertEquals(listOf(video, photo), result, "newest first")
    }

    @Test
    fun isVideoTrue_keepsOnlyVideos() {
        val photo = item(1L, isVideo = false)
        val video = item(2L, isVideo = true)

        val result = applySearchFilters(listOf(photo, video), SearchCriteria(isVideo = true), cap = 500)

        assertEquals(listOf(video), result)
    }

    @Test
    fun combinedDateRangeAndType_narrowsOnBoth() {
        val matches = item(1_500L, isVideo = true)
        val wrongType = item(1_500L, isVideo = false)
        val wrongDate = item(9_000L, isVideo = true)
        val criteria = SearchCriteria(fromUserDate = 1_000L, toUserDate = 2_000L, isVideo = true)

        val result = applySearchFilters(listOf(matches, wrongType, wrongDate), criteria, cap = 500)

        assertEquals(listOf(matches), result)
    }

    @Test
    fun resultsAreSortedNewestFirstAndCapped() {
        val items = listOf(item(1L), item(3L), item(2L))

        val result = applySearchFilters(items, SearchCriteria(), cap = 2)

        assertEquals(listOf(item(3L).userDate, item(2L).userDate), result.map { it.userDate })
    }
}
