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
 * Writes follow the official Odin Photos schema via [AlbumWriteSchema]: a header-only
 * upload for create, header-only patches for rename/cover/membership, soft-delete for
 * delete. Nothing here touches payloads.
 */
class AlbumsRepositoryImpl(
    private val driveId: Uuid,
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val driveQueryProvider: DriveQueryProvider,
    private val driveFileProvider: DriveFileProvider,
    private val driveUploadProvider: DriveUploadProvider,
    private val driveSyncManager: DriveSyncManager,
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

    override suspend fun createAlbum(name: String, description: String?): AlbumItem {
        val albumTag = newAlbumTag()
        val request = AlbumWriteSchema.albumCreateRequest(driveId, albumTag, name, description)
        val result = driveUploadProvider.uploadFile(request)
            ?: error("Album upload returned no result")
        return AlbumItem(
            fileId = result.fileId,
            albumId = albumTag,
            name = name,
            coverFileId = null,
            description = description?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun renameAlbum(album: AlbumItem, newName: String): AlbumItem {
        patchAlbumContentFields(album, mapOf(FIELD_NAME to newName))
        return album.copy(name = newName)
    }

    override suspend fun setCover(album: AlbumItem, photoFileId: Uuid): AlbumItem {
        patchAlbumContentFields(album, mapOf(FIELD_COVER to photoFileId.toString()))
        return album.copy(coverFileId = photoFileId)
    }

    override suspend fun deleteAlbum(album: AlbumItem): Boolean {
        val result = driveFileProvider.softDeleteFile(driveId, album.fileId)
        // Not-found counts as deleted — the goal state (album gone) already holds.
        return result.localFileDeleted || result.localFileNotFound
    }

    override suspend fun addPhotos(albumTag: Uuid, fileIds: List<Uuid>): AlbumMembershipResult =
        retagPhotos(fileIds) { AlbumWriteSchema.withTag(it, albumTag) }

    override suspend fun removePhotos(albumTag: Uuid, fileIds: List<Uuid>): AlbumMembershipResult =
        retagPhotos(fileIds) { AlbumWriteSchema.withoutTag(it, albumTag) }

    /** One header patch per photo — the API has no batch header update. */
    private suspend fun retagPhotos(
        fileIds: List<Uuid>,
        newTags: (List<Uuid>?) -> List<Uuid>,
    ): AlbumMembershipResult {
        val succeeded = mutableListOf<Uuid>()
        val failed = mutableListOf<Uuid>()
        for (fileId in fileIds.distinct()) {
            val outcome = patchHeaderWithRetry(
                fileId = fileId,
                fetch = { driveFileProvider.getFileHeader(driveId, it) },
                send = { existing ->
                    val appData = AlbumWriteSchema.carryOverAppData(
                        existing.fileMetadata.appData,
                        tags = newTags(existing.fileMetadata.appData.tags),
                    )
                    requireNotNull(
                        driveUploadProvider.updateFileByFileId(
                            AlbumWriteSchema.headerUpdateRequest(driveId, existing, appData),
                        ),
                    ) { "Tag update for $fileId returned no result" }
                },
            )
            when (outcome) {
                is HeaderPatchOutcome.Failed -> {
                    Logger.w(tag = TAG) { "retag $fileId failed: ${outcome.message}" }
                    failed += fileId
                }
                // Not-found is tolerated — the photo is gone, so it isn't in the album either.
                else -> succeeded += fileId
            }
        }
        return AlbumMembershipResult(succeeded = succeeded, failed = failed)
    }

    /** Content-only header patch of the album FILE; tags/uniqueId/fileType are carried untouched. */
    private suspend fun patchAlbumContentFields(album: AlbumItem, edits: Map<String, String?>) {
        val outcome = patchHeaderWithRetry(
            fileId = album.fileId,
            fetch = { driveFileProvider.getFileHeader(driveId, it) },
            send = { existing ->
                val appData = AlbumWriteSchema.carryOverAppData(
                    existing.fileMetadata.appData,
                    content = patchAlbumContent(
                        existing = existing.fileMetadata.appData.content,
                        tag = album.albumId,
                        edits = edits,
                    ),
                )
                requireNotNull(
                    driveUploadProvider.updateFileByFileId(
                        AlbumWriteSchema.headerUpdateRequest(driveId, existing, appData),
                    ),
                ) { "Album update returned no result" }
            },
        )
        if (outcome is HeaderPatchOutcome.Failed) error(outcome.message)
        if (outcome is HeaderPatchOutcome.NotFound) error("Album no longer exists")
    }

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
