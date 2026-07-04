package id.homebase.photos.backup

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android [PhotoLibraryCrawler] over MediaStore images. Newest-first (`DATE_ADDED DESC`), images
 * only. `DATE_TAKEN` is millis; `DATE_ADDED` is seconds — normalised to millis so both
 * [LibraryAsset] fields carry the same unit (the D3 date-fallback input the builder expects).
 *
 * Folder-selective (D6): [folders] enumerates MediaStore buckets with photo counts; [assets] only
 * ever reads the buckets the user selected — an empty selection reads nothing.
 */
class MediaStoreCrawler(private val context: Context) : PhotoLibraryCrawler {

    override suspend fun folders(): List<LibraryFolder> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
        )
        // Aggregate counts in memory: the Redmi is API 28 and MediaStore gives no GROUP BY
        // guarantee across providers, so we count rows client-side instead of trusting SQL grouping.
        val names = HashMap<String, String>()
        val counts = HashMap<String, Int>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
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
        counts.entries
            .map { (id, count) -> LibraryFolder(folderId = id, name = names[id] ?: OTHER_FOLDER, photoCount = count) }
            .sortedByDescending { it.photoCount }
    }

    override suspend fun assets(folderIds: Set<String>): List<LibraryAsset> = withContext(Dispatchers.IO) {
        // Nothing selected → upload nothing (D6 default). Never even open a cursor.
        if (folderIds.isEmpty()) return@withContext emptyList()

        val placeholders = folderIds.joinToString(",") { "?" }
        val selection = "${MediaStore.Images.Media.BUCKET_ID} IN ($placeholders)"
        val selectionArgs = folderIds.toTypedArray()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN, // millis
            MediaStore.Images.Media.DATE_ADDED, // seconds
            MediaStore.Images.Media.SIZE,
        )
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val result = ArrayList<LibraryAsset>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sort,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val takenMillis = if (cursor.isNull(takenCol)) null else cursor.getLong(takenCol)
                val addedSeconds = if (cursor.isNull(addedCol)) null else cursor.getLong(addedCol)
                result.add(
                    LibraryAsset(
                        deviceAssetId = id.toString(),
                        fileName = if (cursor.isNull(nameCol)) "IMG_$id" else cursor.getString(nameCol),
                        mimeType = if (cursor.isNull(mimeCol)) null else cursor.getString(mimeCol),
                        takenAtMillis = takenMillis?.takeIf { it > 0 },
                        addedAtMillis = addedSeconds?.let { it * 1000 },
                        sizeBytes = if (cursor.isNull(sizeCol)) null else cursor.getLong(sizeCol),
                    )
                )
            }
        }
        result
    }

    override suspend fun readBytes(asset: LibraryAsset): ByteArray? = withContext(Dispatchers.IO) {
        val id = asset.deviceAssetId.toLongOrNull() ?: return@withContext null
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    private companion object {
        const val OTHER_FOLDER = "Other"
    }
}
