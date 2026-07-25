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
import id.homebase.api.client.drives.files.DeleteFileResult
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.upload.CreateFileResult
import id.homebase.api.client.drives.upload.UpdateFileByFileIdRequest
import id.homebase.api.client.drives.upload.UpdateFileResult
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.SecureByteArray
import id.homebase.photos.PhotoConfig
import id.homebase.photos.domain.AlbumItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * [AlbumWriter]'s COMPOSITION — which drive calls a mutation is allowed to make, and which
 * header each patch is built from. The schema of an individual request is pinned by
 * [AlbumWriteSchemaTest]; this pins the wiring around it.
 */
class AlbumWriterTest {

    /** Records every drive call and hands back scripted headers/results. */
    private class FakeAlbumDriveGateway : AlbumDriveGateway {
        val calls = mutableListOf<String>()
        val updates = mutableListOf<UpdateFileByFileIdRequest>()
        val uploads = mutableListOf<UploadFileRequest>()

        /** The server's current header per file — a test can swap it mid-flight. */
        val headers = mutableMapOf<Uuid, HomebaseFile>()

        /** Fail this many update calls with VersionTagMismatch before letting one through. */
        var conflictsBeforeSuccess = 0
        var onFetch: (() -> Unit)? = null
        var deleteResult: (Uuid) -> DeleteFileResult = { DeleteFileResult(it, true, false) }

        /** The fileId the server assigns to a created album. */
        val createdFileId: Uuid = Uuid.random()

        override suspend fun getFileHeader(driveId: Uuid, fileId: Uuid): HomebaseFile? {
            calls += "getFileHeader:$fileId"
            onFetch?.invoke()
            return headers[fileId]
        }

        override suspend fun uploadFile(request: UploadFileRequest): CreateFileResult {
            calls += "uploadFile"
            uploads += request
            return CreateFileResult(
                fileId = createdFileId,
                driveId = request.driveId,
                newVersionTag = Uuid.random(),
            )
        }

        override suspend fun updateFileByFileId(request: UpdateFileByFileIdRequest): UpdateFileResult {
            calls += "updateFileByFileId:${request.fileId}"
            updates += request
            if (conflictsBeforeSuccess > 0) {
                conflictsBeforeSuccess--
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
            calls += "softDeleteFile:$fileId"
            return deleteResult(fileId)
        }
    }

    private fun photoHeader(fileId: Uuid, tags: List<Uuid>?, versionTag: Uuid) = HomebaseFile(
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
            ),
        ),
        serverMetadata = ServerMetadata(),
    )

    private fun albumHeader(fileId: Uuid) = HomebaseFile(
        fileId = fileId,
        driveId = DRIVE_ID,
        serverFileIsEncrypted = true,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader(iv = ByteArray(16) { 1 }, aesKey = SecureByteArray(ByteArray(16) { 9 })),
        fileMetadata = FileMetadata(
            isEncrypted = true,
            versionTag = Uuid.random(),
            appData = AppFileMetaData(
                uniqueId = ALBUM_TAG,
                tags = emptyList(),
                fileType = PhotoConfig.ALBUM_FILE_TYPE,
                content = """{"name":"Trip","tag":"$ALBUM_TAG_HEX"}""",
            ),
        ),
        serverMetadata = ServerMetadata(),
    )

    private fun album(fileId: Uuid = ALBUM_FILE_ID) =
        AlbumItem(fileId = fileId, albumId = ALBUM_TAG, name = "Trip", coverFileId = null)

    // --- delete ------------------------------------------------------------------------------

    @Test
    fun deleteAlbum_softDeletesTheAlbumFileAndNothingElse() = runTest {
        val drive = FakeAlbumDriveGateway()
        val photoId = Uuid.random()
        drive.headers[ALBUM_FILE_ID] = albumHeader(ALBUM_FILE_ID)
        drive.headers[photoId] = photoHeader(photoId, listOf(ALBUM_TAG), Uuid.random())
        val writer = AlbumWriter(DRIVE_ID, drive)

        assertTrue(writer.delete(album()))

        // The whole point: member photos are never touched — no header patch, no delete fan-out.
        assertEquals(listOf("softDeleteFile:$ALBUM_FILE_ID"), drive.calls)
        assertTrue(drive.updates.isEmpty(), "member photos must keep their (dangling) tag")
        assertTrue(drive.uploads.isEmpty())
    }

    @Test
    fun deleteAlbum_notFoundCountsAsDeleted() = runTest {
        val drive = FakeAlbumDriveGateway()
        drive.deleteResult = { DeleteFileResult(it, false, true) }

        assertTrue(AlbumWriter(DRIVE_ID, drive).delete(album()))
    }

    @Test
    fun deleteAlbum_serverRefusalIsReported() = runTest {
        val drive = FakeAlbumDriveGateway()
        drive.deleteResult = { DeleteFileResult(it, false, false) }

        assertEquals(false, AlbumWriter(DRIVE_ID, drive).delete(album()))
    }

    // --- membership --------------------------------------------------------------------------

    @Test
    fun addPhotos_patchesTheFreshHeadersTags_notACachedCopy() = runTest {
        val drive = FakeAlbumDriveGateway()
        val photoId = Uuid.random()
        val serverVersionTag = Uuid.random()
        // The server's header carries a tag our caller's (stale) copy never saw.
        drive.headers[photoId] = photoHeader(photoId, listOf(OTHER_ALBUM), serverVersionTag)
        val writer = AlbumWriter(DRIVE_ID, drive)

        val result = writer.addPhotos(ALBUM_TAG, listOf(photoId))

        assertEquals(listOf(photoId), result.succeeded)
        // Fetch strictly before patch — the patch is built from what came back.
        assertEquals(listOf("getFileHeader:$photoId", "updateFileByFileId:$photoId"), drive.calls)
        val sent = drive.updates.single()
        assertEquals(listOf(OTHER_ALBUM, ALBUM_TAG), sent.metadata.appData.tags)
        assertEquals(serverVersionTag, sent.metadata.versionTag)
        assertEquals(photoId, sent.fileId)
        // ...and the rest of the fresh header rode along untouched.
        val fresh = drive.headers[photoId]!!.fileMetadata.appData
        assertEquals(fresh.uniqueId, sent.metadata.appData.uniqueId)
        assertEquals(fresh.userDate, sent.metadata.appData.userDate)
        assertEquals(fresh.fileType, sent.metadata.appData.fileType)
    }

    @Test
    fun addPhotos_afterAVersionConflict_rebuildsFromTheREFETCHEDHeader() = runTest {
        val drive = FakeAlbumDriveGateway()
        val photoId = Uuid.random()
        val v1 = Uuid.random()
        val v2 = Uuid.random()
        drive.headers[photoId] = photoHeader(photoId, listOf(OTHER_ALBUM), v1)
        drive.conflictsBeforeSuccess = 1
        // Between our fetch and our patch, another writer added a second album tag.
        var fetches = 0
        drive.onFetch = {
            if (fetches++ == 0) {
                drive.headers[photoId] = photoHeader(photoId, listOf(OTHER_ALBUM, SECOND_ALBUM), v2)
            }
        }
        val writer = AlbumWriter(DRIVE_ID, drive)

        val result = writer.addPhotos(ALBUM_TAG, listOf(photoId))

        assertEquals(listOf(photoId), result.succeeded)
        assertEquals(2, drive.updates.size)
        val retry = drive.updates.last()
        // The retry must carry the OTHER writer's tag — proof it re-read rather than re-sent.
        assertEquals(listOf(OTHER_ALBUM, SECOND_ALBUM, ALBUM_TAG), retry.metadata.appData.tags)
        assertEquals(v2, retry.metadata.versionTag)
        assertNotEquals(v1, retry.metadata.versionTag)
    }

    @Test
    fun addPhotos_missingPhotoIsToleratedAndTheBatchContinues() = runTest {
        val drive = FakeAlbumDriveGateway()
        val gone = Uuid.random()
        val alive = Uuid.random()
        drive.headers[alive] = photoHeader(alive, null, Uuid.random())
        val writer = AlbumWriter(DRIVE_ID, drive)

        val result = writer.addPhotos(ALBUM_TAG, listOf(gone, alive))

        assertEquals(listOf(gone, alive), result.succeeded)
        assertTrue(result.isCompleteSuccess)
        assertEquals(listOf(alive), drive.updates.map { it.fileId }, "only the live photo is patched")
    }

    @Test
    fun removePhotos_dropsOnlyTheAlbumTag() = runTest {
        val drive = FakeAlbumDriveGateway()
        val photoId = Uuid.random()
        drive.headers[photoId] = photoHeader(photoId, listOf(OTHER_ALBUM, ALBUM_TAG), Uuid.random())
        val writer = AlbumWriter(DRIVE_ID, drive)

        writer.removePhotos(ALBUM_TAG, listOf(photoId))

        assertEquals(listOf(OTHER_ALBUM), drive.updates.single().metadata.appData.tags)
        // Untag, never delete.
        assertTrue(drive.calls.none { it.startsWith("softDeleteFile") })
    }

    // --- create / rename ---------------------------------------------------------------------

    @Test
    fun create_uploadsOnceAndReturnsTheServerFileIdWithTheMintedTag() = runTest {
        val drive = FakeAlbumDriveGateway()

        val created = AlbumWriter(DRIVE_ID, drive).create("Roadtrip", null)

        assertEquals(listOf("uploadFile"), drive.calls)
        assertEquals(drive.createdFileId, created.fileId)
        assertEquals("Roadtrip", created.name)
        assertEquals(created.albumId, drive.uploads.single().metadata.appData.uniqueId)
    }

    @Test
    fun rename_patchesTheAlbumFileOnly() = runTest {
        val drive = FakeAlbumDriveGateway()
        drive.headers[ALBUM_FILE_ID] = albumHeader(ALBUM_FILE_ID)
        val writer = AlbumWriter(DRIVE_ID, drive)

        val renamed = writer.rename(album(), "Roadtrip")

        assertEquals("Roadtrip", renamed.name)
        assertEquals(ALBUM_TAG, renamed.albumId, "the album tag is never rewritten")
        assertEquals(
            listOf("getFileHeader:$ALBUM_FILE_ID", "updateFileByFileId:$ALBUM_FILE_ID"),
            drive.calls,
        )
        assertEquals(emptyList<Uuid>(), drive.updates.single().metadata.appData.tags)
    }

    private companion object {
        val DRIVE_ID: Uuid = Uuid.random()
        val ALBUM_FILE_ID: Uuid = Uuid.random()
        val OTHER_ALBUM: Uuid = Uuid.random()
        val SECOND_ALBUM: Uuid = Uuid.random()
        val ALBUM_TAG: Uuid = Uuid.parse("11111111-2222-3333-4444-555555555555")
        const val ALBUM_TAG_HEX = "11111111222233334444555555555555"
    }
}
