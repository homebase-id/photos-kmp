package id.homebase.photos.backup

import id.homebase.api.foundation.toByteArray
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.valueWithCMTime
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSDate
import platform.Foundation.NSMutableData
import platform.Foundation.NSNumber
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSPredicate
import platform.Foundation.NSSortDescriptor
import platform.Foundation.NSValue
import platform.Foundation.appendData
import platform.Foundation.valueForKey
import platform.Photos.PHAsset
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionSubtypeAny
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumUserLibrary
import platform.Photos.PHAssetCollectionTypeAlbum
import platform.Photos.PHAssetCollectionTypeSmartAlbum
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceManager
import platform.Photos.PHAssetResourceRequestOptions
import platform.Photos.PHAssetResourceTypeFullSizePhoto
import platform.Photos.PHAssetResourceTypeFullSizeVideo
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAssetResourceTypeVideo
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHFetchOptions
import platform.Photos.PHImageManager
import platform.Photos.PHPhotoLibrary
import platform.Photos.PHVideoRequestOptions
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.coroutines.resume

/**
 * iOS [PhotoLibraryCrawler] over the Photos framework (PHAsset). Mirrors the Android
 * [MediaStoreCrawler]: folders count-desc, assets newest-first by `creationDate`, an empty
 * selection reads nothing (D6 default), and poster frames only for video.
 *
 * Unlike Android's per-collection `_ID` sequences, a PHAsset's `localIdentifier` is globally unique
 * across images and videos, so there's no `vid:` prefix — [readBytes]/[readPosterFrame] branch on
 * `mediaType`, not on the id. Callback-based Photos APIs are bridged to `suspend` with the same
 * `suspendCancellableCoroutine` pattern as `IOSFileOperationsProvider`.
 *
 * Authorization is granted by Swift (the app requests it); when not authorized/limited, enumeration
 * returns empty and reads return null rather than throwing.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class PHAssetCrawler : PhotoLibraryCrawler {

    override suspend fun folders(): List<LibraryFolder> {
        if (!authorized()) return emptyList()
        val out = ArrayList<LibraryFolder>()
        for (collection in allCollections()) {
            val count = PHAsset.fetchAssetsInAssetCollection(collection, imageVideoOptions(sorted = false))
                .count.toInt()
            if (count == 0) continue // Skip empty collections (matches Android bucket behavior).
            out.add(
                LibraryFolder(
                    folderId = collection.localIdentifier,
                    name = collection.localizedTitle ?: OTHER_FOLDER,
                    photoCount = count,
                )
            )
        }
        return out.sortedByDescending { it.photoCount }
    }

    override suspend fun assets(folderIds: Set<String>): List<LibraryAsset> {
        // Nothing selected → upload nothing (D6 default); never open a fetch.
        if (folderIds.isEmpty()) return emptyList()
        if (!authorized()) return emptyList()
        // An asset can live in several selected albums — dedup by localIdentifier.
        val byId = LinkedHashMap<String, LibraryAsset>()
        val collections = PHAssetCollection.fetchAssetCollectionsWithLocalIdentifiers(folderIds.toList(), null)
        eachIndex(collections.count) { ci ->
            val collection = collections.objectAtIndex(ci) as? PHAssetCollection ?: return@eachIndex
            val fetch = PHAsset.fetchAssetsInAssetCollection(collection, imageVideoOptions(sorted = true))
            eachIndex(fetch.count) { ai ->
                val asset = fetch.objectAtIndex(ai) as? PHAsset ?: return@eachIndex
                byId.getOrPut(asset.localIdentifier) { asset.toLibraryAsset() }
            }
        }
        return byId.values.sortedByDescending { it.takenAtMillis ?: it.addedAtMillis ?: 0L }
    }

    override suspend fun readBytes(asset: LibraryAsset): ByteArray? {
        if (!authorized()) return null
        val phAsset = resolveAsset(asset.deviceAssetId) ?: return null
        val isVideo = phAsset.mediaType == PHAssetMediaTypeVideo
        val resource = primaryResource(phAsset, isVideo) ?: return null
        return readResourceBytes(resource)
    }

    override suspend fun readPosterFrame(asset: LibraryAsset): ByteArray? {
        if (asset.mimeType?.startsWith("video/") != true) return null // Video only.
        if (!authorized()) return null
        val phAsset = resolveAsset(asset.deviceAssetId) ?: return null
        if (phAsset.mediaType != PHAssetMediaTypeVideo) return null
        val avAsset = requestAVAsset(phAsset) ?: return null
        return generatePoster(avAsset)
    }

    // --- enumeration helpers ------------------------------------------------

    /** User albums + the smart-album camera roll, as an ordered list of collections. */
    private fun allCollections(): List<PHAssetCollection> {
        val out = ArrayList<PHAssetCollection>()
        val userAlbums = PHAssetCollection.fetchAssetCollectionsWithType(
            PHAssetCollectionTypeAlbum, PHAssetCollectionSubtypeAny, null,
        )
        eachIndex(userAlbums.count) { (userAlbums.objectAtIndex(it) as? PHAssetCollection)?.let { c -> out.add(c) } }
        val cameraRoll = PHAssetCollection.fetchAssetCollectionsWithType(
            PHAssetCollectionTypeSmartAlbum, PHAssetCollectionSubtypeSmartAlbumUserLibrary, null,
        )
        eachIndex(cameraRoll.count) { (cameraRoll.objectAtIndex(it) as? PHAssetCollection)?.let { c -> out.add(c) } }
        return out
    }

    /** Image + video only, optionally newest-first. Predicate uses the stable PHAssetMediaType raw values (image=1, video=2). */
    private fun imageVideoOptions(sorted: Boolean): PHFetchOptions = PHFetchOptions().apply {
        predicate = NSPredicate.predicateWithFormat("mediaType == 1 OR mediaType == 2")
        if (sorted) {
            sortDescriptors = listOf(NSSortDescriptor.sortDescriptorWithKey("creationDate", ascending = false))
        }
    }

    private fun PHAsset.toLibraryAsset(): LibraryAsset {
        val isVideo = mediaType == PHAssetMediaTypeVideo
        val resource = primaryResource(this, isVideo)
        val createdMs = creationDate?.timeIntervalSince1970?.let { (it * 1000).toLong() }
        val modifiedMs = modificationDate?.timeIntervalSince1970?.let { (it * 1000).toLong() }
        return LibraryAsset(
            deviceAssetId = localIdentifier,
            fileName = resource?.originalFilename ?: defaultName(localIdentifier, isVideo),
            mimeType = utiToMimeType(resource?.uniformTypeIdentifier, isVideo),
            takenAtMillis = createdMs,
            addedAtMillis = createdMs ?: modifiedMs,
            sizeBytes = resourceFileSize(resource),
        )
    }

    // --- byte / poster helpers ---------------------------------------------

    private fun resolveAsset(localIdentifier: String): PHAsset? {
        val fetch = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(localIdentifier), null)
        if (fetch.count.toInt() == 0) return null
        return fetch.objectAtIndex(0u) as? PHAsset
    }

    /** Primary original resource: the full-size photo/video, else the plain photo/video, else the first. */
    private fun primaryResource(asset: PHAsset, isVideo: Boolean): PHAssetResource? {
        val resources = PHAssetResource.assetResourcesForAsset(asset).filterIsInstance<PHAssetResource>()
        if (resources.isEmpty()) return null
        return if (isVideo) {
            resources.firstOrNull { it.type == PHAssetResourceTypeFullSizeVideo || it.type == PHAssetResourceTypeVideo }
                ?: resources.first()
        } else {
            resources.firstOrNull { it.type == PHAssetResourceTypeFullSizePhoto || it.type == PHAssetResourceTypePhoto }
                ?: resources.first()
        }
    }

    /** PHAssetResource exposes size via the KVC key "fileSize" (no typed API); null if unavailable. */
    private fun resourceFileSize(resource: PHAssetResource?): Long? {
        resource ?: return null
        return (resource.valueForKey("fileSize") as? NSNumber)?.longLongValue
    }

    /** Accumulate the resource's original bytes (streamed in chunks) byte-for-byte into a ByteArray. */
    private suspend fun readResourceBytes(resource: PHAssetResource): ByteArray? =
        suspendCancellableCoroutine { cont ->
            val acc = NSMutableData()
            val options = PHAssetResourceRequestOptions().apply { setNetworkAccessAllowed(true) }
            PHAssetResourceManager.defaultManager().requestDataForAssetResource(
                resource,
                options = options,
                dataReceivedHandler = { chunk -> if (chunk != null) acc.appendData(chunk) },
                completionHandler = { error ->
                    if (!cont.isActive) return@requestDataForAssetResource
                    cont.resume(if (error != null) null else acc.toByteArray())
                },
            )
        }

    private suspend fun requestAVAsset(phAsset: PHAsset): AVAsset? =
        suspendCancellableCoroutine { cont ->
            val options = PHVideoRequestOptions().apply { setNetworkAccessAllowed(true) }
            PHImageManager.defaultManager().requestAVAssetForVideo(
                phAsset,
                options,
                resultHandler = { avAsset, _, _ -> if (cont.isActive) cont.resume(avAsset) },
            )
        }

    /** First-frame JPEG (~0.9) via AVAssetImageGenerator — same idiom as AvFoundationVideoDecoder. */
    private suspend fun generatePoster(avAsset: AVAsset): ByteArray? =
        suspendCancellableCoroutine { cont ->
            val generator = AVAssetImageGenerator(avAsset)
            generator.setAppliesPreferredTrackTransform(true)
            generator.setRequestedTimeToleranceBefore(CMTimeMake(Long.MAX_VALUE, 1))
            generator.setRequestedTimeToleranceAfter(CMTimeMake(Long.MAX_VALUE, 1))
            // Aim slightly past 0 to dodge an all-black opening frame some captures have.
            val target = NSValue.valueWithCMTime(CMTimeMake(POSTER_TARGET_MS, 1000))
            generator.generateCGImagesAsynchronouslyForTimes(listOf(target)) { _, image, _, _, _ ->
                val bytes = image?.let {
                    UIImageJPEGRepresentation(UIImage.imageWithCGImage(it), POSTER_JPEG_QUALITY)?.toByteArray()
                }
                if (cont.isActive) cont.resume(bytes)
            }
            cont.invokeOnCancellation { runCatching { generator.cancelAllCGImageGeneration() } }
        }

    // --- misc ---------------------------------------------------------------

    private fun authorized(): Boolean {
        val status = PHPhotoLibrary.authorizationStatus()
        return status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited
    }

    /** Loop 0 until [count] over a PHFetchResult without naming its mapped generic type. */
    private inline fun eachIndex(count: ULong, action: (ULong) -> Unit) {
        var i: ULong = 0u
        while (i < count) {
            action(i)
            i++
        }
    }

    private fun defaultName(localIdentifier: String, isVideo: Boolean): String {
        val stem = localIdentifier.substringBefore('/')
        return if (isVideo) "VID_$stem.mov" else "IMG_$stem.jpg"
    }

    private companion object {
        const val OTHER_FOLDER = "Other"
        const val POSTER_TARGET_MS = 100L
        const val POSTER_JPEG_QUALITY = 0.9
    }
}
