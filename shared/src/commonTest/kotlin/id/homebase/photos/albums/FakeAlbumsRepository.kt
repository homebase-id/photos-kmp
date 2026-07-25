package id.homebase.photos.albums

import id.homebase.photos.data.AlbumMembershipResult
import id.homebase.photos.data.AlbumsRepository
import id.homebase.photos.domain.AlbumItem
import id.homebase.photos.domain.PhotoItem
import kotlinx.coroutines.CompletableDeferred
import kotlin.uuid.Uuid

/**
 * In-memory [AlbumsRepository] for the album ViewModel tests. Writes mutate the fake's own
 * state, so a post-write `sync + refresh` reconcile reads back what the write did — the same
 * loop the real repository goes through.
 */
internal class FakeAlbumsRepository(
    albums: List<AlbumItem> = emptyList(),
    private val photosByAlbum: Map<Uuid, List<PhotoItem>> = emptyMap(),
    private val localPhotos: Map<Uuid, PhotoItem> = emptyMap(),
    var loadAlbumsThrows: Boolean = false,
    var loadPhotosThrows: Boolean = false,
    var writeThrows: Boolean = false,
    var deleteSucceeds: Boolean = true,
    /** File ids whose membership patch "fails" — drives the partial-result paths. */
    var failMembershipFor: Set<Uuid> = emptySet(),
    /** Parks cover resolution so a test can observe the names-first emission. */
    val photosGate: CompletableDeferred<Unit>? = null,
    /** Parks [createAlbum] so a test can fire a second write while the first is in flight. */
    val writeGate: CompletableDeferred<Unit>? = null,
    /** A created album stays out of [loadAlbums] — the sync hasn't indexed the file yet. */
    var indexLagsWrites: Boolean = false,
) : AlbumsRepository {

    val albums: MutableList<AlbumItem> = albums.toMutableList()
    private val membership: MutableMap<Uuid, MutableList<PhotoItem>> =
        photosByAlbum.mapValues { it.value.toMutableList() }.toMutableMap()

    var syncCount = 0
        private set

    /** How many times a cover had to be resolved — a reconcile must not re-run these. */
    var coverLoads = 0
        private set
    val addCalls = mutableListOf<Pair<Uuid, List<Uuid>>>()
    val removeCalls = mutableListOf<Pair<Uuid, List<Uuid>>>()

    override suspend fun loadAlbums(): List<AlbumItem> {
        if (loadAlbumsThrows) throw IllegalStateException("albums exploded")
        return albums.toList()
    }

    override suspend fun loadAlbumPhotos(albumId: Uuid): List<PhotoItem> {
        photosGate?.await()
        if (loadPhotosThrows) throw IllegalStateException("photos exploded")
        return membership[albumId]?.toList() ?: emptyList()
    }

    override suspend fun loadPhoto(fileId: Uuid): PhotoItem? {
        photosGate?.await()
        coverLoads++
        return localPhotos[fileId]
    }

    override suspend fun loadNewestAlbumPhoto(albumId: Uuid): PhotoItem? {
        photosGate?.await()
        coverLoads++
        return membership[albumId]?.firstOrNull()
    }

    override suspend fun sync() {
        syncCount++
    }

    override suspend fun createAlbum(name: String, description: String?): AlbumItem {
        writeGate?.await()
        if (writeThrows) throw IllegalStateException("create exploded")
        val album = AlbumItem(
            fileId = Uuid.random(),
            albumId = Uuid.random(),
            name = name,
            coverFileId = null,
            description = description,
        )
        if (!indexLagsWrites) albums += album
        return album
    }

    override suspend fun renameAlbum(album: AlbumItem, newName: String): AlbumItem {
        if (writeThrows) throw IllegalStateException("rename exploded")
        return album.copy(name = newName).also { replace(it) }
    }

    override suspend fun setCover(album: AlbumItem, photoFileId: Uuid): AlbumItem {
        if (writeThrows) throw IllegalStateException("cover exploded")
        return album.copy(coverFileId = photoFileId).also { replace(it) }
    }

    override suspend fun deleteAlbum(album: AlbumItem): Boolean {
        if (writeThrows) throw IllegalStateException("delete exploded")
        if (deleteSucceeds) albums.removeAll { it.fileId == album.fileId }
        return deleteSucceeds
    }

    override suspend fun addPhotos(albumTag: Uuid, fileIds: List<Uuid>): AlbumMembershipResult {
        if (writeThrows) throw IllegalStateException("add exploded")
        addCalls += albumTag to fileIds
        return split(fileIds)
    }

    override suspend fun removePhotos(albumTag: Uuid, fileIds: List<Uuid>): AlbumMembershipResult {
        if (writeThrows) throw IllegalStateException("remove exploded")
        removeCalls += albumTag to fileIds
        val result = split(fileIds)
        membership[albumTag]?.removeAll { it.fileId in result.succeeded.toSet() }
        return result
    }

    private fun split(fileIds: List<Uuid>) = AlbumMembershipResult(
        succeeded = fileIds.filterNot { it in failMembershipFor },
        failed = fileIds.filter { it in failMembershipFor },
    )

    private fun replace(album: AlbumItem) {
        val i = albums.indexOfFirst { it.fileId == album.fileId }
        if (i >= 0) albums[i] = album
    }
}
