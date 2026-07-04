package id.homebase.photos.backup

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Contract check for the no-op crawler bound on platforms without a native crawler (iOS/JVM), and
 * the folder-selective interface shape: empty folders, and `assets(emptySet())` (the D6 default)
 * returning an empty list — so enabling backup on those targets uploads nothing.
 */
class StubPhotoLibraryCrawlerTest {

    private val crawler = StubPhotoLibraryCrawler()

    @Test
    fun folders_isEmpty() = runTest {
        assertEquals(emptyList(), crawler.folders())
    }

    @Test
    fun assets_emptySelection_returnsEmpty() = runTest {
        assertEquals(emptyList(), crawler.assets(emptySet()))
    }

    @Test
    fun assets_anySelection_stillEmpty() = runTest {
        assertEquals(emptyList(), crawler.assets(setOf("10", "20")))
    }

    @Test
    fun readBytes_isNull() = runTest {
        val asset = LibraryAsset(
            deviceAssetId = "1",
            fileName = "x.png",
            mimeType = "image/png",
            takenAtMillis = null,
            addedAtMillis = null,
            sizeBytes = null,
        )
        assertNull(crawler.readBytes(asset))
    }
}
