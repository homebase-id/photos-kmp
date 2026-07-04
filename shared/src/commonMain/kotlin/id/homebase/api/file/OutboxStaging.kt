package id.homebase.api.file

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.random.Random

/**
 * Shared logic for the DURABLE outbox staging directory — the one place
 * encrypted, ready-to-transmit upload payloads live while their outbox row is
 * pending. Unlike the cache directory, the staging dir is never reclaimed by
 * the OS under storage pressure and is invisible to [CacheSweeper] (it sits
 * outside `getCacheDirectory()`), so a payload survives restarts, "Clear
 * caches", and storage-pressure events until its row drains.
 *
 * Lifecycle (the ways a staged file dies):
 *  - send success — `DriveUploadProvider.cleanupPayloadTempFiles` (delete by path);
 *  - permanent drop — `OutboxSync.cleanupPayloadsForDroppedRow` (delete by path);
 *  - logout — [wipeOutboxStaging], paired with the DB outbox-table wipe so rows
 *    and staged payloads leave together (the staging dir sits OUTSIDE cacheDir,
 *    so the logout cache sweep can't reach it).
 *
 * Mirrors chat-kmp's OutboxStaging (pin e67130cd), trimmed to what the photo
 * backup path needs (path reservation + logout wipe — no promote helper yet).
 */
const val OUTBOX_STAGING_DIR_NAME: String = "outbox-staging"

/**
 * Reserve a unique, not-yet-written path `<stagingDir>/<prefix><token><suffix>`,
 * creating [stagingDir] if needed. Writes NOTHING — this is the seam for stream
 * writers (`writeStream` of an encrypting Flow / buffered bytes), which target
 * the staging dir directly instead of writing to cache scratch and copying.
 */
fun createStagingPathIn(
    stagingDir: String,
    prefix: String,
    suffix: String,
    fileSystem: FileSystem = systemFileSystem,
): String {
    val dir = stagingDir.toPath()
    fileSystem.createDirectories(dir)
    while (true) {
        val candidate = dir / "$prefix${randomToken()}$suffix"
        if (!fileSystem.exists(candidate)) return candidate.toString()
    }
}

/**
 * Delete everything inside [stagingDir] (logout). Each child goes through
 * [safeDeleteRecursively]'s guards; the dir itself stays. Best-effort — a
 * missing dir or a per-child failure is swallowed (logged in safeDeleteRecursively)
 * and never blocks logout. Mirrors chat-kmp's wipeOutboxStaging (pin e67130cd).
 */
fun wipeOutboxStaging(
    stagingDir: String,
    fileSystem: FileSystem = systemFileSystem,
) {
    val dir = stagingDir.toPath()
    val children = runCatching { fileSystem.list(dir) }.getOrElse { return }
    for (child in children) {
        safeDeleteRecursively(stagingDir, child.name, fileSystem)
    }
}

private fun randomToken(): String = Random.nextLong().toULong().toString(16)
