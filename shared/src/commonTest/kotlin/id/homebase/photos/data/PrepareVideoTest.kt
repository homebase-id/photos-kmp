package id.homebase.photos.data

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.BytesResponse
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.video.VideoMetadata
import id.homebase.api.video.VideoPlayerData
import id.homebase.api.video.VideoPrefetchDriveAccess
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * prepareVideo building blocks (Batch B). The full `streamPayloadDecryptedToPath` round-trip
 * needs the real Ktor/credentials stack, so this covers the decision layer with fakes instead:
 * the segmented/HLS → null gate ([isSegmentedVideo]) and the temp-file name + extension
 * derivation ([viewerVideoFileName]).
 */
class PrepareVideoTest {

    /** Drive access that must never be touched — complete stubs resolve without a fetch. */
    private class ExplodingDriveAccess : VideoPrefetchDriveAccess {
        override suspend fun prefetchPayload(
            driveId: Uuid, fileId: Uuid, key: String, onDownloadProgress: ((Float) -> Unit)?,
        ) = error("unexpected drive access")

        override suspend fun prefetchPayloadChunk(
            driveId: Uuid, fileId: Uuid, key: String,
            chunkStart: Long, chunkLength: Long, onDownloadProgress: ((Float) -> Unit)?,
        ) = error("unexpected drive access")

        override suspend fun getPayloadBytesDecrypted(
            driveId: Uuid, fileId: Uuid, key: String, keyHeader: KeyHeader,
            chunkStart: Long?, chunkLength: Long?, onDownloadProgress: ((Float) -> Unit)?,
        ): BytesResponse? = error("unexpected drive access")
    }

    /** Serves [metadataJson] as the descriptor payload for the incomplete-stub fetch path. */
    private class ServingDriveAccess(private val metadataJson: String) : VideoPrefetchDriveAccess {
        override suspend fun prefetchPayload(
            driveId: Uuid, fileId: Uuid, key: String, onDownloadProgress: ((Float) -> Unit)?,
        ) {}

        override suspend fun prefetchPayloadChunk(
            driveId: Uuid, fileId: Uuid, key: String,
            chunkStart: Long, chunkLength: Long, onDownloadProgress: ((Float) -> Unit)?,
        ) {}

        override suspend fun getPayloadBytesDecrypted(
            driveId: Uuid, fileId: Uuid, key: String, keyHeader: KeyHeader,
            chunkStart: Long?, chunkLength: Long?, onDownloadProgress: ((Float) -> Unit)?,
        ): BytesResponse = BytesResponse(metadataJson.encodeToByteArray(), "application/json")
    }

    private fun playerData(descriptorContent: String?): VideoPlayerData = VideoPlayerData(
        fileId = Uuid.random(),
        driveId = Uuid.random(),
        payloadKey = "dflt_key",
        keyHeader = KeyHeader.newRandom16(),
        descriptorContent = descriptorContent,
    )

    private fun metadataJson(isSegmented: Boolean, complete: Boolean = true): String =
        OdinSystemSerializer.serialize(
            VideoMetadata(
                mimeType = "video/mp4",
                isDescriptorContentComplete = complete,
                isSegmented = isSegmented,
                key = "dflt_key",
            ),
        )

    @Test
    fun segmentedDescriptor_gatesToNullBranch() = runTest {
        assertTrue(isSegmentedVideo(playerData(metadataJson(isSegmented = true)), ExplodingDriveAccess()))
    }

    @Test
    fun plainMp4Descriptor_passesThrough() = runTest {
        assertFalse(isSegmentedVideo(playerData(metadataJson(isSegmented = false)), ExplodingDriveAccess()))
    }

    @Test
    fun missingOrGarbageDescriptor_countsAsPlain() = runTest {
        assertFalse(isSegmentedVideo(playerData(null), ExplodingDriveAccess()))
        assertFalse(isSegmentedVideo(playerData("not json at all"), ExplodingDriveAccess()))
    }

    @Test
    fun incompleteStub_fetchesFullMetadataBeforeDeciding() = runTest {
        val stub = metadataJson(isSegmented = false, complete = false)
        val served = ServingDriveAccess(metadataJson(isSegmented = true))
        assertTrue(isSegmentedVideo(playerData(stub), served))
    }

    @Test
    fun viewerVideoFileName_derivesExtensionFromMime() {
        val id = Uuid.random()
        assertEquals("viewer_$id.mp4", viewerVideoFileName(id, "video/mp4"))
        assertEquals("viewer_$id.mov", viewerVideoFileName(id, "video/quicktime"))
        assertEquals("viewer_$id.m4v", viewerVideoFileName(id, "video/x-m4v"))
        assertEquals("viewer_$id.webm", viewerVideoFileName(id, "video/webm"))
        assertEquals("viewer_$id.mp4", viewerVideoFileName(id, null))
        assertEquals("viewer_$id.mp4", viewerVideoFileName(id, "video/3gpp"))
    }
}
