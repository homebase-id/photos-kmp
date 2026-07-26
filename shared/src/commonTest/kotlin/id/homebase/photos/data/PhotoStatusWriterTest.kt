package id.homebase.photos.data

import id.homebase.api.client.ClientException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.ProblemDetails
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.DeleteFileResult
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.upload.CreateFileResult
import id.homebase.api.client.drives.upload.UpdateFileByFileIdRequest
import id.homebase.api.client.drives.upload.UpdateFileResult
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.SecureByteArray
import id.homebase.photos.PhotoConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * [PhotoStatusWriter]'s COMPOSITION — favorite tag toggling and archivalStatus header patches,
 * built out of the SAME [AlbumDriveGateway] seam + [patchHeaderWithRetry] retry loop as album
 * membership. Schema field parity is [AlbumWriteSchemaTest]'s job; this pins the wiring.
 */
class PhotoStatusWriterTest {

    /** Records every drive call and hands back scripted headers/results. Mirrors AlbumWriterTest's fake. */
    private class FakeAlbumDriveGateway : AlbumDriveGateway {
        val calls = mutableListOf<String>()
        val updates = mutableListOf<UpdateFileByFileIdRequest>()
        val headers = mutableMapOf<Uuid, HomebaseFile>()

        /** Per-file remaining VersionTagMismatch throws before an update finally succeeds. */
        val conflictsRemaining = mutableMapOf<Uuid, Int>()
        var onFetch: (() -> Unit)? = null

        override suspend fun getFileHeader(driveId: Uuid, fileId: Uuid): HomebaseFile? {
            calls += "getFileHeader:$fileId"
            onFetch?.invoke()
            return headers[fileId]
        }

        override suspend fun uploadFile(request: UploadFileRequest): CreateFileResult {
            error("PhotoStatusWriter never uploads a new file")
        }

        override suspend fun updateFileByFileId(request: UpdateFileByFileIdRequest): UpdateFileResult {
            calls += "updateFileByFileId:${request.fileId}"
            updates += request
            val remaining = conflictsRemaining[request.fileId] ?: 0
            if (remaining > 0) {
                conflictsRemaining[request.fileId] = remaining - 1
                throw ClientException(
                    status = 400,
                    errorCode = OdinClientErrorCode.VersionTagMismatch,
                    message = "versionTag mismatch",
                    correlationId = null,
                    problem = ProblemDetails(),
                )
            }
            return UpdateFileResult(
                fileId = request.fileId,
                driveId = request.driveId,
                newVersionTag = Uuid.random(),
            )
        }

        override suspend fun softDeleteFile(driveId: Uuid, fileId: Uuid): DeleteFileResult {
            error("PhotoStatusWriter never deletes a file")
        }
    }

    private fun photoHeader(
        fileId: Uuid,
        tags: List<Uuid>? = null,
        versionTag: Uuid = Uuid.random(),
        archivalStatus: ArchivalStatus? = ArchivalStatus.None,
    ) = HomebaseFile(
        fileId = fileId,
        driveId = DRIVE_ID,
        serverFileIsEncrypted = true,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader(iv = ByteArray(16) { 3 }, aesKey = SecureByteArray(ByteArray(16) { 7 })),
        fileMetadata = FileMetadata(
            isEncrypted = true,
            versionTag = versionTag,
            appData = AppFileMetaData(
                uniqueId = Uuid.random(),
                tags = tags,
                fileType = PhotoConfig.PHOTO_FILE_TYPE,
                dataType = PhotoConfig.PHOTO_DATA_TYPE,
                userDate = 1_699_000_000_000L,
                content = """{"originalFileName":"IMG_1.jpg"}""",
                archivalStatus = archivalStatus,
            ),
        ),
        serverMetadata = ServerMetadata(),
    )

    // --- setFavorite -----------------------------------------------------------------------

    @Test
    fun setFavorite_true_addsTheFavoriteTagAndPreservesOthers() = runTest {
        val drive = FakeAlbumDriveGateway()
        val photoId = Uuid.random()
        drive.headers[photoId] = photoHeader(photoId, tags = listOf(OTHER_TAG))
        val writer = PhotoStatusWriter(DRIVE_ID, drive)

        val result = writer.setFavorite(photoId, favorite = true)

        assertTrue(result)
        val sent = drive.updates.single()
        assertEquals(listOf(OTHER_TAG, PhotoConfig.FAVORITE_TAG), sent.metadata.appData.tags)
    }

    @Test
    fun setFavorite_false_dropsOnlyTheFavoriteTag() = runTest {
        val drive = FakeAlbumDriveGateway()
        val photoId = Uuid.random()
        drive.headers[photoId] = photoHeader(photoId, tags = listOf(OTHER_TAG, PhotoConfig.FAVORITE_TAG))
        val writer = PhotoStatusWriter(DRIVE_ID, drive)

        val result = writer.setFavorite(photoId, favorite = false)

        assertTrue(result)
        assertEquals(listOf(OTHER_TAG), drive.updates.single().metadata.appData.tags)
    }

    @Test
    fun setFavorite_alreadyInDesiredState_isIdempotentAndSkipsTheWrite() = runTest {
        val drive = FakeAlbumDriveGateway()
        val photoId = Uuid.random()
        drive.headers[photoId] = photoHeader(photoId, tags = listOf(PhotoConfig.FAVORITE_TAG))
        val writer = PhotoStatusWriter(DRIVE_ID, drive)

        val result = writer.setFavorite(photoId, favorite = true)

        assertTrue(result)
        assertTrue(drive.updates.isEmpty(), "already favorited — no header patch needed")
    }

    @Test
    fun setFavorite_versionConflict_retriesFromTheRefetchedHeader() = runTest {
        val drive = FakeAlbumDriveGateway()
        val photoId = Uuid.random()
        val v1 = Uuid.random()
        val v2 = Uuid.random()
        drive.headers[photoId] = photoHeader(photoId, tags = listOf(OTHER_TAG), versionTag = v1)
        drive.conflictsRemaining[photoId] = 1
        var fetches = 0
        drive.onFetch = {
            if (fetches++ == 0) {
                drive.headers[photoId] = photoHeader(photoId, tags = listOf(OTHER_TAG, SECOND_TAG), versionTag = v2)
            }
        }
        val writer = PhotoStatusWriter(DRIVE_ID, drive)

        val result = writer.setFavorite(photoId, favorite = true)

        assertTrue(result)
        assertEquals(2, drive.updates.size)
        val retry = drive.updates.last()
        assertEquals(listOf(OTHER_TAG, SECOND_TAG, PhotoConfig.FAVORITE_TAG), retry.metadata.appData.tags)
        assertEquals(v2, retry.metadata.versionTag)
    }

    @Test
    fun setFavorite_missingPhoto_isToleratedAsSuccess() = runTest {
        val drive = FakeAlbumDriveGateway()
        val writer = PhotoStatusWriter(DRIVE_ID, drive)

        assertTrue(writer.setFavorite(Uuid.random(), favorite = true))
    }

    // --- setArchivalStatus -------------------------------------------------------------------

    @Test
    fun setArchivalStatus_patchesArchivalStatusAndCarriesEverythingElse() = runTest {
        val drive = FakeAlbumDriveGateway()
        val photoId = Uuid.random()
        drive.headers[photoId] = photoHeader(photoId, tags = listOf(OTHER_TAG))
        val writer = PhotoStatusWriter(DRIVE_ID, drive)

        val result = writer.setArchivalStatus(listOf(photoId), ArchivalStatus.Archived)

        assertEquals(listOf(photoId), result.succeeded)
        assertTrue(result.isCompleteSuccess)
        val sent = drive.updates.single()
        assertEquals(ArchivalStatus.Archived, sent.metadata.appData.archivalStatus)
        assertEquals(listOf(OTHER_TAG), sent.metadata.appData.tags, "tags are carried, not touched")
    }

    @Test
    fun setArchivalStatus_alreadyAtTargetStatus_isSkippedAsSucceeded() = runTest {
        val drive = FakeAlbumDriveGateway()
        val photoId = Uuid.random()
        drive.headers[photoId] = photoHeader(photoId, archivalStatus = ArchivalStatus.Archived)
        val writer = PhotoStatusWriter(DRIVE_ID, drive)

        val result = writer.setArchivalStatus(listOf(photoId), ArchivalStatus.Archived)

        assertEquals(listOf(photoId), result.succeeded)
        assertTrue(drive.updates.isEmpty(), "already archived — no header patch needed")
    }

    @Test
    fun setArchivalStatus_versionConflict_retriesFromTheRefetchedHeader() = runTest {
        val drive = FakeAlbumDriveGateway()
        val photoId = Uuid.random()
        val v1 = Uuid.random()
        val v2 = Uuid.random()
        drive.headers[photoId] = photoHeader(photoId, versionTag = v1)
        drive.conflictsRemaining[photoId] = 1
        drive.onFetch = { drive.headers[photoId] = photoHeader(photoId, versionTag = v2) }
        val writer = PhotoStatusWriter(DRIVE_ID, drive)

        val result = writer.setArchivalStatus(listOf(photoId), ArchivalStatus.Removed)

        assertEquals(listOf(photoId), result.succeeded)
        assertEquals(2, drive.updates.size)
        assertEquals(v2, drive.updates.last().metadata.versionTag)
    }

    @Test
    fun setArchivalStatus_partialFailure_splitsSucceededAndFailed() = runTest {
        val drive = FakeAlbumDriveGateway()
        val alive = Uuid.random()
        val gone = Uuid.random()
        drive.headers[alive] = photoHeader(alive)
        // `gone` has no scripted header — fetch returns null → NotFound → tolerated as success.
        // `stubborn` conflicts on every attempt, exhausting the retry budget → a real failure.
        val stubborn = Uuid.random()
        drive.headers[stubborn] = photoHeader(stubborn)
        drive.conflictsRemaining[stubborn] = Int.MAX_VALUE
        val writer = PhotoStatusWriter(DRIVE_ID, drive)

        val result = writer.setArchivalStatus(listOf(alive, stubborn, gone), ArchivalStatus.Archived)

        assertEquals(listOf(alive, gone), result.succeeded, "gone is tolerated — goal state already holds")
        assertEquals(listOf(stubborn), result.failed)
        assertTrue(!result.isCompleteSuccess)
    }

    private companion object {
        val DRIVE_ID: Uuid = Uuid.random()
        val OTHER_TAG: Uuid = Uuid.random()
        val SECOND_TAG: Uuid = Uuid.random()
    }
}
