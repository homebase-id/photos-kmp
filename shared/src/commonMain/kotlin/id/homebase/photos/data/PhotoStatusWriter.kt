package id.homebase.photos.data

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.photos.PhotoConfig
import kotlin.uuid.Uuid

/** Per-file result of an archivalStatus write — lets the UI report "3 of 5 archived". */
data class PhotoStatusResult(
    val succeeded: List<Uuid> = emptyList(),
    val failed: List<Uuid> = emptyList(),
) {
    val isCompleteSuccess: Boolean get() = failed.isEmpty()
}

/**
 * Favorite / archive / trash writes, composed out of the SAME [AlbumDriveGateway] seam +
 * [patchHeaderWithRetry] retry loop as album membership — both are header-only patches of a
 * PHOTO file (favorite = a tag, archive/trash = `appData.archivalStatus`). Not album-specific,
 * despite the gateway's name.
 */
internal class PhotoStatusWriter(
    private val driveId: Uuid,
    private val drive: AlbumDriveGateway,
) {

    /** Idempotent: already-favorited (or already-not) is a success with no header patch sent. */
    suspend fun setFavorite(fileId: Uuid, favorite: Boolean): Boolean {
        val outcome = patchHeaderWithRetry(
            fileId = fileId,
            fetch = { drive.getFileHeader(driveId, it) },
            send = { existing ->
                val tags = existing.fileMetadata.appData.tags
                val isFavorite = tags?.contains(PhotoConfig.FAVORITE_TAG) == true
                if (isFavorite != favorite) { // already in the desired state → skip the patch entirely
                    val newTags = if (favorite) {
                        AlbumWriteSchema.withTag(tags, PhotoConfig.FAVORITE_TAG)
                    } else {
                        AlbumWriteSchema.withoutTag(tags, PhotoConfig.FAVORITE_TAG)
                    }
                    val appData = AlbumWriteSchema.carryOverAppData(existing.fileMetadata.appData, tags = newTags)
                    requireNotNull(
                        drive.updateFileByFileId(AlbumWriteSchema.headerUpdateRequest(driveId, existing, appData)),
                    ) { "Favorite update for $fileId returned no result" }
                }
            },
        )
        // Not-found is tolerated — favoriting a gone file is moot, the goal state already holds.
        return outcome !is HeaderPatchOutcome.Failed
    }

    /** Idempotent per file: one already at [status] counts as succeeded with no header patch sent. */
    suspend fun setArchivalStatus(fileIds: List<Uuid>, status: ArchivalStatus): PhotoStatusResult {
        val succeeded = mutableListOf<Uuid>()
        val failed = mutableListOf<Uuid>()
        for (fileId in fileIds.distinct()) {
            val outcome = patchHeaderWithRetry(
                fileId = fileId,
                fetch = { drive.getFileHeader(driveId, it) },
                send = { existing ->
                    val current = existing.fileMetadata.appData.archivalStatus ?: ArchivalStatus.None
                    if (current != status) { // already at the target status → skip the patch entirely
                        val appData = AlbumWriteSchema.carryOverAppData(
                            existing.fileMetadata.appData,
                            archivalStatus = status,
                        )
                        requireNotNull(
                            drive.updateFileByFileId(AlbumWriteSchema.headerUpdateRequest(driveId, existing, appData)),
                        ) { "Status update for $fileId returned no result" }
                    }
                },
            )
            when (outcome) {
                is HeaderPatchOutcome.Failed -> {
                    Logger.w(tag = TAG) { "setArchivalStatus $fileId failed: ${outcome.message}" }
                    failed += fileId
                }
                // Not-found is tolerated — the photo is gone, so its status is moot.
                else -> succeeded += fileId
            }
        }
        return PhotoStatusResult(succeeded = succeeded, failed = failed)
    }

    private companion object {
        const val TAG = "PhotoStatusWriter"
    }
}
