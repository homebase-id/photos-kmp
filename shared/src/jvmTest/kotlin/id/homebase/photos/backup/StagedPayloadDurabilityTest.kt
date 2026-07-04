package id.homebase.photos.backup

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.StagedPayloadMissingException
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.file.OUTBOX_STAGING_DIR_NAME
import id.homebase.api.image.ImageTestHelper
import id.homebase.api.sync.database.classifyPermanentFailure
import id.homebase.photos.PhotoConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Tripwire for BLOCKER 2 (durable staging) + BLOCKER 1.4 (missing-file is PERMANENT),
 * end-to-end through the real [PhotoFileBuilder] and real [DriveUploadProvider].
 *
 *  - The pre-encrypted payload is staged into the DURABLE outbox staging dir, NOT the
 *    OS-reclaimable cacheDir that the startup CacheSweeper wipes on launch.
 *  - If that staged file is gone by drain time (the ~19 inert Redmi rows), the upload
 *    fails PERMANENT on attempt 1 via [StagedPayloadMissingException] — the row drops
 *    instead of retry-spamming ~48h as a phantom "Network failure".
 */
class StagedPayloadDurabilityTest {

    private val fileOps = JvmFileOperationsProvider()

    private suspend fun buildRequest() = PhotoFileBuilder(
        fileOps = fileOps,
        driveId = Uuid.parseHex(PhotoConfig.DRIVE_ALIAS),
        zoneProvider = { TimeZone.UTC },
    ).build(
        LibraryAsset(
            deviceAssetId = "durability-1",
            fileName = "dice.png",
            mimeType = "image/png",
            takenAtMillis = 1_000_000L,
            addedAtMillis = null,
            sizeBytes = 0L,
        ),
        ImageTestHelper.loadImage("dice.png"),
    )

    @Test
    fun payloadIsStagedInDurableOutboxDir_notCache() = runTest {
        val request = buildRequest()
        val path = request.payloads.single().filePath
        assertTrue(
            path.contains(OUTBOX_STAGING_DIR_NAME),
            "payload must stage into the durable outbox dir (survives restart + CacheSweeper); was $path",
        )
        assertTrue(fileOps.getFileSize(path) > 0L, "staged ciphertext must exist on disk")
    }

    @Test
    fun missingStagedPayloadFailsPermanentAtUploadPreflight() = runTest {
        val request = buildRequest()
        val path = request.payloads.single().filePath

        // Simulate the swept/vanished payload (what actually happened to the Redmi rows).
        fileOps.deleteTempFile(path)
        assertEquals(0L, fileOps.getFileSize(path), "precondition: staged file is gone")

        val provider = DriveUploadProvider(
            httpClient = HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
            credentialsManager = CredentialsManager(), // preflight runs before requireCreds()
            fileOperationsProvider = fileOps,
        )

        // The pre-flight throws BEFORE any network call — deterministic, not a phantom retry.
        val ex = assertFailsWith<StagedPayloadMissingException> { provider.uploadFile(request) }
        assertEquals(PhotoConfig.PAYLOAD_KEY, ex.payloadKey)

        // And the classifier drops it on attempt 1 rather than burning ~48h of retries.
        assertNotNull(
            classifyPermanentFailure(ex),
            "a missing staged payload must classify as a PERMANENT outbox failure",
        )
    }
}
