package id.homebase.photos.data

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.UpdateFileByFileIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.photos.PhotoConfig
import kotlin.uuid.Uuid

/**
 * Pure builders for every album WRITE, matching the official Odin Photos schema
 * (photo-app `AlbumProvider.ts` / `PhotoProvider.ts`; see the Batch C schema contract).
 * No I/O — the repository hands these to `DriveUploadProvider`.
 *
 * Album file: `fileType 400`, `uniqueId = tag`, `tags = []` (identity is the content `tag`,
 * NOT appData.tags), encrypted content, no payloads. Membership: the album tag lives in the
 * PHOTO's `appData.tags`, written as a header-only patch.
 */
internal object AlbumWriteSchema {

    /**
     * Re-projects a fetched header's appData onto the upload DTO, changing only [tags] and
     * [content]. A header update REPLACES appData wholesale, so any field missed here is
     * nulled server-side (a dropped `previewThumbnail` blanks the blur placeholder, a dropped
     * `userDate` destroys timeline position). Every [AppFileMetaData] field is carried.
     */
    fun carryOverAppData(
        existing: AppFileMetaData,
        tags: List<Uuid>? = existing.tags,
        content: String? = existing.content,
        archivalStatus: ArchivalStatus? = existing.archivalStatus,
    ): UploadAppFileMetaData = UploadAppFileMetaData(
        uniqueId = existing.uniqueId,
        tags = tags,
        fileType = existing.fileType,
        dataType = existing.dataType,
        userDate = existing.userDate,
        groupId = existing.groupId,
        archivalStatus = archivalStatus,
        content = content,
        previewThumbnail = existing.previewThumbnail,
    )

    /** appData for a fresh album file — plaintext content; the caller encrypts. */
    fun albumAppData(albumTag: Uuid, name: String, description: String? = null): UploadAppFileMetaData =
        UploadAppFileMetaData(
            uniqueId = albumTag,
            tags = emptyList(),
            fileType = PhotoConfig.ALBUM_FILE_TYPE,
            dataType = 0,
            content = newAlbumContentJson(name = name, tag = albumTag, description = description),
        )

    /** CREATE: header-only upload of a new album file. */
    suspend fun albumCreateRequest(
        driveId: Uuid,
        albumTag: Uuid,
        name: String,
        description: String? = null,
    ): UploadFileRequest {
        val keyHeader = KeyHeader.newRandom16()
        val metadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = true,
            accessControlList = AccessControlList(requiredSecurityGroup = SecurityGroupType.Owner.value),
            appData = albumAppData(albumTag, name, description),
        )
        return UploadFileRequest(
            driveId = driveId,
            keyHeader = keyHeader,
            metadata = metadata.encryptContent(keyHeader),
            payloads = emptyList(),
            thumbnails = emptyList(),
        )
    }

    /**
     * UPDATE: header-only patch of [existing] carrying [appData]. Keeps the file's aesKey
     * (payloads/thumbnails stay decryptable) and rotates the IV per revision; the empty
     * manifest ships the header alone, so server payloads are untouched. `versionTag` is
     * carried so a concurrent writer loses with `VersionTagMismatch` instead of clobbering.
     */
    suspend fun headerUpdateRequest(
        driveId: Uuid,
        existing: HomebaseFile,
        appData: UploadAppFileMetaData,
    ): UpdateFileByFileIdRequest {
        val encrypted = existing.serverFileIsEncrypted
        val keyHeader = if (encrypted) {
            KeyHeader(iv = ByteArrayUtil.getRndByteArray(16), aesKey = existing.keyHeader.aesKey)
        } else null
        val metadata = UploadFileMetadata(
            // Carried, not defaulted: a header update replaces wholesale, so hardcoding false
            // would silently un-distribute a shared photo the moment it joins an album.
            allowDistribution = existing.serverMetadata.allowDistribution,
            isEncrypted = encrypted,
            accessControlList = null, // omitted — the server keeps the file's existing ACL
            appData = appData,
            versionTag = existing.fileMetadata.versionTag,
        )
        return UpdateFileByFileIdRequest(
            driveId = driveId,
            fileId = existing.fileId,
            keyHeader = keyHeader,
            instructions = FileUpdateInstructionSet(
                transferIv = ByteArrayUtil.getRndByteArray(16),
                locale = UpdateLocale.Local,
                recipients = emptyList(),
                manifest = UpdateManifest.build(
                    payloads = null,
                    toDeletePayloads = null,
                    thumbnails = null,
                    generatePayloadIv = false,
                ),
            ),
            metadata = metadata.encryptContent(keyHeader),
            payloads = null,
            thumbnails = null,
        )
    }

    /** Membership add — dedups, preserves every other tag (a photo lives in many albums). */
    fun withTag(tags: List<Uuid>?, albumTag: Uuid): List<Uuid> =
        if (tags?.contains(albumTag) == true) tags else tags.orEmpty() + albumTag

    /** Membership remove — drops [albumTag] only. */
    fun withoutTag(tags: List<Uuid>?, albumTag: Uuid): List<Uuid> =
        tags.orEmpty().filterNot { it == albumTag }
}
