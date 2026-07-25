@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.photos.android.ui.collections

import id.homebase.photos.data.AlbumMembershipResult
import id.homebase.photos.data.AlbumsRepository
import id.homebase.photos.domain.AlbumItem
import id.homebase.photos.domain.PhotoItem
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * In-memory [AlbumsRepository] for the Compose flow tests: the real shared ViewModels run against
 * it, so create / rename / set-cover / remove exercise the same optimistic-patch-then-reconcile
 * path they take on device — only the network is missing.
 */
class FakeAlbumsRepository(
    albums: List<AlbumItem> = emptyList(),
    photos: Map<Uuid, List<PhotoItem>> = emptyMap(),
) : AlbumsRepository {

    private val albumList = albums.toMutableList()
    private val membership = photos.mapValues { it.value.toMutableList() }.toMutableMap()

    var syncCount = 0
        private set

    override suspend fun loadAlbums(): List<AlbumItem> = albumList.toList()

    override suspend fun loadAlbumPhotos(albumId: Uuid): List<PhotoItem> =
        membership[albumId].orEmpty().toList()

    override suspend fun loadPhoto(fileId: Uuid): PhotoItem? =
        membership.values.flatten().firstOrNull { it.fileId == fileId }

    override suspend fun loadNewestAlbumPhoto(albumId: Uuid): PhotoItem? =
        membership[albumId]?.firstOrNull()

    override suspend fun sync() {
        syncCount++
    }

    override suspend fun createAlbum(name: String, description: String?): AlbumItem {
        val album = AlbumItem(
            fileId = Uuid.random(),
            albumId = Uuid.random(),
            name = name,
            coverFileId = null,
            description = description,
        )
        albumList.add(0, album)
        membership[album.albumId] = mutableListOf()
        return album
    }

    override suspend fun renameAlbum(album: AlbumItem, newName: String): AlbumItem =
        replace(album.copy(name = newName))

    override suspend fun setCover(album: AlbumItem, photoFileId: Uuid): AlbumItem =
        replace(album.copy(coverFileId = photoFileId))

    override suspend fun deleteAlbum(album: AlbumItem): Boolean =
        albumList.removeAll { it.fileId == album.fileId }

    override suspend fun addPhotos(albumTag: Uuid, fileIds: List<Uuid>): AlbumMembershipResult {
        val target = membership.getOrPut(albumTag) { mutableListOf() }
        val pool = membership.values.flatten()
        fileIds.forEach { id ->
            pool.firstOrNull { it.fileId == id }?.let { if (it !in target) target.add(it) }
        }
        return AlbumMembershipResult(succeeded = fileIds)
    }

    override suspend fun removePhotos(albumTag: Uuid, fileIds: List<Uuid>): AlbumMembershipResult {
        membership[albumTag]?.removeAll { it.fileId in fileIds }
        return AlbumMembershipResult(succeeded = fileIds)
    }

    private fun replace(album: AlbumItem): AlbumItem {
        val index = albumList.indexOfFirst { it.fileId == album.fileId }
        if (index >= 0) albumList[index] = album
        return album
    }
}
