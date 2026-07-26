package id.homebase.photos.data

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.video.VideoPlayerData
import id.homebase.api.video.VideoPrefetchDriveAccess
import id.homebase.api.video.resolveVideoMetadata
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.image.ImageSize
import id.homebase.core.image.thumbSizesFrom
import id.homebase.photos.PhotoConfig
import id.homebase.photos.PhotoQueries
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.viewer.VideoHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

/**
 * Real timeline repository: DriveSync pull of the Photos drive + a local
 * DriveMainIndex paged read (userDate DESC), mapping each row's `HomebaseFile`
 * to a [PhotoItem]. Thumbnail bytes for iOS native rendering wrap
 * [HomebaseImageLoader].
 *
 * [driveId] is the Photos drive alias `Uuid` (DriveMainIndex.driveId IS the alias —
 * no resolution step), bound in `photosModule` as the same value used for the
 * mandatoryDrives mount key.
 */
class PhotosRepositoryImpl(
    private val driveId: Uuid,
    private val driveSyncManager: DriveSyncManager,
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val imageLoader: HomebaseImageLoader,
    private val driveFileProvider: DriveFileProvider,
    driveUploadProvider: DriveUploadProvider,
    private val driveQueryProvider: DriveQueryProvider,
    private val fileOps: FileOperationsProvider,
) : PhotosRepository {

    private val statusWriter = PhotoStatusWriter(
        driveId = driveId,
        drive = OdinAlbumDriveGateway(driveFileProvider, driveUploadProvider),
    )

    private val _photos = MutableStateFlow<List<PhotoItem>>(emptyList())

    /**
     * Dormant live stream (PERF-10). The timeline VM pages via [loadPage] + an awaited
     * [sync]; this flow is never refilled by [sync] anymore and stays empty until a future
     * wave wires it to EventBus BackendEvents (or it is removed from the contract).
     */
    override fun observePhotos(): Flow<List<PhotoItem>> = _photos.asStateFlow()

    override suspend fun loadPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> {
        val identityId = activeIdentity() ?: return emptyList()
        // Long.MAX_VALUE = "no cursor": the newest page. `userDate < cursor` then
        // strictly pages older for each subsequent call.
        val cursor = beforeUserDate ?: Long.MAX_VALUE
        val files = databaseManager.driveMainIndex.selectPhotosPage(
            identityId = identityId,
            driveId = driveId,
            fileType = PhotoConfig.PHOTO_FILE_TYPE.toLong(),
            beforeUserDate = cursor,
            limit = limit.toLong(),
        )
        return files
            .asSequence()
            .filterNot { it.isSoftDeleted() } // canonical predicate; SQL fileState=1 can miss archival-removed
            .map { PhotoMapper.fromHomebaseFile(it) }
            .toList()
    }

    override suspend fun sync() {
        // Chat-canonical minimal sequence; no WebSocket needed for a REST crawl. syncAll()
        // suspends until own drives reach a terminal state — that's the await the VM needs
        // before it re-reads the now-populated local index via loadPage().
        driveSyncManager.ensureMandatoryMounted()
        driveSyncManager.start()   // idempotent; no-op without credentials
        driveSyncManager.syncAll() // suspends until own drives finish
    }

    override suspend fun deletePhotos(fileIds: List<Uuid>): Boolean {
        if (fileIds.isEmpty()) return true
        val outcome = driveFileProvider.deleteFiles(driveId, fileIds)
        // Not-found counts as deleted — the goal state (file absent) already holds.
        return outcome.results.all { it.localFileDeleted || it.localFileNotFound }
    }

    override suspend fun setFavorite(fileId: Uuid, favorite: Boolean): Boolean =
        statusWriter.setFavorite(fileId, favorite)

    override suspend fun setArchived(fileIds: List<Uuid>, archived: Boolean): PhotoStatusResult =
        statusWriter.setArchivalStatus(fileIds, if (archived) ArchivalStatus.Archived else ArchivalStatus.None)

    override suspend fun softDelete(fileIds: List<Uuid>): PhotoStatusResult =
        statusWriter.setArchivalStatus(fileIds, ArchivalStatus.Removed)

    override suspend fun restore(fileIds: List<Uuid>): PhotoStatusResult =
        statusWriter.setArchivalStatus(fileIds, ArchivalStatus.None)

    override suspend fun permanentDelete(fileIds: List<Uuid>): Boolean = deletePhotos(fileIds)

    override suspend fun loadFavoritesPage(cursor: String?, limit: Int): FavoritesPage {
        val response = driveQueryProvider.queryBatch(driveId, PhotoQueries.favoritesQuery(cursor, limit))
        val items = response.searchResults
            .filterNot { it.isSoftDeleted() }
            .map(PhotoMapper::fromHomebaseFile)
        return FavoritesPage(items = items, nextCursor = response.cursorState.takeIf { response.hasMoreRows })
    }

    override suspend fun loadArchivedPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> =
        loadStatusPage(ArchivalStatus.Archived, beforeUserDate, limit)

    override suspend fun loadTrashPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> =
        loadStatusPage(ArchivalStatus.Removed, beforeUserDate, limit)

    private suspend fun loadStatusPage(status: ArchivalStatus, beforeUserDate: Long?, limit: Int): List<PhotoItem> {
        val identityId = activeIdentity() ?: return emptyList()
        val cursor = beforeUserDate ?: Long.MAX_VALUE
        val files = databaseManager.driveMainIndex.selectPhotosByArchivalStatusPage(
            identityId = identityId,
            driveId = driveId,
            fileType = PhotoConfig.PHOTO_FILE_TYPE.toLong(),
            archivalStatus = status.value.toLong(),
            beforeUserDate = cursor,
            limit = limit.toLong(),
        )
        return files.map(PhotoMapper::fromHomebaseFile)
    }

    override suspend fun loadThumbnailBytes(item: PhotoItem, maxDim: Int): ByteArray? {
        val file = lookupFile(item.fileId) ?: return null
        return imageLoader.loadThumbnail(imageDataFor(item, file), ImageSize(maxDim, maxDim))?.bytes
    }

    override suspend fun loadOriginalBytes(item: PhotoItem): ByteArray? {
        val file = lookupFile(item.fileId) ?: return null
        return imageLoader.loadFullPayload(imageDataFor(item, file, loadFullPayload = true))?.bytes
    }

    override suspend fun prepareVideo(item: PhotoItem): VideoHandle? {
        val file = lookupFile(item.fileId) ?: return null
        val payload = file.fileMetadata.getPayloadDescriptor(item.payloadKey)
        val keyHeader = perPayloadKeyHeader(file.keyHeader, payload)
        val playerData = VideoPlayerData(
            fileId = item.fileId,
            driveId = item.driveId,
            payloadKey = item.payloadKey,
            keyHeader = keyHeader,
            descriptorContent = payload?.descriptorContent,
        )
        // ponytail: HLS playback deferred — decrypt-to-temp mp4 only; chat-kmp loopback path if needed
        if (isSegmentedVideo(playerData, driveFileProvider)) return null
        val mimeType = payload?.contentType ?: item.payloadContentType ?: "video/mp4"
        val outputPath =
            fileOps.getCacheDirectory().trimEnd('/') + "/" + viewerVideoFileName(item.fileId, mimeType)
        val streamed = try {
            driveFileProvider.streamPayloadDecryptedToPath(
                driveId = item.driveId,
                fileId = item.fileId,
                key = item.payloadKey,
                keyHeader = keyHeader,
                outputPath = outputPath,
                fileOps = fileOps,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "prepareVideo(${item.fileId}) stream failed: ${e.message}" }
            false
        }
        return if (streamed) VideoHandle(filePath = outputPath, mimeType = mimeType) else null
    }

    override suspend fun disposeVideo(handle: VideoHandle) {
        fileOps.deleteTempFile(handle.filePath)
    }

    private fun imageDataFor(
        item: PhotoItem,
        file: HomebaseFile,
        loadFullPayload: Boolean = false,
    ): HomebaseImageData {
        val payload = file.fileMetadata.getPayloadDescriptor(item.payloadKey)
        return HomebaseImageData(
            driveId = item.driveId,
            fileId = item.fileId,
            payloadKey = item.payloadKey,
            previewThumbnail = file.fileMetadata.appData.previewThumbnail,
            availableThumbSizes = thumbSizesFrom(payload?.thumbnails),
            loadFullPayload = loadFullPayload,
            isEncrypted = file.fileMetadata.isEncrypted,
            payloadContentType = payload?.contentType,
            lastModified = payload?.lastModified,
            // Per-payload IV (not the file/metadata IV) or thumbnails decrypt to garbage.
            keyHeader = perPayloadKeyHeader(file.keyHeader, payload),
        )
    }

    private suspend fun lookupFile(fileId: Uuid): HomebaseFile? {
        val identityId = activeIdentity() ?: return null
        val row = databaseManager.driveMainIndex
            .selectByIdentityAndDriveAndFile(identityId, driveId, fileId) ?: return null
        return try {
            OdinSystemSerializer.deserialize<HomebaseFile>(row.jsonHeader)
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "lookupFile($fileId) deserialize failed: ${e.message}" }
            null
        }
    }

    private suspend fun activeIdentity(): Uuid? =
        credentialsManager.getActiveCredentials()?.getIdentityId()

    companion object {
        private const val TAG = "PhotosRepository"
    }
}

/**
 * True only when the descriptor positively says segmented/HLS. A missing or unreadable
 * descriptor counts as plain — decrypt-to-temp still works for a whole-file payload,
 * while a false "segmented" would refuse a playable video.
 */
internal suspend fun isSegmentedVideo(
    data: VideoPlayerData,
    access: VideoPrefetchDriveAccess,
): Boolean {
    if (data.descriptorContent == null) return false
    return try {
        resolveVideoMetadata(data, access).isSegmented
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(tag = "PhotosRepository") { "video metadata resolve failed for ${data.fileId}: ${e.message}" }
        false
    }
}

/** Cache-dir file name for a decrypted viewer video; extension from the payload MIME, default mp4. */
internal fun viewerVideoFileName(fileId: Uuid, mimeType: String?): String =
    "viewer_$fileId." + when (mimeType?.lowercase()) {
        "video/quicktime" -> "mov"
        "video/x-m4v" -> "m4v"
        "video/webm" -> "webm"
        else -> "mp4"
    }
