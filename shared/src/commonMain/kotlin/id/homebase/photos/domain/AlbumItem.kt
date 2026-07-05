package id.homebase.photos.domain

import kotlin.uuid.Uuid

/**
 * One album, projected from a `HomebaseFile` with `fileType 900`. Membership is by tag:
 * photos carry [albumId] (the album file's first tag) in their own tags.
 */
data class AlbumItem(
    val fileId: Uuid,       // the album file itself
    val albumId: Uuid,      // the tag photos carry = first tag on the album file
    val name: String,
    val coverFileId: Uuid?,
)
