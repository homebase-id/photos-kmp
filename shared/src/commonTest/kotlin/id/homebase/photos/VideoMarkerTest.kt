package id.homebase.photos

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Photo vs video is decided SOLELY by the payload contentType MIME (spec §4). */
class VideoMarkerTest {

    @Test fun jpegIsImageNotVideo() {
        assertTrue(PhotoConfig.isImage("image/jpeg"))
        assertFalse(PhotoConfig.isVideo("image/jpeg"))
    }

    @Test fun webpIsImageNotVideo() {
        assertTrue(PhotoConfig.isImage("image/webp"))
        assertFalse(PhotoConfig.isVideo("image/webp"))
    }

    @Test fun mp4IsVideoNotImage() {
        assertTrue(PhotoConfig.isVideo("video/mp4"))
        assertFalse(PhotoConfig.isImage("video/mp4"))
    }
}
