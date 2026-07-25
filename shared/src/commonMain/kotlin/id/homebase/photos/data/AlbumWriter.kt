package id.homebase.photos.data

import co.touchlab.kermit.Logger
import id.homebase.photos.domain.AlbumItem
import kotlin.uuid.Uuid

/**
 * Every album MUTATION, composed out of [AlbumWriteSchema]'s pure builders over an
 * [AlbumDriveGateway]. Split out of [AlbumsRepositoryImpl] so the composition itself — which
 * header a patch is built from, and which calls a mutation is allowed to make — is testable
 * without a database or an HTTP client.
 */
internal class AlbumWriter(
    private val driveId: Uuid,
    private val drive: AlbumDriveGateway,
) {

    suspend fun create(name: String, description: String?): AlbumItem {
        val albumTag = newAlbumTag()
        val request = AlbumWriteSchema.albumCreateRequest(driveId, albumTag, name, description)
        val result = drive.uploadFile(request) ?: error("Album upload returned no result")
        return AlbumItem(
            fileId = result.fileId,
            albumId = albumTag,
            name = name,
            coverFileId = null,
            description = description?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun rename(album: AlbumItem, newName: String): AlbumItem {
        patchContent(album, mapOf(FIELD_NAME to newName))
        return album.copy(name = newName)
    }

    suspend fun setCover(album: AlbumItem, photoFileId: Uuid): AlbumItem {
        patchContent(album, mapOf(FIELD_COVER to photoFileId.toString()))
        return album.copy(coverFileId = photoFileId)
    }

    /** The album FILE and nothing else — member photos keep their (now dangling) tag. */
    suspend fun delete(album: AlbumItem): Boolean {
        val result = drive.softDeleteFile(driveId, album.fileId)
        // Not-found counts as deleted — the goal state (album gone) already holds.
        return result.localFileDeleted || result.localFileNotFound
    }

    suspend fun addPhotos(albumTag: Uuid, fileIds: List<Uuid>): AlbumMembershipResult =
        retagPhotos(fileIds) { AlbumWriteSchema.withTag(it, albumTag) }

    suspend fun removePhotos(albumTag: Uuid, fileIds: List<Uuid>): AlbumMembershipResult =
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
                fetch = { drive.getFileHeader(driveId, it) },
                send = { existing ->
                    // Tags come off the JUST-FETCHED header, never a cached/index copy — a stale
                    // list would drop whatever another writer added in the meantime.
                    val appData = AlbumWriteSchema.carryOverAppData(
                        existing.fileMetadata.appData,
                        tags = newTags(existing.fileMetadata.appData.tags),
                    )
                    requireNotNull(
                        drive.updateFileByFileId(
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
    private suspend fun patchContent(album: AlbumItem, edits: Map<String, String?>) {
        val outcome = patchHeaderWithRetry(
            fileId = album.fileId,
            fetch = { drive.getFileHeader(driveId, it) },
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
                    drive.updateFileByFileId(
                        AlbumWriteSchema.headerUpdateRequest(driveId, existing, appData),
                    ),
                ) { "Album update returned no result" }
            },
        )
        if (outcome is HeaderPatchOutcome.Failed) error(outcome.message)
        if (outcome is HeaderPatchOutcome.NotFound) error("Album no longer exists")
    }

    private companion object {
        const val TAG = "AlbumWriter"
    }
}
