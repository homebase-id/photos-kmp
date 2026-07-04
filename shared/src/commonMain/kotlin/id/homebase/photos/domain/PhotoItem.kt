package id.homebase.photos.domain

import id.homebase.api.client.KeyHeader
import id.homebase.core.image.ImageSize
import kotlin.uuid.Uuid

/**
 * A single timeline photo or video, projected from a synced `HomebaseFile`.
 *
 * Headless: carries only what both native grids/viewers need to render and to
 * build a `HomebaseImageData` for Coil (`fileId` + `driveId` + `payloadKey`
 * + a chosen size; placeholder = [previewPlaceholder]). No bitmaps here.
 *
 * The trailing crypto/context fields ([keyHeader]..[thumbSizes]) let the Android
 * Coil path build a fully-decryptable `HomebaseImageData` without a second index
 * lookup (iOS builds it from the row directly in `loadThumbnailBytes`). They
 * default to the unencrypted/empty case so the mock repository and existing tests
 * stay source-compatible.
 */
data class PhotoItem(
    val fileId: Uuid,
    val uniqueId: Uuid?,
    val userDate: Long,              // EXIF capture millis — timeline sort key
    val isVideo: Boolean,            // PhotoConfig.isVideo(payload.contentType)
    val pixelWidth: Int,             // from previewThumbnail dims (aspect + placeholder)
    val pixelHeight: Int,
    val previewPlaceholder: String?, // inline base64 webp blur placeholder
    val driveId: Uuid,              // + payloadKey → build HomebaseImageData for Coil
    val payloadKey: String,         // PhotoConfig.PAYLOAD_KEY ("dflt_key")
    val keyHeader: KeyHeader? = null,       // decryption key for the Coil path (null for mock/unencrypted)
    val isEncrypted: Boolean = false,
    val payloadContentType: String? = null, // real payload MIME (also decides isVideo upstream)
    val lastModified: Long? = null,          // cache-key freshness
    val thumbSizes: List<ImageSize> = emptyList(), // native server thumbnail sizes for size snapping
)
