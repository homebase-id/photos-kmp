package id.homebase.photos.data

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.photos.PhotoConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Album identity is the content `tag` (bare hex, as the official app mints it), with `uniqueId`
 * as the fallback — NOT `appData.tags`, which is empty on official album files.
 */
class AlbumMapperTest {

    private fun albumFile(
        uniqueId: Uuid? = null,
        tags: List<Uuid>? = emptyList(),
        content: String? = null,
    ): HomebaseFile = HomebaseFile(
        fileId = Uuid.random(),
        driveId = Uuid.random(),
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader.empty(),
        fileMetadata = FileMetadata(
            created = UnixTimeUtc(1_700_000_000_000L),
            appData = AppFileMetaData(
                uniqueId = uniqueId,
                fileType = PhotoConfig.ALBUM_FILE_TYPE,
                tags = tags,
                content = content,
            ),
        ),
        serverMetadata = ServerMetadata(),
    )

    @Test
    fun officialAppFixture_mapsNameDescriptionAndTag() {
        // Exactly what photo-app's AlbumProvider writes: empty appData.tags, uniqueId == tag,
        // content {name, description, tag} with the tag in bare hex.
        val hbf = albumFile(
            uniqueId = TAG,
            tags = emptyList(),
            content = """{"name":"Summer Trip","description":"Beach week","tag":"$TAG_HEX"}""",
        )

        val album = AlbumMapper.fromHomebaseFile(hbf)

        assertNotNull(album)
        assertEquals(hbf.fileId, album.fileId)
        assertEquals(TAG, album.albumId)
        assertEquals("Summer Trip", album.name)
        assertEquals("Beach week", album.description)
        assertNull(album.coverFileId, "official albums carry no cover")
    }

    @Test
    fun contentTagWins_overAppDataTags() {
        val album = AlbumMapper.fromHomebaseFile(
            albumFile(tags = listOf(Uuid.random()), content = """{"name":"Hikes","tag":"$TAG_HEX"}"""),
        )

        assertEquals(TAG, album?.albumId)
    }

    @Test
    fun ourCoverExtension_isParsed() {
        val album = AlbumMapper.fromHomebaseFile(
            albumFile(content = """{"name":"Hikes","tag":"$TAG_HEX","coverFileId":"$COVER_ID"}"""),
        )

        assertEquals(Uuid.parse(COVER_ID), album?.coverFileId)
    }

    @Test
    fun unknownContentFields_areIgnored() {
        val album = AlbumMapper.fromHomebaseFile(
            albumFile(content = """{"name":"Hikes","tag":"$TAG_HEX","futureField":{"a":1}}"""),
        )

        assertEquals("Hikes", album?.name)
        assertEquals(TAG, album?.albumId)
    }

    @Test
    fun missingContentTag_fallsBackToUniqueId() {
        val album = AlbumMapper.fromHomebaseFile(
            albumFile(uniqueId = TAG, content = """{"name":"Hikes"}"""),
        )

        assertEquals(TAG, album?.albumId)
        assertEquals("Hikes", album?.name)
    }

    @Test
    fun taglessContentAndNoUniqueId_isSkipped() {
        assertNull(AlbumMapper.fromHomebaseFile(albumFile(content = """{"name":"Hikes"}""")))
        assertNull(AlbumMapper.fromHomebaseFile(albumFile(content = null)))
        assertNull(AlbumMapper.fromHomebaseFile(albumFile(content = "not json")))
    }

    @Test
    fun old900StyleFile_isNoLongerAnAlbum() {
        // Pre-Batch-C rows identified the album by appData.tags.first() — that is now ignored,
        // so a file whose only "identity" is a tag maps to nothing.
        val legacy = albumFile(
            tags = listOf(TAG),
            content = """{"name":"Summer Trip","coverFileId":"$COVER_ID"}""",
        )

        assertNull(AlbumMapper.fromHomebaseFile(legacy))
    }

    @Test
    fun blankOrUnparsableName_fallsBackToUntitled() {
        assertEquals(
            "Untitled",
            AlbumMapper.fromHomebaseFile(albumFile(content = """{"name":"  ","tag":"$TAG_HEX"}"""))?.name,
        )
        assertEquals("Untitled", AlbumMapper.fromHomebaseFile(albumFile(uniqueId = TAG, content = "not json"))?.name)
    }

    @Test
    fun unparsableCoverFileId_dropsCoverOnly() {
        val album = AlbumMapper.fromHomebaseFile(
            albumFile(content = """{"name":"Hikes","tag":"$TAG_HEX","coverFileId":"garbage"}"""),
        )

        assertNotNull(album)
        assertEquals("Hikes", album.name)
        assertNull(album.coverFileId)
    }

    private companion object {
        val TAG: Uuid = Uuid.parse("11111111-2222-3333-4444-555555555555")
        const val TAG_HEX = "11111111222233334444555555555555" // official getNewId(): dashes stripped
        const val COVER_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    }
}
