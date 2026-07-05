package id.homebase.photos.data

import id.homebase.photos.domain.PhotoItem
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

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
}
