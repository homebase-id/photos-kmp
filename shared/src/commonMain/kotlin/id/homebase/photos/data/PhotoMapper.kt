package id.homebase.photos.data

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.core.image.thumbSizesFrom
import id.homebase.photos.PhotoConfig
import id.homebase.photos.domain.PhotoItem
import kotlin.io.encoding.Base64

/**
 * KeyHeader for decrypting a payload (and its thumbnails): the file's shared aesKey
 * but the PAYLOAD's own IV. Each payload/thumbnail is AES-CBC-encrypted under its own
 * IV; the file-level keyHeader IV only decrypts the metadata, so reusing it corrupts
 * the first cipher block — i.e. the webp `RIFF....WEBP` header — leaving decode to fail.
 * Mirrors chat-kmp (MomentMediaItem: `KeyHeader(iv = payload.iv, aesKey = file aesKey)`).
 * Falls back to [fileKeyHeader] when the payload carries no IV (unencrypted files).
 */
internal fun perPayloadKeyHeader(fileKeyHeader: KeyHeader, payload: PayloadDescriptor?): KeyHeader {
    val iv = payload?.iv?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return fileKeyHeader
    return KeyHeader(iv = iv, aesKey = fileKeyHeader.aesKey)
}

/** Pure projection `HomebaseFile` → [PhotoItem]. No I/O — unit-testable in isolation. */
object PhotoMapper {
    fun fromHomebaseFile(file: HomebaseFile): PhotoItem {
        val appData = file.fileMetadata.appData
        val preview = appData.previewThumbnail
        val payload = file.fileMetadata.getPayloadDescriptor(PhotoConfig.PAYLOAD_KEY)
        // Video vs image is decided SOLELY by the payload's contentType MIME.
        val contentType = payload?.contentType
        return PhotoItem(
            fileId = file.fileId,
            uniqueId = appData.uniqueId,
            userDate = file.sqlUserDateMs(), // appData.userDate ?: created.milliseconds
            isVideo = contentType?.let { PhotoConfig.isVideo(it) } ?: false,
            pixelWidth = preview?.pixelWidth ?: 0,
            pixelHeight = preview?.pixelHeight ?: 0,
            previewPlaceholder = preview?.content,
            driveId = file.driveId,
            payloadKey = PhotoConfig.PAYLOAD_KEY,
            // Crypto/context for the Android Coil path — mirrors loadThumbnailBytes' construction.
            // Per-payload IV (not the file/metadata IV) or thumbnails decrypt to garbage.
            keyHeader = perPayloadKeyHeader(file.keyHeader, payload),
            isEncrypted = file.fileMetadata.isEncrypted,
            payloadContentType = contentType,
            lastModified = payload?.lastModified,
            thumbSizes = thumbSizesFrom(payload?.thumbnails),
            isFavorite = appData.tags?.contains(PhotoConfig.FAVORITE_TAG) == true,
        )
    }
}
