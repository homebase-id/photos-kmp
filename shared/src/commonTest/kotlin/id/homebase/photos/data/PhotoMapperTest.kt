package id.homebase.photos.data

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.core.image.ImageSize
import id.homebase.photos.PhotoConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PhotoMapperTest {

    private fun file(
        contentType: String,
        userDate: Long = 1_700_000_000_000L,
        previewWidth: Int = 1200,
        previewHeight: Int = 1600,
        previewContent: String? = "data:image/webp;base64,AAAA",
        uniqueId: Uuid? = Uuid.random(),
        created: Long = 1_699_000_000_000L,
        isEncrypted: Boolean = false,
        lastModified: Long? = null,
        thumbnails: List<ThumbnailDescriptor>? = null,
    ): HomebaseFile {
        val driveId = Uuid.random()
        return HomebaseFile(
            fileId = Uuid.random(),
            driveId = driveId,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            keyHeader = KeyHeader.empty(),
            fileMetadata = FileMetadata(
                created = UnixTimeUtc(created),
                isEncrypted = isEncrypted,
                appData = AppFileMetaData(
                    uniqueId = uniqueId,
                    fileType = PhotoConfig.PHOTO_FILE_TYPE,
                    dataType = PhotoConfig.PHOTO_DATA_TYPE,
                    userDate = userDate,
                    previewThumbnail = EmbeddedThumb(
                        pixelWidth = previewWidth,
                        pixelHeight = previewHeight,
                        contentType = "image/webp",
                        content = previewContent,
                    ),
                ),
                payloads = listOf(
                    PayloadDescriptor(
                        key = PhotoConfig.PAYLOAD_KEY,
                        contentType = contentType,
                        thumbnails = thumbnails,
                        lastModified = lastModified,
                    ),
                ),
            ),
            serverMetadata = ServerMetadata(),
        )
    }

    @Test
    fun mapsCoreFieldsFromHomebaseFile() {
        val hbf = file(contentType = "image/jpeg", userDate = 1_700_000_000_000L)
        val item = PhotoMapper.fromHomebaseFile(hbf)

        assertEquals(hbf.fileId, item.fileId)
        assertEquals(hbf.driveId, item.driveId)
        assertEquals(hbf.fileMetadata.appData.uniqueId, item.uniqueId)
        assertEquals(1_700_000_000_000L, item.userDate)
        assertEquals(PhotoConfig.PAYLOAD_KEY, item.payloadKey)
    }

    @Test
    fun videoIsDecidedByPayloadContentType() {
        assertTrue(PhotoMapper.fromHomebaseFile(file(contentType = "video/mp4")).isVideo)
        assertTrue(PhotoMapper.fromHomebaseFile(file(contentType = "video/quicktime")).isVideo)
        assertFalse(PhotoMapper.fromHomebaseFile(file(contentType = "image/jpeg")).isVideo)
        assertFalse(PhotoMapper.fromHomebaseFile(file(contentType = "image/webp")).isVideo)
    }

    @Test
    fun mapsCryptoAndPayloadContextThroughForAndroidCoilPath() {
        val thumbs = listOf(
            ThumbnailDescriptor(pixelWidth = 300, pixelHeight = 400, contentType = "image/webp"),
            ThumbnailDescriptor(pixelWidth = 1200, pixelHeight = 1600, contentType = "image/webp"),
        )
        val item = PhotoMapper.fromHomebaseFile(
            file(
                contentType = "image/jpeg",
                isEncrypted = true,
                lastModified = 42L,
                thumbnails = thumbs,
            ),
        )

        // KeyHeader.empty() compares by (zero) iv + aesKey contents, so equality is meaningful here.
        assertEquals(KeyHeader.empty(), item.keyHeader)
        assertTrue(item.isEncrypted)
        assertEquals("image/jpeg", item.payloadContentType)
        assertEquals(42L, item.lastModified)
        assertEquals(listOf(ImageSize(300, 400), ImageSize(1200, 1600)), item.thumbSizes)
    }

    @Test
    fun cryptoContextDefaultsWhenUnencryptedAndNoThumbnails() {
        val item = PhotoMapper.fromHomebaseFile(file(contentType = "image/jpeg"))
        assertFalse(item.isEncrypted)
        assertNull(item.lastModified)
        assertTrue(item.thumbSizes.isEmpty())
    }

    @Test
    fun pixelDimsAndPlaceholderComeFromPreviewThumbnail() {
        val item = PhotoMapper.fromHomebaseFile(
            file(contentType = "image/jpeg", previewWidth = 900, previewHeight = 1200, previewContent = "BLURHASH"),
        )
        assertEquals(900, item.pixelWidth)
        assertEquals(1200, item.pixelHeight)
        assertEquals("BLURHASH", item.previewPlaceholder)
    }

    @Test
    fun missingPreviewThumbnailYieldsZeroDimsAndNullPlaceholder() {
        val driveId = Uuid.random()
        val hbf = HomebaseFile(
            fileId = Uuid.random(),
            driveId = driveId,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            keyHeader = KeyHeader.empty(),
            fileMetadata = FileMetadata(
                created = UnixTimeUtc(1L),
                appData = AppFileMetaData(
                    fileType = PhotoConfig.PHOTO_FILE_TYPE,
                    userDate = 5L,
                    previewThumbnail = null,
                ),
                payloads = listOf(PayloadDescriptor(key = PhotoConfig.PAYLOAD_KEY, contentType = "image/jpeg")),
            ),
            serverMetadata = ServerMetadata(),
        )
        val item = PhotoMapper.fromHomebaseFile(hbf)
        assertEquals(0, item.pixelWidth)
        assertEquals(0, item.pixelHeight)
        assertNull(item.previewPlaceholder)
    }

    @Test
    fun userDateFallsBackToCreatedWhenAppDataUserDateMissing() {
        val driveId = Uuid.random()
        val hbf = HomebaseFile(
            fileId = Uuid.random(),
            driveId = driveId,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            keyHeader = KeyHeader.empty(),
            fileMetadata = FileMetadata(
                created = UnixTimeUtc(1_650_000_000_000L),
                appData = AppFileMetaData(
                    fileType = PhotoConfig.PHOTO_FILE_TYPE,
                    userDate = null, // missing → fall back to created
                ),
                payloads = listOf(PayloadDescriptor(key = PhotoConfig.PAYLOAD_KEY, contentType = "image/jpeg")),
            ),
            serverMetadata = ServerMetadata(),
        )
        assertEquals(1_650_000_000_000L, PhotoMapper.fromHomebaseFile(hbf).userDate)
    }
}
