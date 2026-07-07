package id.homebase.photos.backup

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Android [PhotoLibraryCrawler] over MediaStore — images AND video. Newest-first (`DATE_ADDED
 * DESC`). Image and video live in separate MediaStore collections that, on older devices (the Redmi
 * is API 28), keep INDEPENDENT `_ID` sequences — so an image and a video can share `_ID` 5. Video
 * `deviceAssetId`s are therefore prefixed [VIDEO_PREFIX] to stay unique as ledger dedup keys; image
 * ids stay bare (unchanged, so already-backed-up photos keep matching the ledger). The prefix also
 * tells [readBytes]/[readPosterFrame] which collection to resolve against.
 *
 * `DATE_TAKEN` is millis; `DATE_ADDED` is seconds — normalised to millis. The MediaStore column
 * names are shared across the image/video collections, so one query helper serves both.
 *
 * Folder-selective (D6): [folders] enumerates buckets with item counts (photos + videos); [assets]
 * only ever reads the buckets the user selected — an empty selection reads nothing.
 */
class MediaStoreCrawler(private val context: Context) : PhotoLibraryCrawler {

    override suspend fun folders(): List<LibraryFolder> = withContext(Dispatchers.IO) {
        // Aggregate counts in memory: API 28 gives no GROUP BY guarantee across providers, so we
        // count rows client-side. Images and videos in the same on-disk folder share a BUCKET_ID,
        // so their counts merge into one folder row.
        val names = HashMap<String, String>()
        val counts = HashMap<String, Int>()
        countBuckets(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, names, counts)
        countBuckets(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, names, counts)
        counts.entries
            .map { (id, count) -> LibraryFolder(folderId = id, name = names[id] ?: OTHER_FOLDER, photoCount = count) }
            .sortedByDescending { it.photoCount }
    }

    private fun countBuckets(collection: Uri, names: HashMap<String, String>, counts: HashMap<String, Int>) {
        val projection = arrayOf(
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        context.contentResolver.query(
            collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.isNull(bucketIdCol)) continue
                val bucketId = cursor.getLong(bucketIdCol).toString()
                if (!names.containsKey(bucketId)) {
                    // First row per bucket (newest, DATE_ADDED DESC) names it; null → "Other".
                    names[bucketId] = if (cursor.isNull(bucketNameCol)) OTHER_FOLDER else cursor.getString(bucketNameCol)
                }
                counts[bucketId] = (counts[bucketId] ?: 0) + 1
            }
        }
    }

    override suspend fun assets(folderIds: Set<String>): List<LibraryAsset> = withContext(Dispatchers.IO) {
        // Nothing selected → upload nothing (D6 default). Never even open a cursor.
        if (folderIds.isEmpty()) return@withContext emptyList()
        // Merge both collections and re-sort newest-first across them.
        (queryAssets(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, idPrefix = "", folderIds) +
            queryAssets(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, idPrefix = VIDEO_PREFIX, folderIds))
            .sortedByDescending { it.addedAtMillis ?: it.takenAtMillis ?: 0L }
    }

    private fun queryAssets(collection: Uri, idPrefix: String, folderIds: Set<String>): List<LibraryAsset> {
        val placeholders = folderIds.joinToString(",") { "?" }
        val selection = "${MediaStore.MediaColumns.BUCKET_ID} IN ($placeholders)"
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN, // millis
            MediaStore.MediaColumns.DATE_ADDED, // seconds
            MediaStore.MediaColumns.SIZE,
        )
        val result = ArrayList<LibraryAsset>()
        context.contentResolver.query(
            collection, projection, selection, folderIds.toTypedArray(),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val takenMillis = if (cursor.isNull(takenCol)) null else cursor.getLong(takenCol)
                val addedSeconds = if (cursor.isNull(addedCol)) null else cursor.getLong(addedCol)
                val defaultName = if (idPrefix.isEmpty()) "IMG_$id" else "VID_$id"
                result.add(
                    LibraryAsset(
                        deviceAssetId = "$idPrefix$id",
                        fileName = if (cursor.isNull(nameCol)) defaultName else cursor.getString(nameCol),
                        mimeType = if (cursor.isNull(mimeCol)) null else cursor.getString(mimeCol),
                        takenAtMillis = takenMillis?.takeIf { it > 0 },
                        addedAtMillis = addedSeconds?.let { it * 1000 },
                        sizeBytes = if (cursor.isNull(sizeCol)) null else cursor.getLong(sizeCol),
                    )
                )
            }
        }
        return result
    }

    override suspend fun readBytes(asset: LibraryAsset): ByteArray? = withContext(Dispatchers.IO) {
        val uri = contentUri(asset) ?: return@withContext null
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    override suspend fun readPosterFrame(asset: LibraryAsset): ByteArray? = withContext(Dispatchers.IO) {
        if (!asset.deviceAssetId.startsWith(VIDEO_PREFIX)) return@withContext null
        val uri = contentUri(asset) ?: return@withContext null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            // First keyframe as the poster; fall back to a representative frame.
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return@withContext null
            ByteArrayOutputStream().use { out ->
                frame.compress(Bitmap.CompressFormat.JPEG, POSTER_JPEG_QUALITY, out)
                frame.recycle()
                out.toByteArray()
            }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() } // MediaMetadataRetriever isn't AutoCloseable until API 29
        }
    }

    /** Resolve the content URI for [asset], picking the Video vs Images collection by the id prefix. */
    private fun contentUri(asset: LibraryAsset): Uri? {
        val isVideo = asset.deviceAssetId.startsWith(VIDEO_PREFIX)
        val rawId = if (isVideo) asset.deviceAssetId.removePrefix(VIDEO_PREFIX) else asset.deviceAssetId
        val id = rawId.toLongOrNull() ?: return null
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return ContentUris.withAppendedId(collection, id)
    }

    private companion object {
        const val OTHER_FOLDER = "Other"
        const val VIDEO_PREFIX = "vid:" // disambiguates video _IDs from image _IDs in the ledger key
        const val POSTER_JPEG_QUALITY = 90
    }
}
