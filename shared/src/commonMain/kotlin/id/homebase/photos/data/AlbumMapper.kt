package id.homebase.photos.data

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.photos.domain.AlbumItem
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class AlbumContent(val name: String? = null, val coverFileId: String? = null)

/** Pure projection `HomebaseFile` (fileType 900) → [AlbumItem]. No I/O — mirrors [PhotoMapper]. */
object AlbumMapper {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Null when the row can't act as an album (no tag to look photos up by). */
    fun fromHomebaseFile(file: HomebaseFile): AlbumItem? {
        val albumId = file.fileMetadata.appData.tags?.firstOrNull() ?: return null
        val content = file.fileMetadata.appData.content
            ?.let { runCatching { json.decodeFromString<AlbumContent>(it) }.getOrNull() }
        return AlbumItem(
            fileId = file.fileId,
            albumId = albumId,
            name = content?.name?.takeIf { it.isNotBlank() } ?: "Untitled",
            coverFileId = content?.coverFileId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
        )
    }
}
