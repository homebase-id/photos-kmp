package id.homebase.photos

import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.FileQueryParams
import kotlin.uuid.Uuid

/** Pure builders for the Photos drive — no I/O. Callers pass these to DriveQueryProvider.queryBatch. */
object PhotoQueries {

    // Newest-first by EXIF capture date is the timeline/album sort everywhere in the app.
    private val newestByUserDate = QueryBatchResultOptionsRequest(
        ordering = QueryBatchSortOrder.NewestFirst,
        sorting = QueryBatchSortField.UserDate,
        includeMetadataHeader = true,
    )

    /** Photos tagged into [albumId], newest first. */
    fun albumQuery(albumId: Uuid): QueryBatchRequest = QueryBatchRequest(
        queryParams = FileQueryParams(
            fileType = listOf(PhotoConfig.PHOTO_FILE_TYPE),
            tagsMatchAtLeastOne = listOf(albumId),
        ),
        resultOptionsRequest = newestByUserDate,
    )

    /** Whole-library timeline, newest first. */
    fun timelineQuery(): QueryBatchRequest = QueryBatchRequest(
        queryParams = FileQueryParams(
            fileType = listOf(PhotoConfig.PHOTO_FILE_TYPE),
        ),
        resultOptionsRequest = newestByUserDate,
    )
}
