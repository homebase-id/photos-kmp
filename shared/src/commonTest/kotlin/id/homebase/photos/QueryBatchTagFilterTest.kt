package id.homebase.photos

import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class QueryBatchTagFilterTest {

    @Test fun albumQueryFiltersByPhotoFileTypeAndAlbumTag() {
        val albumId = Uuid.parse("11111111-2222-3333-4444-555555555555")
        val req = PhotoQueries.albumQuery(albumId)
        val q = req.queryParams

        assertEquals(listOf(PhotoConfig.PHOTO_FILE_TYPE), q.fileType)
        assertEquals(listOf(albumId), q.tagsMatchAtLeastOne)
        // album membership must NOT collapse the whole library — no broad tag-all filter
        assertNull(q.tagsMatchAll)
        // official Odin Photos album query: none / archived / apps — everything but the bin
        assertEquals(listOf(0, 1, 3), q.archivalStatus)
    }

    @Test fun albumQueryPagesTheGridButCanAskForJustTheCover() {
        val albumId = Uuid.random()

        assertEquals(
            PhotoQueries.ALBUM_PAGE_SIZE,
            PhotoQueries.albumQuery(albumId).resultOptionsRequest.maxRecords,
        )
        assertEquals(1, PhotoQueries.albumQuery(albumId, maxRecords = 1).resultOptionsRequest.maxRecords)
    }

    @Test fun albumQuerySortsByUserDateNewestFirst() {
        val albumId = Uuid.random()
        val opts = PhotoQueries.albumQuery(albumId).resultOptionsRequest

        assertEquals(QueryBatchSortField.UserDate, opts.sorting)
        assertEquals(QueryBatchSortOrder.NewestFirst, opts.ordering)
        assertEquals(true, opts.includeMetadataHeader)
    }

    @Test fun timelineQueryFiltersPhotosOnlyNoTag() {
        val q = PhotoQueries.timelineQuery().queryParams

        assertEquals(listOf(PhotoConfig.PHOTO_FILE_TYPE), q.fileType)
        // timeline spans the whole library — no album/tag constraint
        assertNull(q.tagsMatchAtLeastOne)
        assertNull(q.tagsMatchAll)
    }

    @Test fun timelineQuerySortsByUserDateNewestFirst() {
        val opts = PhotoQueries.timelineQuery().resultOptionsRequest

        assertEquals(QueryBatchSortField.UserDate, opts.sorting)
        assertEquals(QueryBatchSortOrder.NewestFirst, opts.ordering)
    }

    @Test fun distinctAlbumIdsProduceDistinctTagFilters() {
        val a = Uuid.random()
        val b = Uuid.random()
        assertEquals(listOf(a), PhotoQueries.albumQuery(a).queryParams.tagsMatchAtLeastOne)
        assertEquals(listOf(b), PhotoQueries.albumQuery(b).queryParams.tagsMatchAtLeastOne)
    }

    @Test fun favoritesQueryFiltersByPhotoFileTypeAndTagsMatchAllFavoriteTag() {
        val q = PhotoQueries.favoritesQuery().queryParams

        assertEquals(listOf(PhotoConfig.PHOTO_FILE_TYPE), q.fileType)
        assertNull(q.tagsMatchAtLeastOne, "favorites must require ALL of [favoriteTag], not just one")
        assertEquals(listOf(PhotoConfig.FAVORITE_TAG), q.tagsMatchAll)
        // official Odin Photos favorites query: none / archived / apps — everything but the bin
        assertEquals(listOf(0, 1, 3), q.archivalStatus)
    }

    @Test fun favoritesQuerySortsByUserDateNewestFirst() {
        val opts = PhotoQueries.favoritesQuery().resultOptionsRequest

        assertEquals(QueryBatchSortField.UserDate, opts.sorting)
        assertEquals(QueryBatchSortOrder.NewestFirst, opts.ordering)
        assertEquals(true, opts.includeMetadataHeader)
    }

    @Test fun favoritesQueryCarriesTheGivenCursorAndPageSize() {
        assertEquals(null, PhotoQueries.favoritesQuery().resultOptionsRequest.cursorState)
        assertEquals("cursor-1", PhotoQueries.favoritesQuery(cursor = "cursor-1").resultOptionsRequest.cursorState)
        assertEquals(10, PhotoQueries.favoritesQuery(maxRecords = 10).resultOptionsRequest.maxRecords)
    }
}
