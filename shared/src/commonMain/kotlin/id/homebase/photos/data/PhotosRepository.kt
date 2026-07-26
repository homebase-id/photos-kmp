package id.homebase.photos.data

import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.viewer.VideoHandle
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/** One server-paged batch of favorited photos, plus the cursor for the next page (null = end). */
data class FavoritesPage(
    val items: List<PhotoItem>,
    val nextCursor: String?,
)

/**
 * Timeline data source. Impl wraps `homebase-api` (DriveSync pull + a local
 * DriveMainIndex paged query, userDate DESC).
 */
interface PhotosRepository {
    /** Local DriveMainIndex stream, userDate DESC. Emits again after each [sync]. */
    fun observePhotos(): Flow<List<PhotoItem>>

    /**
     * One page of photos older than [beforeUserDate] (null = newest page),
     * userDate DESC, at most [limit] items. Empty when the end is reached.
     */
    suspend fun loadPage(beforeUserDate: Long?, limit: Int): List<PhotoItem>

    /** DriveSync pull of the Photos drive; refreshes the local index. */
    suspend fun sync()

    /** Soft-delete [fileIds] on the drive (batch). True when every file deleted. */
    suspend fun deletePhotos(fileIds: List<Uuid>): Boolean

    /**
     * Decoded thumbnail bytes for [item] sized to roughly [maxDim] on the longest
     * edge. For iOS native rendering, which has no Coil pipeline — wraps
     * `HomebaseImageLoader`. Returns null when the bytes can't be loaded.
     */
    suspend fun loadThumbnailBytes(item: PhotoItem, maxDim: Int): ByteArray?

    /** Full-res decrypted payload bytes for [item] (stills share/save). Null when unavailable. */
    suspend fun loadOriginalBytes(item: PhotoItem): ByteArray?

    /** Decrypt [item]'s video payload to a cache temp file. Null = can't play (missing/segmented). */
    suspend fun prepareVideo(item: PhotoItem): VideoHandle?

    /** Delete [handle]'s temp file. */
    suspend fun disposeVideo(handle: VideoHandle)

    /** Header-only tag patch. Idempotent — already-favorited (or not) is a no-op success. */
    suspend fun setFavorite(fileId: Uuid, favorite: Boolean): Boolean

    /** archivalStatus patch: true → Archived, false → None (unarchive). */
    suspend fun setArchived(fileIds: List<Uuid>, archived: Boolean): PhotoStatusResult

    /** archivalStatus patch → Removed (the bin, distinct from [deletePhotos]'s hard delete). */
    suspend fun softDelete(fileIds: List<Uuid>): PhotoStatusResult

    /** archivalStatus patch → None, out of both Archive and Trash. */
    suspend fun restore(fileIds: List<Uuid>): PhotoStatusResult

    /** Irreversible: hard-deletes [fileIds] on the drive. True when every file is gone. */
    suspend fun permanentDelete(fileIds: List<Uuid>): Boolean

    /** Favorited photos, newest first — server queryBatch, cursor-paged. */
    suspend fun loadFavoritesPage(cursor: String?, limit: Int): FavoritesPage

    /**
     * One page of ARCHIVED photos older than [beforeUserDate] (null = newest page),
     * userDate DESC — local DriveMainIndex, mirrors [loadPage]'s cursor contract.
     */
    suspend fun loadArchivedPage(beforeUserDate: Long?, limit: Int): List<PhotoItem>

    /** One page of TRASHED (binned) photos, same cursor contract as [loadArchivedPage]. */
    suspend fun loadTrashPage(beforeUserDate: Long?, limit: Int): List<PhotoItem>
}
