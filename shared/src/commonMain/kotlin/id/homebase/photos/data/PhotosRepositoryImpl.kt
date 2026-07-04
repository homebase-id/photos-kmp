package id.homebase.photos.data

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.image.ImageSize
import id.homebase.core.image.thumbSizesFrom
import id.homebase.photos.PhotoConfig
import id.homebase.photos.domain.PhotoItem
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
) : PhotosRepository {

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

    override suspend fun loadThumbnailBytes(item: PhotoItem, maxDim: Int): ByteArray? {
        val file = lookupFile(item.fileId) ?: return null
        val payload = file.fileMetadata.getPayloadDescriptor(item.payloadKey)
        val data = HomebaseImageData(
            driveId = item.driveId,
            fileId = item.fileId,
            payloadKey = item.payloadKey,
            previewThumbnail = file.fileMetadata.appData.previewThumbnail,
            availableThumbSizes = thumbSizesFrom(payload?.thumbnails),
            isEncrypted = file.fileMetadata.isEncrypted,
            payloadContentType = payload?.contentType,
            lastModified = payload?.lastModified,
            // Per-payload IV (not the file/metadata IV) or thumbnails decrypt to garbage.
            keyHeader = perPayloadKeyHeader(file.keyHeader, payload),
        )
        return imageLoader.loadThumbnail(data, ImageSize(maxDim, maxDim))?.bytes
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
