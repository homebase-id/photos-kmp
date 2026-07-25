package id.homebase.photos

/** Static config for the Homebase Photos drive. Schema constants only — no file-build logic. */
object PhotoConfig {
    // Existing Odin "Photo Library" drive — we reuse the same drive, registered by a NEW app.
    const val DRIVE_TYPE = "2af68fe72fb84896f39f97c59d60813a"
    const val DRIVE_ALIAS = "6483b7b1f71bd43eb6896c86148668cc"

    // THE Homebase Photos appId (owner directive 2026-07-04) — matches the established Photos app registration; never mint a new one.
    const val APP_ID = "32f0bdbf-017f-4fc0-8004-2d4631182d1e"
    const val APP_NAME = "Homebase Photos"

    const val PHOTO_FILE_TYPE = 0
    const val PHOTO_DATA_TYPE = 0

    // Official Odin Photos values (photo-app PhotoTypes.ts): albums are 400; 900 is the
    // library-metadata singleton, NOT an album (Batch E reads it).
    const val ALBUM_FILE_TYPE = 400
    const val LIBRARY_METADATA_FILE_TYPE = 900

    const val PAYLOAD_KEY = "dflt_key" // satisfies ^[a-z0-9_]{8,10}$

    // tiny / grid / fullscreen-preview. SPEC values (15x20/225x300/900x1200 == these max dims on 3:4).
    // ponytail: provisional — owner sign-off vs a real Photos drive file before Batch 1 upload.
    val thumbnailMaxDimensions = listOf(20, 300, 1200)

    // Photo vs video is decided SOLELY by the payload contentType MIME (no fileType/dataType/flag).
    fun isVideo(contentType: String): Boolean = contentType.startsWith("video/")
    fun isImage(contentType: String): Boolean = contentType.startsWith("image/")
}
