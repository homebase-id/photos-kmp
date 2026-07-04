package id.homebase.photos.android.ui

import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.HomebaseImageFetcher
import id.homebase.core.image.HomebaseImageKeyer
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.image.ImageSize
import id.homebase.photos.domain.PhotoItem
import org.koin.core.Koin

/**
 * Builds the Coil [ImageLoader] backing the timeline grid. Thumbnails are fetched and
 * decrypted through the shared pipeline ([HomebaseImageFetcher] + [HomebaseImageKeyer]),
 * not Coil's HTTP stack — so no coil-network artifact is needed. Keyed memory cache lets
 * a grid thumbnail seed the viewer's placeholder.
 */
fun buildHomebaseImageLoader(context: Context, koin: Koin): ImageLoader {
    // Coil's encrypted Fetcher needs a HomebaseImageLoader; assemble it from the
    // protocol graph. DriveFileProvider is a factory; FileOperationsProvider a single.
    val homebaseImageLoader = HomebaseImageLoader(
        driveFileProvider = koin.get<DriveFileProvider>(),
        fileOperationsProvider = koin.get<FileOperationsProvider>(),
    )
    return ImageLoader.Builder(context as PlatformContext)
        .components {
            add(HomebaseImageKeyer())
            add(
                HomebaseImageFetcher.Factory(
                    homebaseImageLoader = homebaseImageLoader,
                    fileOperationsProvider = koin.get<FileOperationsProvider>(),
                )
            )
        }
        // Cap the in-memory thumbnail cache at 25% of app memory (chat-kmp parity).
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
        // Decrypted photo bytes must NEVER hit a plaintext disk cache (PERF-07).
        .diskCache(null)
        .crossfade(true)
        .build()
        // Install as the Coil singleton so AsyncImage in later batches shares this graph.
        .also { SingletonImageLoader.setSafe { _ -> it } }
}

/**
 * Maps a contract [PhotoItem] onto the Coil-loadable [HomebaseImageData] at the given
 * grid/viewer [requestedSize]. The [PhotoItem] now carries the real per-file crypto/context
 * ([PhotoItem.keyHeader], [PhotoItem.isEncrypted], [PhotoItem.payloadContentType],
 * [PhotoItem.lastModified], [PhotoItem.thumbSizes]) populated by the shared mapper, so a real
 * encrypted thumbnail decrypts in the grid. [KeyHeader.empty] is only the mock/unencrypted
 * fallback. `previewThumbnail` stays null — the native cells draw the blur placeholder themselves.
 */
fun homebaseImageData(photo: PhotoItem, requestedSize: ImageSize): HomebaseImageData =
    HomebaseImageData(
        driveId = photo.driveId,
        fileId = photo.fileId,
        payloadKey = photo.payloadKey,
        requestedSize = requestedSize,
        availableThumbSizes = photo.thumbSizes,
        isEncrypted = photo.isEncrypted,
        payloadContentType = photo.payloadContentType,
        lastModified = photo.lastModified,
        keyHeader = photo.keyHeader ?: KeyHeader.empty(),
    )
