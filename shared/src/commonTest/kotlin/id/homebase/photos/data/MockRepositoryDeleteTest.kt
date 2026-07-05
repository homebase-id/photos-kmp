package id.homebase.photos.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Delete contract (C2) against the mock: deleted ids leave the seeded list for good,
 * so a re-read of the newest page no longer serves them.
 */
class MockRepositoryDeleteTest {

    @Test
    fun deletePhotos_removesIdsFromSubsequentPages_andReturnsTrue() = runTest {
        val repo = MockPhotosRepository(seedCount = 12)
        val firstPage = repo.loadPage(beforeUserDate = null, limit = 12)
        val doomed = firstPage.take(2).map { it.fileId }

        assertTrue(repo.deletePhotos(doomed))

        val after = repo.loadPage(beforeUserDate = null, limit = 12)
        assertEquals(10, after.size)
        assertTrue(after.none { it.fileId in doomed })
    }

    @Test
    fun deletePhotos_emptyList_isANoOpReturningTrue() = runTest {
        val repo = MockPhotosRepository(seedCount = 5)

        assertTrue(repo.deletePhotos(emptyList()))

        assertEquals(5, repo.loadPage(beforeUserDate = null, limit = 10).size)
    }
}
