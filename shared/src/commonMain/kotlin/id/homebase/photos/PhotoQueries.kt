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

    // ponytail: 500-photo album ceiling (queryBatch's own default is 100 — too low to page an
    // album grid off); cursor-page it when someone actually hits this.
    const val ALBUM_PAGE_SIZE = 500

    // Official Odin Photos album query: none(0) / archived(1) / apps(3) — everything but the bin.
    private val albumArchivalStatus = listOf(0, 1, 3)

    /** Photos tagged into [albumId], newest first. [maxRecords] 1 gives the cover photo. */
    fun albumQuery(albumId: Uuid, maxRecords: Int = ALBUM_PAGE_SIZE): QueryBatchRequest = QueryBatchRequest(
        queryParams = FileQueryParams(
            fileType = listOf(PhotoConfig.PHOTO_FILE_TYPE),
            tagsMatchAtLeastOne = listOf(albumId),
            archivalStatus = albumArchivalStatus,
        ),
        resultOptionsRequest = newestByUserDate.copy(maxRecords = maxRecords),
    )

    /** Whole-library timeline, newest first. */
    fun timelineQuery(): QueryBatchRequest = QueryBatchRequest(
        queryParams = FileQueryParams(
            fileType = listOf(PhotoConfig.PHOTO_FILE_TYPE),
        ),
        resultOptionsRequest = newestByUserDate,
    )

    /** Favorited photos (tagged with FAVORITE_TAG), newest first, cursor-paged. */
    fun favoritesQuery(cursor: String? = null, maxRecords: Int = ALBUM_PAGE_SIZE): QueryBatchRequest =
        QueryBatchRequest(
            queryParams = FileQueryParams(
                fileType = listOf(PhotoConfig.PHOTO_FILE_TYPE),
                tagsMatchAll = listOf(PhotoConfig.FAVORITE_TAG),
                archivalStatus = albumArchivalStatus, // none / archived / apps — everything but the bin
            ),
            resultOptionsRequest = newestByUserDate.copy(maxRecords = maxRecords, cursorState = cursor),
        )
}
