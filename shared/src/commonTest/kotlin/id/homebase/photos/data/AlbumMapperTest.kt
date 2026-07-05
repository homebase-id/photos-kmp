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

class AlbumMapperTest {

    private fun albumFile(
        tags: List<Uuid>? = listOf(Uuid.random()),
        content: String? = """{"name":"Summer Trip","coverFileId":"$COVER_ID"}""",
    ): HomebaseFile = HomebaseFile(
        fileId = Uuid.random(),
        driveId = Uuid.random(),
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader.empty(),
        fileMetadata = FileMetadata(
            created = UnixTimeUtc(1_700_000_000_000L),
            appData = AppFileMetaData(
                fileType = PhotoConfig.ALBUM_FILE_TYPE,
                tags = tags,
                content = content,
            ),
        ),
        serverMetadata = ServerMetadata(),
    )

    @Test
    fun albumFile_mapsNameCoverAndAlbumIdFromFirstTag() {
        val tag = Uuid.parse("11111111-2222-3333-4444-555555555555")
        val hbf = albumFile(tags = listOf(tag, Uuid.random()))

        val album = AlbumMapper.fromHomebaseFile(hbf)

        assertNotNull(album)
        assertEquals(hbf.fileId, album.fileId)
        assertEquals(tag, album.albumId)
        assertEquals("Summer Trip", album.name)
        assertEquals(Uuid.parse(COVER_ID), album.coverFileId)
    }

    @Test
    fun albumFile_withoutTags_isSkipped() {
        assertNull(AlbumMapper.fromHomebaseFile(albumFile(tags = null)))
        assertNull(AlbumMapper.fromHomebaseFile(albumFile(tags = emptyList())))
    }

    @Test
    fun albumFile_withBlankOrMissingContent_fallsBackToUntitled() {
        assertEquals("Untitled", AlbumMapper.fromHomebaseFile(albumFile(content = null))?.name)
        assertEquals("Untitled", AlbumMapper.fromHomebaseFile(albumFile(content = """{"name":"  "}"""))?.name)
        assertEquals("Untitled", AlbumMapper.fromHomebaseFile(albumFile(content = "not json"))?.name)
    }

    @Test
    fun albumFile_withUnparsableCoverFileId_dropsCoverOnly() {
        val album = AlbumMapper.fromHomebaseFile(
            albumFile(content = """{"name":"Hikes","coverFileId":"garbage"}"""),
        )

        assertNotNull(album)
        assertEquals("Hikes", album.name)
        assertNull(album.coverFileId)
    }

    private companion object {
        const val COVER_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    }
}
