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
}
