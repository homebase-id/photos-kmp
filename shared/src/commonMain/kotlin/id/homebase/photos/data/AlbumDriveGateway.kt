package id.homebase.photos.data

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DeleteFileResult
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.upload.CreateFileResult
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.UpdateFileByFileIdRequest
import id.homebase.api.client.drives.upload.UpdateFileResult
import id.homebase.api.client.drives.upload.UploadFileRequest
import kotlin.uuid.Uuid

/**
 * The four drive calls album writes need. A seam, not an abstraction: the copied providers are
 * final classes wired to a live HttpClient, so this is what lets commonTest assert WHICH request
 * a mutation actually puts on the wire.
 */
internal interface AlbumDriveGateway {
    suspend fun getFileHeader(driveId: Uuid, fileId: Uuid): HomebaseFile?
    suspend fun uploadFile(request: UploadFileRequest): CreateFileResult?
    suspend fun updateFileByFileId(request: UpdateFileByFileIdRequest): UpdateFileResult?
    suspend fun softDeleteFile(driveId: Uuid, fileId: Uuid): DeleteFileResult
}

internal class OdinAlbumDriveGateway(
    private val files: DriveFileProvider,
    private val uploads: DriveUploadProvider,
) : AlbumDriveGateway {
    override suspend fun getFileHeader(driveId: Uuid, fileId: Uuid): HomebaseFile? =
        files.getFileHeader(driveId, fileId)

    override suspend fun uploadFile(request: UploadFileRequest): CreateFileResult? =
        uploads.uploadFile(request)

    override suspend fun updateFileByFileId(request: UpdateFileByFileIdRequest): UpdateFileResult? =
        uploads.updateFileByFileId(request)

    override suspend fun softDeleteFile(driveId: Uuid, fileId: Uuid): DeleteFileResult =
        files.softDeleteFile(driveId, fileId)
}
