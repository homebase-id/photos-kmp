package id.homebase.photos.timeline

import id.homebase.photos.domain.PhotoItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class TimelineGroupingTest {

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

    // Reference epoch-millis (UTC noon) on known days, so a viewer's local-zone
    // offset can't slip an item into an adjacent month.
    private val jun14_2026 = 1_781_438_400_000L // 2026-06-14T12:00Z
    private val jun28_2026 = 1_782_648_000_000L // 2026-06-28T12:00Z
    private val may05_2026 = 1_777_982_400_000L // 2026-05-05T12:00Z

    @Test
    fun groupsItemsByMonthIntoSections() {
        val items = listOf(item(jun28_2026), item(jun14_2026), item(may05_2026))
        val sections = groupIntoMonthSections(items)

        // Two distinct months → two sections.
        assertEquals(2, sections.size)
        // June section carries both June items; May section carries one.
        val june = sections.first { it.title.startsWith("June") }
        assertEquals(2, june.items.size)
        val may = sections.first { it.title.startsWith("May") }
        assertEquals(1, may.items.size)
    }

    @Test
    fun sectionTitleIsFullMonthAndYear() {
        val sections = groupIntoMonthSections(listOf(item(jun14_2026)))
        assertEquals(1, sections.size)
        assertEquals("June 2026", sections.single().title)
    }

    @Test
    fun sectionsAreNewestMonthFirst() {
        val sections = groupIntoMonthSections(
            listOf(item(may05_2026), item(jun14_2026)),
        )
        assertEquals("June 2026", sections.first().title)
        assertEquals("May 2026", sections.last().title)
    }

    @Test
    fun itemsWithinASectionStayNewestFirst() {
        val sections = groupIntoMonthSections(listOf(item(jun28_2026), item(jun14_2026)))
        val june = sections.single()
        assertEquals(june.items.map { it.userDate }.sortedDescending(), june.items.map { it.userDate })
    }

    @Test
    fun emptyInputYieldsNoSections() {
        assertTrue(groupIntoMonthSections(emptyList()).isEmpty())
    }
}
