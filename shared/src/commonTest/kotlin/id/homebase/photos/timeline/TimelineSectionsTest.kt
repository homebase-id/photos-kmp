package id.homebase.photos.timeline

import id.homebase.photos.domain.PhotoItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Pure-function tests for [appendToMonthSections] — no coroutines needed. */
class TimelineSectionsTest {

    private fun item(userDate: Long): PhotoItem = PhotoItem(
        fileId = Uuid.random(),
        uniqueId = null,
        userDate = userDate,
        isVideo = false,
        pixelWidth = 100,
        pixelHeight = 100,
        previewPlaceholder = null,
        driveId = Uuid.random(),
        payloadKey = "dflt_key",
    )

    // Reference epoch-millis (UTC noon) on known days.
    private val jun28_2026 = 1_782_648_000_000L // 2026-06-28T12:00Z
    private val jun14_2026 = 1_781_438_400_000L // 2026-06-14T12:00Z
    private val may05_2026 = 1_777_982_400_000L // 2026-05-05T12:00Z

    @Test
    fun mergesSameMonthPageIntoLastSection() {
        val existing = groupIntoMonthSections(listOf(item(jun28_2026)))
        val page = listOf(item(jun14_2026))

        val result = appendToMonthSections(existing, page)

        assertEquals(1, result.size)
        assertEquals("June 2026", result.single().title)
        assertEquals(2, result.single().items.size)
    }

    @Test
    fun appendsNewMonthAsNewSection() {
        val existing = groupIntoMonthSections(listOf(item(jun28_2026)))
        val page = listOf(item(may05_2026))

        val result = appendToMonthSections(existing, page)

        assertEquals(2, result.size)
        assertEquals("June 2026", result[0].title)
        assertEquals("May 2026", result[1].title)
    }

    @Test
    fun preservesObjectIdentityOfUntouchedSections() {
        val existing = groupIntoMonthSections(listOf(item(jun28_2026), item(may05_2026)))
        // Page continues a brand-new, older month — neither existing section merges.
        val page = listOf(item(may05_2026 - 30L * 24 * 60 * 60 * 1000))

        val result = appendToMonthSections(existing, page)

        assertSame(existing[0], result[0])
        assertSame(existing[1], result[1])
    }

    @Test
    fun emptyPageReturnsOriginalList() {
        val existing = groupIntoMonthSections(listOf(item(jun28_2026)))

        val result = appendToMonthSections(existing, emptyList())

        assertTrue(result === existing)
    }
}
