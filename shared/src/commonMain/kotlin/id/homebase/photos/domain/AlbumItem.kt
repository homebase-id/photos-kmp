package id.homebase.photos.domain

import kotlin.uuid.Uuid

/**
 * One album, projected from a `HomebaseFile` with `fileType 400`. Membership is by tag:
 * photos carry [albumId] (the album's content `tag`, also its `uniqueId`) in their own tags.
 *
 * [coverFileId] is our owner-approved extension to the official content JSON — absent on
 * albums made by the official app, where the cover is the newest member photo instead.
 */
data class AlbumItem(
    val fileId: Uuid,       // the album file itself
    val albumId: Uuid,      // the tag photos carry = the album file's content `tag`
    val name: String,
    val coverFileId: Uuid?,
    val description: String? = null,
)
