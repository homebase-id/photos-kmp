package id.homebase.api.file

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Logout wipe of the durable outbox staging dir (#842). Pairs with the outbox-table wipe in
 * DriveSyncManager.clearStorage — rows and their staged payloads must leave together. Runs over a
 * [FakeFileSystem] on all targets. Mirrors chat-kmp's OutboxStagingTest wipe cases (pin e67130cd).
 */
class OutboxStagingWipeTest {

    private val stagingDir = "/data/app/outbox-staging"

    @Test
    fun wipeOutboxStaging_removesAllChildrenButKeepsDir() {
        val fs = FakeFileSystem()
        fs.createDirectories(stagingDir.toPath())
        fs.write("$stagingDir/enc1.encrypted".toPath()) { writeUtf8("a") }
        fs.write("$stagingDir/enc2.encrypted".toPath()) { writeUtf8("b") }
        fs.createDirectories("$stagingDir/hls_99".toPath())
        fs.write("$stagingDir/hls_99/index.ts".toPath()) { writeUtf8("c") }

        wipeOutboxStaging(stagingDir, fs)

        assertTrue(fs.exists(stagingDir.toPath()), "the staging dir itself is kept")
        assertEquals(emptyList(), fs.list(stagingDir.toPath()), "every staged payload is removed")
    }

    @Test
    fun wipeOutboxStaging_onMissingDirIsANoOp() {
        // Nothing to wipe and no crash — best-effort by contract.
        wipeOutboxStaging("/data/app/never-created", FakeFileSystem())
    }
}
