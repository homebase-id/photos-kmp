package id.homebase.photos.data

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.photos.PhotoConfig
import id.homebase.photos.PhotoQueries
import id.homebase.photos.domain.AlbumItem
import id.homebase.photos.domain.PhotoItem
import kotlin.uuid.Uuid

/**
 * Real albums repository: album files come off the synced local DriveMainIndex
 * (same paged read [PhotosRepositoryImpl] uses, just `fileType 400`); album
 * MEMBERSHIP is a server queryBatch on the album's tag — membership tags are
 * not indexed locally.
 *
 * Reads live here; every WRITE delegates to [AlbumWriter], which composes the official Odin
 * Photos schema (header-only upload for create, header-only patches for rename/cover/membership,
 * soft-delete for delete). Nothing here touches payloads.
 */
class AlbumsRepositoryImpl(
    private val driveId: Uuid,
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val driveQueryProvider: DriveQueryProvider,
    driveFileProvider: DriveFileProvider,
    driveUploadProvider: DriveUploadProvider,
    private val driveSyncManager: DriveSyncManager,
) : AlbumsRepository {

    private val writer = AlbumWriter(
        driveId = driveId,
        drive = OdinAlbumDriveGateway(driveFileProvider, driveUploadProvider),
    )

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
        queryAlbum(albumId, PhotoQueries.ALBUM_PAGE_SIZE)

    // A pinned cover is on the same drive and the same sync, so the local index answers it
    // without a server round-trip.
    override suspend fun loadPhoto(fileId: Uuid): PhotoItem? {
        val file = localFile(fileId) ?: return null
        return if (file.isSoftDeleted()) null else PhotoMapper.fromHomebaseFile(file)
    }

    override suspend fun loadNewestAlbumPhoto(albumId: Uuid): PhotoItem? =
        queryAlbum(albumId, maxRecords = 1).firstOrNull()

    override suspend fun sync() {
        driveSyncManager.ensureMandatoryMounted()
        driveSyncManager.start()   // idempotent; no-op without credentials
        driveSyncManager.syncAll() // suspends until own drives finish
    }

    override suspend fun createAlbum(name: String, description: String?): AlbumItem =
        writer.create(name, description)

    override suspend fun renameAlbum(album: AlbumItem, newName: String): AlbumItem =
        writer.rename(album, newName)

    override suspend fun setCover(album: AlbumItem, photoFileId: Uuid): AlbumItem =
        writer.setCover(album, photoFileId)

    override suspend fun deleteAlbum(album: AlbumItem): Boolean = writer.delete(album)

    override suspend fun addPhotos(albumTag: Uuid, fileIds: List<Uuid>): AlbumMembershipResult =
        writer.addPhotos(albumTag, fileIds)

    override suspend fun removePhotos(albumTag: Uuid, fileIds: List<Uuid>): AlbumMembershipResult =
        writer.removePhotos(albumTag, fileIds)

    private suspend fun queryAlbum(albumId: Uuid, maxRecords: Int): List<PhotoItem> =
        driveQueryProvider.queryBatch(driveId, PhotoQueries.albumQuery(albumId, maxRecords))
            .searchResults
            .filterNot { it.isSoftDeleted() }
            .map(PhotoMapper::fromHomebaseFile)

    private suspend fun localFile(fileId: Uuid): HomebaseFile? {
        val identityId = activeIdentity() ?: return null
        val row = databaseManager.driveMainIndex
            .selectByIdentityAndDriveAndFile(identityId, driveId, fileId) ?: return null
        return try {
            OdinSystemSerializer.deserialize<HomebaseFile>(row.jsonHeader)
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "localFile($fileId) deserialize failed: ${e.message}" }
            null
        }
    }

    private suspend fun activeIdentity(): Uuid? =
        credentialsManager.getActiveCredentials()?.getIdentityId()

    private companion object {
        const val TAG = "AlbumsRepository"
    }
}
