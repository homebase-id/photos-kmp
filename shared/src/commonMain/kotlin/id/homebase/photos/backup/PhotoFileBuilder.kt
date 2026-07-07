package id.homebase.photos.backup

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageMetadata
import id.homebase.api.image.ThumbnailInstruction
import id.homebase.api.image.createImageThumbnail
import id.homebase.api.image.createThumbnails
import id.homebase.api.image.readImageMetadata
import id.homebase.api.image.tinyThumbSize
import id.homebase.photos.PhotoConfig
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import kotlin.uuid.Uuid

// ---------------------------------------------------------------------------
// Spec §1 content model (D4). Top-level `camera`/`captureDetails` objects are ALWAYS present
// (even when empty) to match the real library rows; their inner fields are omitted when absent.
// exposureTime/fNumber/iso/focalLength are deferred (our copied EXIF reader doesn't extract them).
// ---------------------------------------------------------------------------

@Serializable
data class PhotoCamera(val make: String? = null, val model: String? = null)

@Serializable
data class PhotoGeolocation(val latitude: Double, val longitude: Double, val altitude: Double? = null)

@Serializable
data class PhotoCaptureDetails(val geolocation: PhotoGeolocation? = null)

@Serializable
data class PhotoContent(
    val camera: PhotoCamera,
    val captureDetails: PhotoCaptureDetails,
    val originalFileName: String,
)

// encodeDefaults=false → inner null fields drop out (`"camera":{}`), but the top-level camera /
// captureDetails objects have NO defaults so they stay present. Matches the fixture shape exactly.
private val photoContentSerializer = Json { encodeDefaults = false }

/** D4 content JSON `{camera{make,model}, captureDetails{geolocation}, originalFileName}`. */
fun photoContentJson(meta: ImageMetadata?, fileName: String): String {
    val geo = if (meta?.latitude != null && meta.longitude != null) {
        PhotoGeolocation(meta.latitude, meta.longitude, meta.altitudeMeters)
    } else null
    val content = PhotoContent(
        camera = PhotoCamera(make = meta?.cameraMake, model = meta?.cameraModel),
        captureDetails = PhotoCaptureDetails(geolocation = geo),
        originalFileName = fileName,
    )
    return photoContentSerializer.encodeToString(PhotoContent.serializer(), content)
}

/** D1: deterministic cross-device dedup id — first 16 bytes of `sha256(originalBytes)` as a Uuid. */
fun deterministicPhotoUniqueId(bytes: ByteArray): Uuid =
    Uuid.fromByteArray(bytes.toByteString().sha256().substring(0, 16).toByteArray())

/**
 * D2/D3 `userDate`, epoch millis. EXIF `DateTimeOriginal` is a wall-clock with no zone, so:
 *  - EXIF present + `OffsetTimeOriginal` present → interpret at that offset (D2).
 *  - EXIF present, no offset → interpret at the device's current zone (D2 fallback).
 *  - No EXIF capture time → MediaStore `DATE_TAKEN`, then `DATE_ADDED` (D3); 0 as last resort.
 */
fun resolvePhotoUserDate(
    meta: ImageMetadata?,
    takenAtMillis: Long?,
    addedAtMillis: Long?,
    zone: TimeZone,
): Long {
    val captured = meta?.capturedAt
    if (captured != null) {
        val offset = meta.captureUtcOffset
        val instant = if (offset != null) captured.toInstant(offset) else captured.toInstant(zone)
        return instant.toEpochMilliseconds()
    }
    return takenAtMillis ?: addedAtMillis ?: 0L
}

/**
 * Turns one [LibraryAsset] + its original bytes into a ready-to-enqueue [UploadFileRequest] that
 * matches the existing Odin Photos row format (verified field-by-field by the §2 format gate).
 *
 * Photo vs video is the same envelope (spec §4: fileType 0, dflt_key payload, poster thumbnail),
 * distinguished only by the payload MIME. For video the caller passes the original video as
 * [build]'s `payloadBytes` (byte-for-byte upload + the dedup hash) and a decoded poster frame as
 * `thumbnailBytes` — video bytes aren't a decodable image, so the thumbnail pipeline runs on the
 * poster. For photos the two are the same array (the default).
 *
 * Encryption is the builder's job — the copied upload path streams payload/thumbnail bytes RAW.
 * So the payload is pre-encrypted to a ciphertext temp file and each thumbnail's bytes are
 * ciphertext, all under ONE file aesKey with a DISTINCT per-payload IV (the per-payload-IV rule);
 * the file-level IV encrypts only the metadata content. Never hand-rolls a cipher — it uses the
 * copied [KeyHeader]/AES primitives exactly as chat-kmp's PayloadBundleEncryptionService does.
 */
class PhotoFileBuilder(
    private val fileOps: FileOperationsProvider,
    private val driveId: Uuid,
    private val zoneProvider: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    suspend fun build(
        asset: LibraryAsset,
        payloadBytes: ByteArray,
        thumbnailBytes: ByteArray = payloadBytes,   // video: a poster frame; photo: the same bytes
    ): UploadFileRequest {
        // Metadata + thumbnails come from the IMAGE (photo bytes, or the video's poster frame). The
        // dedup id + payload come from the ORIGINAL bytes so a video hashes/uploads as itself.
        val meta = readImageMetadata(thumbnailBytes)
        val uniqueId = deterministicPhotoUniqueId(payloadBytes)
        val userDate = resolvePhotoUserDate(meta, asset.takenAtMillis, asset.addedAtMillis, zoneProvider())
        val contentJson = photoContentJson(meta, asset.fileName)

        // Thumbnail tiers 300 + 1200; the inline tiny (20px) also ships as a thumbnail FILE, so the
        // payload carries [20, 300, 1200] like the real rows. getRevisedThumbs may drop tiers above
        // the source size, so a small photo yields fewer — that's expected (1..3 tiers).
        val (naturalSize, embeddedTiny, tierThumbs) =
            createThumbnails(thumbnailBytes, PhotoConfig.PAYLOAD_KEY, thumbSizes = listOf(THUMB_300, THUMB_1200))
        val tinyFile = createImageThumbnail(thumbnailBytes, PhotoConfig.PAYLOAD_KEY, tinyThumbSize, isTinyThumb = true)
        val plainThumbnails = listOf(tinyFile) + tierThumbs

        // One content key for the file; file IV for metadata, a distinct payload IV for bytes+thumbs.
        val fileAesKey = SecureByteArray(ByteArrayUtil.getRndByteArray(16))
        val fileKeyHeader = KeyHeader(iv = ByteArrayUtil.getRndByteArray(16), aesKey = fileAesKey)
        val payloadKeyHeader = KeyHeader(iv = ByteArrayUtil.getRndByteArray(16), aesKey = fileAesKey)

        // Pre-encrypt the original bytes to a ciphertext file (byte-for-byte "original quality").
        // DURABLE staging, not cacheDir: an offline backup queue can wait days, and the startup
        // CacheSweeper wipes cacheDir on launch — a cache temp would die before its outbox row
        // drains. writeBytesToOutboxTempFile targets the sweeper-invisible outbox staging dir,
        // reaped only when the row completes / permanently drops (chat-kmp #842 convention).
        val cipher = payloadKeyHeader.encryptDataAes(payloadBytes)
        val encPath = fileOps.writeBytesToOutboxTempFile(cipher, prefix = "photo_enc_", suffix = ".enc")
        val payload = PayloadFile(
            key = PhotoConfig.PAYLOAD_KEY,
            filePath = encPath,
            contentType = asset.mimeType ?: sniffImageMime(payloadBytes) ?: DEFAULT_CONTENT_TYPE,
            isPreEncrypted = true,
            iv = payloadKeyHeader.iv,
        )

        // Thumbnail bytes ship as ciphertext under the SAME per-payload keyHeader (payload IV).
        val encryptedThumbnails = plainThumbnails.map {
            it.copy(thumbnailBytes = payloadKeyHeader.encryptDataAes(it.thumbnailBytes))
        }

        // Inline preview: PLAINTEXT tiny webp base64, but tagged with the ORIGINAL natural dims
        // (createThumbnails' embeddedTiny carries the 20px dims — override to the source size).
        val previewThumbnail = embeddedTiny?.copy(
            pixelWidth = naturalSize.pixelWidth,
            pixelHeight = naturalSize.pixelHeight,
        )

        val metadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = true,
            accessControlList = AccessControlList(requiredSecurityGroup = SecurityGroupType.Owner.value),
            appData = UploadAppFileMetaData(
                uniqueId = uniqueId,
                tags = emptyList(),                     // no album this wave
                fileType = PhotoConfig.PHOTO_FILE_TYPE,
                dataType = PhotoConfig.PHOTO_DATA_TYPE,
                userDate = userDate,
                archivalStatus = ArchivalStatus.None,
                content = contentJson,
                previewThumbnail = previewThumbnail,
            ),
        ).encryptContent(fileKeyHeader)             // metadata content encrypted with the FILE key

        return UploadFileRequest(
            driveId = driveId,
            keyHeader = fileKeyHeader,
            metadata = metadata,
            payloads = listOf(payload),
            thumbnails = encryptedThumbnails,
        )
    }

    private companion object {
        val THUMB_300 = ThumbnailInstruction(quality = 84, maxPixelDimension = 300, maxBytes = 64 * 1024)
        val THUMB_1200 = ThumbnailInstruction(quality = 76, maxPixelDimension = 1200, maxBytes = 512 * 1024)
        const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
    }
}

/** Minimal magic-byte MIME sniff — a fallback for when the library didn't report a mimeType. */
internal fun sniffImageMime(bytes: ByteArray): String? {
    if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte())
        return "image/jpeg"
    if (bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    ) return "image/png"
    if (bytes.size >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
        bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
        bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
    ) return "image/webp"
    if (bytes.size >= 3 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte())
        return "image/gif"
    return null
}
