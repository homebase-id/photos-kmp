package id.homebase.photos.data

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.photos.PhotoConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The album WRITE schema, field for field against the official Odin Photos format. A header
 * update replaces appData wholesale, so the parity assertions here are what stand between us
 * and silently nulling a field (or bricking payloads with a fresh aesKey) on the server.
 */
class AlbumWriteSchemaTest {

    private val aesKey = SecureByteArray(ByteArray(16) { 7 })
    private val fileIv = ByteArray(16) { 3 }

    private fun photoFile(
        tags: List<Uuid>? = null,
        content: String? = """{"originalFileName":"IMG_1.jpg"}""",
        encrypted: Boolean = true,
    ): HomebaseFile = HomebaseFile(
        fileId = PHOTO_FILE_ID,
        driveId = DRIVE_ID,
        serverFileIsEncrypted = encrypted,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader(iv = fileIv, aesKey = aesKey),
        fileMetadata = FileMetadata(
            created = UnixTimeUtc(1_700_000_000_000L),
            isEncrypted = encrypted,
            versionTag = VERSION_TAG,
            appData = AppFileMetaData(
                uniqueId = PHOTO_UNIQUE_ID,
                tags = tags,
                fileType = PhotoConfig.PHOTO_FILE_TYPE,
                dataType = PhotoConfig.PHOTO_DATA_TYPE,
                groupId = GROUP_ID,
                userDate = 1_699_000_000_000L,
                content = content,
                previewThumbnail = EmbeddedThumb(20, 27, "image/webp", "AAAA"),
                archivalStatus = ArchivalStatus.None,
            ),
        ),
        serverMetadata = ServerMetadata(),
    )

    // --- CREATE ---------------------------------------------------------------------------

    @Test
    fun albumAppData_matchesTheOfficialSchema() {
        val tag = Uuid.parse("11111111-2222-3333-4444-555555555555")

        val appData = AlbumWriteSchema.albumAppData(tag, "Summer Trip")

        assertEquals(400, appData.fileType, "official AlbumDefinitionFileType")
        assertEquals(400, PhotoConfig.ALBUM_FILE_TYPE)
        assertEquals(0, appData.dataType)
        assertEquals(tag, appData.uniqueId, "uniqueId == tag")
        assertEquals(emptyList<Uuid>(), appData.tags, "identity is the content tag, appData.tags stays empty")
        assertNull(appData.userDate, "official albums set no userDate")
        assertNull(appData.previewThumbnail)
        assertEquals("""{"name":"Summer Trip","tag":"11111111222233334444555555555555"}""", appData.content)
    }

    @Test
    fun albumTag_isBareLowercaseHex() {
        val tag = newAlbumTag()
        val content = Json.parseToJsonElement(newAlbumContentJson("X", tag)) as JsonObject

        val raw = content["tag"]!!.jsonPrimitive.content
        assertTrue(Regex("^[0-9a-f]{32}$").matches(raw), "official getNewId() strips the dashes: $raw")
        assertEquals(tag, parseLenientUuid(raw))
    }

    @Test
    fun albumContent_dropsAbsentDescription_andKeepsItWhenGiven() {
        val tag = Uuid.parse("11111111-2222-3333-4444-555555555555")

        assertFalse(newAlbumContentJson("X", tag).contains("description"))
        assertEquals(
            """{"name":"X","description":"Beach week","tag":"11111111222233334444555555555555"}""",
            newAlbumContentJson("X", tag, "Beach week"),
        )
    }

    @Test
    fun albumCreateRequest_isHeaderOnlyOwnerOnlyAndEncrypted() = runTest {
        val tag = newAlbumTag()

        val request = AlbumWriteSchema.albumCreateRequest(DRIVE_ID, tag, "Summer Trip")

        assertEquals(DRIVE_ID, request.driveId)
        assertTrue(request.payloads.isEmpty(), "albums carry no payloads")
        assertTrue(request.thumbnails.isEmpty())
        assertTrue(request.metadata.isEncrypted)
        assertFalse(request.metadata.allowDistribution)
        assertEquals(
            AccessControlList(requiredSecurityGroup = SecurityGroupType.Owner.value),
            request.metadata.accessControlList,
        )
        assertNull(request.metadata.versionTag, "a create carries no versionTag")
        // content ships encrypted under the file key header
        val plaintext = AlbumWriteSchema.albumAppData(tag, "Summer Trip").content
        assertNotEquals(plaintext, request.metadata.appData.content)
        val decrypted = request.keyHeader.decrypt(Base64.decode(request.metadata.appData.content!!))
        assertEquals(plaintext, decrypted.decodeToString())
    }

    // --- carryOverAppData -------------------------------------------------------------------

    @Test
    fun carryOverAppData_carriesEveryFieldByteForByte() {
        val existing = photoFile(tags = listOf(OTHER_ALBUM)).fileMetadata.appData

        val carried = AlbumWriteSchema.carryOverAppData(existing)

        assertEquals(existing.uniqueId, carried.uniqueId)
        assertEquals(existing.tags, carried.tags)
        assertEquals(existing.fileType, carried.fileType)
        assertEquals(existing.dataType, carried.dataType)
        assertEquals(existing.userDate, carried.userDate)
        assertEquals(existing.groupId, carried.groupId)
        assertEquals(existing.archivalStatus, carried.archivalStatus)
        assertEquals(existing.content, carried.content)
        assertEquals(existing.previewThumbnail, carried.previewThumbnail)
    }

    @Test
    fun carryOverAppData_changesOnlyWhatIsPassed() {
        val existing = photoFile(tags = listOf(OTHER_ALBUM)).fileMetadata.appData

        val retagged = AlbumWriteSchema.carryOverAppData(existing, tags = listOf(ALBUM_TAG))
        assertEquals(listOf(ALBUM_TAG), retagged.tags)
        assertEquals(existing.content, retagged.content)
        assertEquals(existing.previewThumbnail, retagged.previewThumbnail)

        val recontented = AlbumWriteSchema.carryOverAppData(existing, content = """{"name":"New"}""")
        assertEquals("""{"name":"New"}""", recontented.content)
        assertEquals(existing.tags, recontented.tags)
    }

    // --- membership tag math ----------------------------------------------------------------

    @Test
    fun withTag_addsOnceAndPreservesOtherAlbums() {
        assertEquals(listOf(ALBUM_TAG), AlbumWriteSchema.withTag(null, ALBUM_TAG))
        assertEquals(listOf(OTHER_ALBUM, ALBUM_TAG), AlbumWriteSchema.withTag(listOf(OTHER_ALBUM), ALBUM_TAG))
        assertEquals(
            listOf(OTHER_ALBUM, ALBUM_TAG),
            AlbumWriteSchema.withTag(listOf(OTHER_ALBUM, ALBUM_TAG), ALBUM_TAG),
            "already a member — no duplicate tag",
        )
    }

    @Test
    fun withoutTag_dropsOnlyTheAlbumTag() {
        assertEquals(
            listOf(OTHER_ALBUM),
            AlbumWriteSchema.withoutTag(listOf(OTHER_ALBUM, ALBUM_TAG), ALBUM_TAG),
        )
        assertEquals(emptyList<Uuid>(), AlbumWriteSchema.withoutTag(null, ALBUM_TAG))
    }

    // --- UPDATE ------------------------------------------------------------------------------

    @Test
    fun headerUpdateRequest_keepsAesKeyRotatesIvAndCarriesVersionTag() = runTest {
        val existing = photoFile(tags = listOf(OTHER_ALBUM))
        val appData = AlbumWriteSchema.carryOverAppData(
            existing.fileMetadata.appData,
            tags = AlbumWriteSchema.withTag(existing.fileMetadata.appData.tags, ALBUM_TAG),
        )

        val request = AlbumWriteSchema.headerUpdateRequest(DRIVE_ID, existing, appData)

        assertEquals(existing.fileId, request.fileId)
        assertEquals(DRIVE_ID, request.driveId)
        // A fresh aesKey would make every payload and thumbnail undecryptable.
        assertContentEquals(aesKey.unsafeBytes, request.keyHeader!!.aesKey.unsafeBytes)
        assertFalse(request.keyHeader!!.iv.contentEquals(fileIv), "IV must rotate per revision")
        assertEquals(VERSION_TAG, request.metadata.versionTag)
        assertEquals(listOf(OTHER_ALBUM, ALBUM_TAG), request.metadata.appData.tags)
        assertTrue(request.metadata.isEncrypted)
        assertNull(request.metadata.accessControlList, "omitted so the server keeps the existing ACL")
    }

    @Test
    fun headerUpdateRequest_shipsHeaderOnly() = runTest {
        val existing = photoFile()

        val request = AlbumWriteSchema.headerUpdateRequest(
            DRIVE_ID,
            existing,
            AlbumWriteSchema.carryOverAppData(existing.fileMetadata.appData),
        )

        assertNull(request.payloads, "payloads stay on the server untouched")
        assertNull(request.thumbnails)
        assertTrue(request.instructions.manifest.payloadDescriptors.isNullOrEmpty())
        assertEquals(UpdateLocale.Local, request.instructions.locale)
        assertTrue(request.instructions.recipients.isEmpty())
        assertEquals(16, request.instructions.transferIv.size)
    }

    @Test
    fun headerUpdateRequest_reEncryptsContentUnderTheRotatedIv() = runTest {
        val existing = photoFile()
        val appData = AlbumWriteSchema.carryOverAppData(existing.fileMetadata.appData)

        val request = AlbumWriteSchema.headerUpdateRequest(DRIVE_ID, existing, appData)

        assertNotEquals(appData.content, request.metadata.appData.content)
        val decrypted = request.keyHeader!!.decrypt(Base64.decode(request.metadata.appData.content!!))
        assertEquals(appData.content, decrypted.decodeToString())
    }

    @Test
    fun headerUpdateRequest_onAPlaintextFile_staysPlaintext() = runTest {
        val existing = photoFile(encrypted = false)
        val appData = AlbumWriteSchema.carryOverAppData(existing.fileMetadata.appData)

        val request = AlbumWriteSchema.headerUpdateRequest(DRIVE_ID, existing, appData)

        assertNull(request.keyHeader)
        assertFalse(request.metadata.isEncrypted)
        assertEquals(appData.content, request.metadata.appData.content)
    }

    // --- album content patching (rename / set cover) -------------------------------------------

    @Test
    fun rename_changesOnlyTheNameAndKeepsUnknownOfficialFields() {
        val existing =
            """{"name":"Old","description":"d","tag":"$TAG_HEX","futureField":"keep me"}"""

        val patched = patchAlbumContent(existing, ALBUM_TAG, mapOf(FIELD_NAME to "New"))

        val obj = Json.parseToJsonElement(patched) as JsonObject
        assertEquals("New", obj[FIELD_NAME]!!.jsonPrimitive.content)
        assertEquals("d", obj["description"]!!.jsonPrimitive.content)
        assertEquals(TAG_HEX, obj[FIELD_TAG]!!.jsonPrimitive.content, "the tag is never rewritten")
        assertEquals("keep me", obj["futureField"]!!.jsonPrimitive.content)
    }

    @Test
    fun setCover_addsOurExtensionWithoutTouchingAnythingElse() {
        val existing = """{"name":"Old","tag":"$TAG_HEX"}"""
        val cover = Uuid.parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        val patched = patchAlbumContent(existing, ALBUM_TAG, mapOf(FIELD_COVER to cover.toString()))

        val obj = Json.parseToJsonElement(patched) as JsonObject
        assertEquals("Old", obj[FIELD_NAME]!!.jsonPrimitive.content)
        assertEquals(TAG_HEX, obj[FIELD_TAG]!!.jsonPrimitive.content)
        assertEquals(cover.toString(), obj[FIELD_COVER]!!.jsonPrimitive.content)
    }

    @Test
    fun patch_reinstatesTheTagWhenTheExistingContentHasNone() {
        val patched = patchAlbumContent("not json", ALBUM_TAG, mapOf(FIELD_NAME to "New"))

        val obj = Json.parseToJsonElement(patched) as JsonObject
        assertEquals("New", obj[FIELD_NAME]!!.jsonPrimitive.content)
        assertEquals(ALBUM_TAG, parseLenientUuid(obj[FIELD_TAG]!!.jsonPrimitive.content))
    }

    @Test
    fun patchedContent_roundTripsBackThroughTheMapper() {
        val patched = patchAlbumContent(
            """{"name":"Old","description":"d","tag":"$TAG_HEX","futureField":"keep me"}""",
            ALBUM_TAG,
            mapOf(FIELD_NAME to "New"),
        )

        val parsed = parseAlbumContent(patched)
        assertEquals("New", parsed?.name)
        assertEquals("d", parsed?.description)
        assertEquals(ALBUM_TAG, parseLenientUuid(parsed?.tag))
    }

    private companion object {
        val DRIVE_ID: Uuid = Uuid.random()
        val PHOTO_FILE_ID: Uuid = Uuid.random()
        val PHOTO_UNIQUE_ID: Uuid = Uuid.random()
        val GROUP_ID: Uuid = Uuid.random()
        val VERSION_TAG: Uuid = Uuid.random()
        val OTHER_ALBUM: Uuid = Uuid.random()
        val ALBUM_TAG: Uuid = Uuid.parse("11111111-2222-3333-4444-555555555555")
        const val TAG_HEX = "11111111222233334444555555555555"
    }
}
