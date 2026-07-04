package id.homebase.photos.backup

/**
 * One device-library photo, as surfaced by a [PhotoLibraryCrawler]. Platform-neutral: Android's
 * MediaStore and (later) iOS's PHAsset both project into this shape so the shared backup pipeline
 * never touches a platform API. All time fields are epoch millis; nulls mean "the library didn't
 * record it" (a screenshot with no capture date, a file with no size).
 */
data class LibraryAsset(
    val deviceAssetId: String,   // MediaStore _ID as string (stable per device) — the ledger dedup key
    val fileName: String,
    val mimeType: String?,
    val takenAtMillis: Long?,    // MediaStore DATE_TAKEN — D3 fallback input
    val addedAtMillis: Long?,    // DATE_ADDED millis — last-resort D3 fallback
    val sizeBytes: Long?,
)

/**
 * One device-library folder (Android MediaStore "bucket"), as surfaced by a [PhotoLibraryCrawler].
 * The user picks which folders back up (D6, Google-Photos "device folders" model); the default is
 * none selected. [folderId] is the opaque bucket key the selection persists and [assets] filters on.
 */
data class LibraryFolder(
    val folderId: String,   // MediaStore BUCKET_ID as string — the selection/persistence key
    val name: String,       // BUCKET_DISPLAY_NAME ("Camera", "Screenshots", …)
    val photoCount: Int,
)

/**
 * Enumerates the device photo library and reads original bytes. Bound in `platformModule()`:
 * the real Android implementation (MediaStore) lives in androidMain; iOS/JVM bind
 * [StubPhotoLibraryCrawler] until their native crawlers land (later waves).
 *
 * Backup is folder-selective (D6): callers enumerate [folders] and back up only the assets in the
 * folders the user selected — [assets] takes that selection and never crawls the whole library.
 */
interface PhotoLibraryCrawler {
    /** Every device folder that holds images, with photo counts. Order is impl-defined (count-desc). */
    suspend fun folders(): List<LibraryFolder>

    /**
     * Image assets in the selected folders only, newest-first. Videos are excluded (out of scope
     * this wave). An empty [folderIds] selects nothing and returns an empty list — the D6 default,
     * so enabling backup uploads nothing until folders are deliberately chosen.
     */
    suspend fun assets(folderIds: Set<String>): List<LibraryAsset>

    /** Original file bytes for [asset], or null if the source vanished / can't be read. */
    suspend fun readBytes(asset: LibraryAsset): ByteArray?
}

/** No-op crawler for platforms without a native crawler yet (iOS/JVM). Keeps those targets green. */
class StubPhotoLibraryCrawler : PhotoLibraryCrawler {
    override suspend fun folders(): List<LibraryFolder> = emptyList()
    override suspend fun assets(folderIds: Set<String>): List<LibraryAsset> = emptyList()
    override suspend fun readBytes(asset: LibraryAsset): ByteArray? = null
}
