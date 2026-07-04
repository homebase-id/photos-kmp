package id.homebase.photos

import id.homebase.photos.domain.PhotoItem
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create

/** iOS-callable: decoded thumbnail as NSData in ONE copy (memcpy), not per-byte interop. */
suspend fun loadThumbnailData(item: PhotoItem, maxDim: Int): NSData? =
    loadThumbnailBytes(item, maxDim)?.toNSData()

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData()
    else usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
