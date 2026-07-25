package id.homebase.photos.backup

/**
 * Maps an Apple uniform-type-identifier (a PHAssetResource's `uniformTypeIdentifier`, e.g.
 * "public.jpeg") to a MIME type. Pure so it's unit-testable off-device; the PHAsset plumbing that
 * feeds it is verified on a real device. Unknown/absent UTIs fall back to a generic per [isVideo]
 * (branch on PHAsset.mediaType, never on the id) so a [LibraryAsset] always carries a usable type.
 */
internal fun utiToMimeType(uti: String?, isVideo: Boolean): String {
    uti?.lowercase()?.let { UTI_MIME[it]?.let { mime -> return mime } }
    return if (isVideo) "video/mp4" else "image/jpeg"
}

private val UTI_MIME: Map<String, String> = mapOf(
    "public.jpeg" to "image/jpeg",
    "public.png" to "image/png",
    "public.heic" to "image/heic",
    "public.heif" to "image/heif",
    "public.tiff" to "image/tiff",
    "com.compuserve.gif" to "image/gif",
    "org.webmproject.webp" to "image/webp",
    "public.webp" to "image/webp",
    "com.apple.quicktime-movie" to "video/quicktime",
    "public.mpeg-4" to "video/mp4",
    "com.apple.m4v-video" to "video/x-m4v",
    "public.3gpp" to "video/3gpp",
    "public.avi" to "video/x-msvideo",
)
