package id.homebase.photos.data

import id.homebase.photos.domain.AlbumItem
import id.homebase.photos.domain.PhotoItem
import kotlin.uuid.Uuid

/** Per-file result of a membership write — lets the UI report "3 of 5 added". */
data class AlbumMembershipResult(
    val succeeded: List<Uuid> = emptyList(),
    val failed: List<Uuid> = emptyList(),
) {
    val isCompleteSuccess: Boolean get() = failed.isEmpty()
}

/**
 * Albums data source. Album files (fileType 400) come off the local DriveMainIndex; album
 * MEMBERSHIP (photos tagged with the album's tag) is a server queryBatch — membership tags are
 * not indexed locally. Writes go straight to the server (albums are tiny header-only files),
 * so a [sync] is what makes a written album visible to [loadAlbums] again.
 */
interface AlbumsRepository {
    /** Album files from the local DriveMainIndex. */
    suspend fun loadAlbums(): List<AlbumItem>

    /** Photos tagged into [albumId], newest first — server queryBatch. */
    suspend fun loadAlbumPhotos(albumId: Uuid): List<PhotoItem>

    /** One photo from the local index, or null when it isn't synced (or is gone). */
    suspend fun loadPhoto(fileId: Uuid): PhotoItem?

    /** The album's newest member photo — the cover fallback; a 1-result server query. */
    suspend fun loadNewestAlbumPhoto(albumId: Uuid): PhotoItem?

    /** Pull the Photos drive so freshly written album files land in the local index. */
    suspend fun sync()

    /** Creates a `fileType 400` album file with a freshly minted tag. Throws on failure. */
    suspend fun createAlbum(name: String, description: String? = null): AlbumItem

    /** Header-only content rewrite — name changes, tag/uniqueId/tags don't. Throws on failure. */
    suspend fun renameAlbum(album: AlbumItem, newName: String): AlbumItem

    /** Pins [photoFileId] as the album cover (our `coverFileId` content extension). */
    suspend fun setCover(album: AlbumItem, photoFileId: Uuid): AlbumItem

    /** Soft-deletes the album FILE only; member photos keep their (now dangling) tag. */
    suspend fun deleteAlbum(album: AlbumItem): Boolean

    /** Adds [albumTag] to each photo's own tags (one header patch per photo). */
    suspend fun addPhotos(albumTag: Uuid, fileIds: List<Uuid>): AlbumMembershipResult

    /** Removes [albumTag] from each photo's own tags; the photos themselves survive. */
    suspend fun removePhotos(albumTag: Uuid, fileIds: List<Uuid>): AlbumMembershipResult
}
