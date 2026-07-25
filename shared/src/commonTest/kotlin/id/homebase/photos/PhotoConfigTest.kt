package id.homebase.photos
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoConfigTest {
    @Test fun videoMarkerIsPayloadContentType() {
        assertTrue(PhotoConfig.isVideo("video/mp4"))
        assertFalse(PhotoConfig.isVideo("image/jpeg"))
        assertTrue(PhotoConfig.isImage("image/webp"))
        assertFalse(PhotoConfig.isImage("video/mp4"))
    }
    @Test fun payloadKeyMatchesServerRegex() {
        assertTrue(Regex("^[a-z0-9_]{8,10}$").matches(PhotoConfig.PAYLOAD_KEY))
    }
    @Test fun driveGuidsAreDashless32Hex() {
        assertTrue(Regex("^[0-9a-f]{32}$").matches(PhotoConfig.DRIVE_TYPE))
        assertTrue(Regex("^[0-9a-f]{32}$").matches(PhotoConfig.DRIVE_ALIAS))
    }
    @Test fun appIdIsTheEstablishedPhotosRegistration() {
        assertTrue(PhotoConfig.APP_ID == "32f0bdbf-017f-4fc0-8004-2d4631182d1e")
    }
    // Official Odin Photos: AlbumDefinitionFileType 400, PhotoLibraryMetadataFileType 900.
    // We shipped 900 for albums, which read the library-metadata file instead — never again.
    @Test fun albumFileTypeIsTheOfficialAlbumDefinition() {
        assertTrue(PhotoConfig.ALBUM_FILE_TYPE == 400)
        assertTrue(PhotoConfig.LIBRARY_METADATA_FILE_TYPE == 900)
        assertFalse(PhotoConfig.ALBUM_FILE_TYPE == PhotoConfig.LIBRARY_METADATA_FILE_TYPE)
    }
}
