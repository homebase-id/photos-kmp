package id.homebase.photos.backup

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.image.ImageTestHelper
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.io.encoding.Base64
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Spec §2 format-verification gate. Builds the full upload descriptor for a fixture image and
 * diffs it field-by-field against a real synced Photos row (`real-photo-row.json`). Diffs the
 * appData / payload-descriptor / thumbnail-tier / ACL SUB-shapes — NOT the whole envelope — since
 * the fixture is the stored HomebaseFile (keyHeader + fileMetadata + serverMetadata) while the
 * builder emits an UploadFileRequest, and some fields legitimately differ (the fixture is a
 * transit-synced row with fileMetadata.isEncrypted=false; the builder sets isEncrypted=true).
 *
 * Any UNEXPLAINED divergence must fail — do not weaken this test to make it pass (STOP condition).
 */
class FormatGateTest {

    private val fixture: kotlinx.serialization.json.JsonObject by lazy {
        val raw = this::class.java.getResourceAsStream("/real-photo-row.json")!!.readBytes().decodeToString()
        Json.parseToJsonElement(raw).jsonObject
    }

    private suspend fun buildRequest(): UploadFileRequest {
        val driveId = Uuid.parse(fixture["driveId"]!!.jsonPrimitive.content)
        val builder = PhotoFileBuilder(
            fileOps = JvmFileOperationsProvider(),
            driveId = driveId,
            zoneProvider = { TimeZone.UTC },
        )
        val bytes = ImageTestHelper.loadImage("whitespace-issue.png") // 3000x2000 PNG → all 3 tiers
        val asset = LibraryAsset(
            deviceAssetId = "fmt-gate-1",
            fileName = "whitespace-issue.png",
            mimeType = "image/png",                       // mirrors the fixture payload contentType
            takenAtMillis = 1684679888366L,               // no EXIF → userDate falls back to this
            addedAtMillis = null,
            sizeBytes = bytes.size.toLong(),
        )
        return builder.build(asset, bytes)
    }

    @Test
    fun builtDescriptor_matchesRealRowShape() = runTest {
        val request = buildRequest()
        val appData = request.metadata.appData
        val fixtureAppData = fixture["fileMetadata"]!!.jsonObject["appData"]!!.jsonObject
        val fixturePayload = fixture["fileMetadata"]!!.jsonObject["payloads"]!!.jsonArray[0].jsonObject

        // ---- drive + top-level ----
        assertEquals(Uuid.parse(fixture["driveId"]!!.jsonPrimitive.content), request.driveId)

        // ---- appData scalars ----
        assertEquals(fixtureAppData["fileType"]!!.jsonPrimitive.int, appData.fileType)
        assertEquals(fixtureAppData["dataType"]!!.jsonPrimitive.int, appData.dataType)
        assertEquals(0, fixtureAppData["fileType"]!!.jsonPrimitive.int, "sanity: fixture fileType is 0")
        assertEquals(fixtureAppData["userDate"]!!.jsonPrimitive.long, appData.userDate)
        assertTrue(fixtureAppData["tags"]!!.jsonArray.isEmpty(), "fixture tags is empty")
        assertEquals(emptyList(), appData.tags, "builder ships no album tag this wave")
        assertEquals(0, fixtureAppData["archivalStatus"]!!.jsonPrimitive.int)
        assertEquals(0, appData.archivalStatus!!.value)
        assertNotNull(appData.uniqueId, "deterministic uniqueId must be set (outbox requires it)")

        // ---- builder always encrypts (asymmetry with the transit-synced fixture) ----
        assertTrue(request.metadata.isEncrypted, "builder uploads encrypted regardless of the fixture's transit flag")

        // ---- ACL: fixture carries it under serverMetadata; upload under metadata ----
        val fixtureAcl = fixture["serverMetadata"]!!.jsonObject["accessControlList"]!!.jsonObject
        assertEquals("owner", fixtureAcl["requiredSecurityGroup"]!!.jsonPrimitive.content)
        assertEquals("owner", request.metadata.accessControlList!!.requiredSecurityGroup)

        // ---- content JSON shape: decrypt with the file key, compare key sets ----
        val decrypted = request.keyHeader.decrypt(Base64.decode(appData.content!!)).decodeToString()
        val builtContent = Json.parseToJsonElement(decrypted).jsonObject
        val fixtureContent = Json.parseToJsonElement(fixtureAppData["content"]!!.jsonPrimitive.content).jsonObject
        assertEquals(fixtureContent.keys, builtContent.keys, "content key set must match the real row")
        assertNotNull(builtContent["camera"]!!.jsonObject, "camera is an object")
        assertNotNull(builtContent["captureDetails"]!!.jsonObject, "captureDetails is an object")
        assertEquals("whitespace-issue.png", builtContent["originalFileName"]!!.jsonPrimitive.content)

        // ---- previewThumbnail: plaintext webp, tagged with ORIGINAL dims (not the 20px tiny) ----
        val preview = appData.previewThumbnail
        assertNotNull(preview)
        assertEquals("image/webp", preview.contentType)
        assertEquals(3000, preview.pixelWidth, "preview carries the source width, not 20")
        assertEquals(2000, preview.pixelHeight)
        assertTrue(preview.pixelWidth > 20 && preview.pixelHeight > 20, "matches the fixture rule: original dims")
        val previewBytes = Base64.decode(preview.content!!)
        ImageTestHelper.assertValidWebp(previewBytes) // plaintext RIFF/WEBP, NOT encrypted
        assertEquals(2700, fixtureAppData["previewThumbnail"]!!.jsonObject["pixelWidth"]!!.jsonPrimitive.int,
            "sanity: fixture preview dims are the original (2700), not 20")

        // ---- payload descriptor ----
        assertEquals(1, request.payloads.size)
        val payload = request.payloads[0]
        assertEquals(fixturePayload["key"]!!.jsonPrimitive.content, payload.key)
        assertEquals("dflt_key", payload.key)
        assertEquals(fixturePayload["contentType"]!!.jsonPrimitive.content, payload.contentType)
        assertEquals("image/png", payload.contentType)
        assertTrue(payload.isPreEncrypted, "the copied upload path streams RAW — payload MUST be pre-encrypted")
        val payloadIv = payload.iv
        assertNotNull(payloadIv)
        assertEquals(16, payloadIv.size)
        assertEquals(16, request.keyHeader.iv.size)
        assertFalse(payloadIv.contentEquals(request.keyHeader.iv),
            "per-payload IV must differ from the file keyHeader IV (per-payload-IV rule)")
        assertEquals(16, Base64.decode(fixturePayload["iv"]!!.jsonPrimitive.content).size, "fixture payload IV is 16 bytes")

        // ---- thumbnail tiers: same max-dim set + all webp, keyed dflt_key ----
        val fixtureThumbMaxDims = fixturePayload["thumbnails"]!!.jsonArray.map {
            val o = it.jsonObject
            max(o["pixelWidth"]!!.jsonPrimitive.int, o["pixelHeight"]!!.jsonPrimitive.int)
        }.sorted()
        assertEquals(listOf(20, 300, 1200), fixtureThumbMaxDims, "sanity: fixture tiers are 20/300/1200")
        val builtThumbMaxDims = request.thumbnails.map { max(it.pixelWidth, it.pixelHeight) }.sorted()
        assertEquals(fixtureThumbMaxDims, builtThumbMaxDims, "built tiers must match the real row's tiers")
        assertTrue(request.thumbnails.all { it.contentType == "image/webp" }, "all thumbnails webp")
        assertTrue(request.thumbnails.all { it.key == "dflt_key" }, "all thumbnails keyed to the payload")

        // ---- crypto round-trip: payload + thumbnails are ciphertext under ONE aesKey + payload IV ----
        val payloadCipherKey = KeyHeader(iv = payloadIv, aesKey = request.keyHeader.aesKey)

        // Thumbnail bytes decrypt to a valid webp with the shared key + payload IV.
        val firstThumbPlain = payloadCipherKey.decrypt(request.thumbnails.first().thumbnailBytes)
        ImageTestHelper.assertValidWebp(firstThumbPlain)

        // Payload file on disk is ciphertext; decrypts byte-for-byte back to the original.
        val originalBytes = ImageTestHelper.loadImage("whitespace-issue.png")
        val cipherOnDisk = JvmFileOperationsProvider().readFileBytes(payload.filePath)
        assertFalse(cipherOnDisk.contentEquals(originalBytes), "payload on disk must NOT be plaintext")
        val decryptedPayload = payloadCipherKey.decrypt(cipherOnDisk)
        assertTrue(decryptedPayload.contentEquals(originalBytes), "decrypted payload must equal the original bytes (byte-for-byte)")
    }
}
