package id.homebase.photos.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** The mock must actually render — every item needs a real, decodable placeholder. */
class MockPlaceholderTest {

    @Test
    fun everySeededItemHasANonEmptyPlaceholder() = runTest {
        val repository = MockPhotosRepository()

        val page = repository.loadPage(beforeUserDate = null, limit = 60)

        assertEquals(60, page.size)
        page.forEach { item ->
            val placeholder = item.previewPlaceholder
            assertNotNull(placeholder, "expected a placeholder for $item")
            assertFalse(placeholder.isEmpty(), "expected a non-empty placeholder for $item")
        }
    }

    @Test
    fun loadThumbnailBytesReturnsWebpBytes() = runTest {
        val repository = MockPhotosRepository()
        val item = repository.loadPage(beforeUserDate = null, limit = 1).single()

        val bytes = repository.loadThumbnailBytes(item, maxDim = 300)

        assertNotNull(bytes)
        assertEquals(0x52, bytes[0].toInt()) // 'R'
        assertEquals(0x49, bytes[1].toInt()) // 'I'
        assertEquals(0x46, bytes[2].toInt()) // 'F'
        assertEquals(0x46, bytes[3].toInt()) // 'F'
    }
}
