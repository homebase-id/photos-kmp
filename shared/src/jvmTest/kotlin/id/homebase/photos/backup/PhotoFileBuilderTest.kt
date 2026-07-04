package id.homebase.photos.backup

import id.homebase.api.image.ImageMetadata
import id.homebase.api.image.ImageTestHelper
import id.homebase.api.image.readImageMetadata
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

/**
 * PhotoFileBuilder's spec §1 mapping, tested through its pure helpers so date/content/id rules are
 * pinned without image I/O — plus one real-file check that the copied EXIF reader flows through.
 */
class PhotoFileBuilderTest {

    // ---- D1: deterministic content-hash uniqueId ----

    @Test
    fun d1_uniqueId_isDeterministicFirst16OfSha256() {
        // sha256(0x01 0x02 0x03) first 16 bytes, as a UUID (pinned against an independent tool).
        assertEquals(
            Uuid.parse("039058c6-f2c0-cb49-2c53-3b0a4d14ef77"),
            deterministicPhotoUniqueId(byteArrayOf(1, 2, 3)),
        )
    }

    @Test
    fun d1_uniqueId_sameBytesSameId_differentBytesDifferentId() {
        val a = ByteArray(64) { it.toByte() }
        val b = ByteArray(64) { (it + 1).toByte() }
        assertEquals(deterministicPhotoUniqueId(a), deterministicPhotoUniqueId(a.copyOf()))
        assertNotEquals(deterministicPhotoUniqueId(a), deterministicPhotoUniqueId(b))
    }

    // ---- D2: EXIF wall-clock timezone resolution ----

    private val captured = LocalDateTime(2023, 5, 21, 14, 30, 0)

    @Test
    fun d2_offsetPresent_usesOffsetAndIgnoresDeviceZone() {
        val meta = ImageMetadata(capturedAt = captured, captureUtcOffset = UtcOffset.parse("+02:00"))
        val atUtcZone = resolvePhotoUserDate(meta, null, null, TimeZone.UTC)
        val atPlus9Zone = resolvePhotoUserDate(meta, null, null, UtcOffset(hours = 9).asTimeZone())

        assertEquals(1684672200000L, atUtcZone, "offset +02:00 must resolve regardless of device zone")
        assertEquals(atUtcZone, atPlus9Zone, "device zone must be ignored when an EXIF offset exists")
    }

    @Test
    fun d2_noOffset_interpretsWallClockInDeviceZone() {
        val meta = ImageMetadata(capturedAt = captured, captureUtcOffset = null)
        val atUtc = resolvePhotoUserDate(meta, null, null, TimeZone.UTC)
        val atPlus5 = resolvePhotoUserDate(meta, null, null, UtcOffset(hours = 5).asTimeZone())

        assertEquals(1684679400000L, atUtc)
        assertEquals(1684661400000L, atPlus5)
        assertNotEquals(atUtc, atPlus5, "the device zone must actually shift the resolved instant")
    }

    // ---- D3: no-EXIF fallbacks (DATE_TAKEN -> DATE_ADDED -> 0) ----

    @Test
    fun d3_noExif_usesDateTakenFirst() {
        assertEquals(123456789L, resolvePhotoUserDate(meta = null, takenAtMillis = 123456789L, addedAtMillis = 999L, zone = TimeZone.UTC))
    }

    @Test
    fun d3_noExifNoDateTaken_usesDateAdded() {
        assertEquals(555000L, resolvePhotoUserDate(meta = null, takenAtMillis = null, addedAtMillis = 555000L, zone = TimeZone.UTC))
    }

    @Test
    fun d3_nothingAvailable_isZero() {
        assertEquals(0L, resolvePhotoUserDate(meta = null, takenAtMillis = null, addedAtMillis = null, zone = TimeZone.UTC))
    }

    @Test
    fun d3_metaWithoutCaptureTime_stillFallsThrough() {
        val meta = ImageMetadata(cameraMake = "Canon") // no capturedAt
        assertEquals(42L, resolvePhotoUserDate(meta, takenAtMillis = 42L, addedAtMillis = null, zone = TimeZone.UTC))
    }

    // ---- D4: content JSON shape ----

    @Test
    fun d4_noExif_emptyCameraAndCaptureDetails() {
        assertEquals(
            """{"camera":{},"captureDetails":{},"originalFileName":"TheEye.png"}""",
            photoContentJson(meta = null, fileName = "TheEye.png"),
        )
    }

    @Test
    fun d4_cameraOnly_noGeolocation() {
        val meta = ImageMetadata(cameraMake = "Canon", cameraModel = "EOS 5D")
        assertEquals(
            """{"camera":{"make":"Canon","model":"EOS 5D"},"captureDetails":{},"originalFileName":"c.jpg"}""",
            photoContentJson(meta, "c.jpg"),
        )
    }

    @Test
    fun d4_fullCameraAndGeolocation() {
        val meta = ImageMetadata(
            cameraMake = "Canon", cameraModel = "EOS 5D",
            latitude = 12.5, longitude = -3.25, altitudeMeters = 100.0,
        )
        assertEquals(
            """{"camera":{"make":"Canon","model":"EOS 5D"},"captureDetails":{"geolocation":{"latitude":12.5,"longitude":-3.25,"altitude":100.0}},"originalFileName":"g.jpg"}""",
            photoContentJson(meta, "g.jpg"),
        )
    }

    @Test
    fun d4_geolocationWithoutAltitude_omitsAltitude() {
        val meta = ImageMetadata(latitude = 1.0, longitude = 2.0, altitudeMeters = null)
        assertEquals(
            """{"camera":{},"captureDetails":{"geolocation":{"latitude":1.0,"longitude":2.0}},"originalFileName":"x.jpg"}""",
            photoContentJson(meta, "x.jpg"),
        )
    }

    @Test
    fun d4_realExifFile_flowsCameraMakeModelThrough() {
        // canon_5d_srgb.jpg: camera make/model + capture date, but the JVM reader yields no GPS.
        val meta = readImageMetadata(ImageTestHelper.loadImage("canon_5d_srgb.jpg"))
        assertEquals(
            """{"camera":{"make":"Canon","model":"Canon EOS 5D Mark IV"},"captureDetails":{},"originalFileName":"canon_5d_srgb.jpg"}""",
            photoContentJson(meta, "canon_5d_srgb.jpg"),
        )
    }
}
