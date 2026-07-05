package id.homebase.photos.data

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.photos.PhotoConfig
import id.homebase.photos.PhotoQueries
import id.homebase.photos.domain.AlbumItem
import id.homebase.photos.domain.PhotoItem
import kotlin.uuid.Uuid

/**
 * Real albums repository: album files come off the synced local DriveMainIndex
 * (same paged read [PhotosRepositoryImpl] uses, just `fileType 900`); album
 * MEMBERSHIP is a server queryBatch on the album's tag — membership tags are
 * not indexed locally.
 */
class AlbumsRepositoryImpl(
    private val driveId: Uuid,
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val driveQueryProvider: DriveQueryProvider,
) : AlbumsRepository {

    override suspend fun loadAlbums(): List<AlbumItem> {
        val identityId = activeIdentity() ?: return emptyList()
        val files = databaseManager.driveMainIndex.selectPhotosPage(
            identityId = identityId,
            driveId = driveId,
            fileType = PhotoConfig.ALBUM_FILE_TYPE.toLong(),
            beforeUserDate = Long.MAX_VALUE,
            limit = 500, // ponytail: 500-album ceiling, page when someone actually has more
        )
        return files
            .filterNot { it.isSoftDeleted() }
            .mapNotNull(AlbumMapper::fromHomebaseFile)
    }

    override suspend fun loadAlbumPhotos(albumId: Uuid): List<PhotoItem> =
        driveQueryProvider.queryBatch(driveId, PhotoQueries.albumQuery(albumId))
            .searchResults
            .filterNot { it.isSoftDeleted() }
            .map(PhotoMapper::fromHomebaseFile)

    private suspend fun activeIdentity(): Uuid? =
        credentialsManager.getActiveCredentials()?.getIdentityId()
}
