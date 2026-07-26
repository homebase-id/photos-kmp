package id.homebase.photos.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The seeded pre-login repository must behave like the real one for Favorites/Archive/Trash so
 * ViewModels and native grids can be built and screenshot-tested before login/sync exist.
 */
class MockPhotosRepositoryTest {

    @Test
    fun setFavorite_flipsIsFavoriteOnTheItemAndFeedsLoadFavoritesPage() = runTest {
        val repo = MockPhotosRepository(seedCount = 5)
        val target = repo.loadPage(null, 5).first()

        repo.setFavorite(target.fileId, favorite = true)

        val reloaded = repo.loadPage(null, 5).first { it.fileId == target.fileId }
        assertTrue(reloaded.isFavorite)
        assertEquals(listOf(target.fileId), repo.loadFavoritesPage(null, 10).items.map { it.fileId })

        repo.setFavorite(target.fileId, favorite = false)
        assertFalse(repo.loadPage(null, 5).first { it.fileId == target.fileId }.isFavorite)
        assertTrue(repo.loadFavoritesPage(null, 10).items.isEmpty())
    }

    @Test
    fun setArchived_hidesFromTimelineAndSurfacesInLoadArchivedPage() = runTest {
        val repo = MockPhotosRepository(seedCount = 5)
        val target = repo.loadPage(null, 5).first()

        val result = repo.setArchived(listOf(target.fileId), archived = true)

        assertEquals(listOf(target.fileId), result.succeeded)
        assertTrue(repo.loadPage(null, 5).none { it.fileId == target.fileId }, "archived photos leave the timeline")
        assertEquals(listOf(target.fileId), repo.loadArchivedPage(null, 10).map { it.fileId })
    }

    @Test
    fun softDelete_hidesFromTimelineAndSurfacesInLoadTrashPage() = runTest {
        val repo = MockPhotosRepository(seedCount = 5)
        val target = repo.loadPage(null, 5).first()

        repo.softDelete(listOf(target.fileId))

        assertTrue(repo.loadPage(null, 5).none { it.fileId == target.fileId })
        assertEquals(listOf(target.fileId), repo.loadTrashPage(null, 10).map { it.fileId })
    }

    @Test
    fun restore_returnsAnArchivedOrTrashedPhotoToTheTimeline() = runTest {
        val repo = MockPhotosRepository(seedCount = 5)
        val (archived, trashed) = repo.loadPage(null, 5).let { it[0] to it[1] }
        repo.setArchived(listOf(archived.fileId), archived = true)
        repo.softDelete(listOf(trashed.fileId))

        repo.restore(listOf(archived.fileId, trashed.fileId))

        assertTrue(repo.loadPage(null, 5).map { it.fileId }.containsAll(listOf(archived.fileId, trashed.fileId)))
        assertTrue(repo.loadArchivedPage(null, 10).isEmpty())
        assertTrue(repo.loadTrashPage(null, 10).isEmpty())
    }

    @Test
    fun permanentDelete_removesTheItemEntirely() = runTest {
        val repo = MockPhotosRepository(seedCount = 5)
        val target = repo.loadPage(null, 5).first()
        repo.softDelete(listOf(target.fileId))

        assertTrue(repo.permanentDelete(listOf(target.fileId)))

        assertTrue(repo.loadTrashPage(null, 10).none { it.fileId == target.fileId })
        assertTrue(repo.loadPage(null, 10).none { it.fileId == target.fileId })
    }

    @Test
    fun trashThenArchive_isExclusive_leavesTheBinAndEntersTheArchiveOnly() = runTest {
        // Real backend: archivalStatus is ONE field — archiving a trashed photo must move it OUT
        // of the bin, not leave it in both places at once.
        val repo = MockPhotosRepository(seedCount = 5)
        val target = repo.loadPage(null, 5).first()
        repo.softDelete(listOf(target.fileId))

        repo.setArchived(listOf(target.fileId), archived = true)

        assertEquals(listOf(target.fileId), repo.loadArchivedPage(null, 10).map { it.fileId })
        assertTrue(repo.loadTrashPage(null, 10).none { it.fileId == target.fileId }, "no longer in the bin")
    }

    @Test
    fun archiveThenTrash_isExclusive_leavesTheArchiveAndEntersTheBinOnly() = runTest {
        val repo = MockPhotosRepository(seedCount = 5)
        val target = repo.loadPage(null, 5).first()
        repo.setArchived(listOf(target.fileId), archived = true)

        repo.softDelete(listOf(target.fileId))

        assertEquals(listOf(target.fileId), repo.loadTrashPage(null, 10).map { it.fileId })
        assertTrue(repo.loadArchivedPage(null, 10).none { it.fileId == target.fileId }, "no longer archived")
    }

    @Test
    fun favoriteThenTrash_dropsOutOfLoadFavoritesPage() = runTest {
        // Real favoritesQuery uses archivalStatus=[0,1,3] — the bin (2) is excluded even for favorites.
        val repo = MockPhotosRepository(seedCount = 5)
        val target = repo.loadPage(null, 5).first()
        repo.setFavorite(target.fileId, favorite = true)
        assertEquals(listOf(target.fileId), repo.loadFavoritesPage(null, 10).items.map { it.fileId })

        repo.softDelete(listOf(target.fileId))

        assertTrue(repo.loadFavoritesPage(null, 10).items.none { it.fileId == target.fileId })
    }

    @Test
    fun favoriteThenArchive_staysInLoadFavoritesPage() = runTest {
        // Archived (1) is still in the favoritesQuery's archivalStatus=[0,1,3] set.
        val repo = MockPhotosRepository(seedCount = 5)
        val target = repo.loadPage(null, 5).first()
        repo.setFavorite(target.fileId, favorite = true)

        repo.setArchived(listOf(target.fileId), archived = true)

        assertEquals(listOf(target.fileId), repo.loadFavoritesPage(null, 10).items.map { it.fileId })
    }
}
