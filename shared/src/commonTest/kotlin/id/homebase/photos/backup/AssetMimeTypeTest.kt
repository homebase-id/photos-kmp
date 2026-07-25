package id.homebase.photos.backup

import kotlin.test.Test
import kotlin.test.assertEquals

class AssetMimeTypeTest {
    @Test fun mapsKnownUtisCaseInsensitively() {
        assertEquals("image/jpeg", utiToMimeType("public.jpeg", isVideo = false))
        assertEquals("image/heic", utiToMimeType("PUBLIC.HEIC", isVideo = false))
        assertEquals("video/quicktime", utiToMimeType("com.apple.quicktime-movie", isVideo = true))
    }

    @Test fun fallsBackByMediaTypeForUnknownOrNull() {
        assertEquals("image/jpeg", utiToMimeType(null, isVideo = false))
        assertEquals("video/mp4", utiToMimeType("com.unknown.thing", isVideo = true))
    }
}
