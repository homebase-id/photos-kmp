package id.homebase.photos.data

import id.homebase.photos.domain.AlbumItem
import id.homebase.photos.domain.PhotoItem
import kotlin.uuid.Uuid

/**
 * Albums data source. Impl reads album files from the local DriveMainIndex and
 * album membership (photos tagged with an albumId) from the server via queryBatch.
 */
interface AlbumsRepository {
    /** Album files (fileType 900) from the local DriveMainIndex. */
    suspend fun loadAlbums(): List<AlbumItem>

    /** Photos tagged into [albumId], newest first — server queryBatch. */
    suspend fun loadAlbumPhotos(albumId: Uuid): List<PhotoItem>
}
