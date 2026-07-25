package id.homebase.photos.data

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.photos.domain.AlbumItem

/** Pure projection `HomebaseFile` (fileType 400) → [AlbumItem]. No I/O — mirrors [PhotoMapper]. */
object AlbumMapper {

    /**
     * Null when the row can't act as an album — identity is the content `tag` (what member
     * photos carry in their own tags), with `uniqueId` as the fallback the official app keeps
     * in lockstep with it. `appData.tags` is EMPTY on official album files, so it is never read.
     */
    fun fromHomebaseFile(file: HomebaseFile): AlbumItem? {
        val appData = file.fileMetadata.appData
        val content = parseAlbumContent(appData.content)
        val albumId = parseLenientUuid(content?.tag) ?: appData.uniqueId ?: return null
        return AlbumItem(
            fileId = file.fileId,
            albumId = albumId,
            name = content?.name?.takeIf { it.isNotBlank() } ?: "Untitled",
            coverFileId = content?.coverFileId?.let { parseLenientUuid(it) },
            description = content?.description?.takeIf { it.isNotBlank() },
        )
    }
}
