package id.homebase.photos.data

import id.homebase.api.client.ClientException
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.ProblemDetails
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Membership writes are N unbatched PATCHes against live headers: a racing writer (another
 * device, or backup) must cost a re-fetch, not a silently dropped write, and a photo deleted
 * out from under us must not fail the whole batch.
 */
class HeaderPatchRetryTest {

    private val fileId = Uuid.random()

    private fun header(versionTag: Uuid = Uuid.random()) = HomebaseFile(
        fileId = fileId,
        driveId = Uuid.random(),
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader.empty(),
        fileMetadata = FileMetadata(versionTag = versionTag, appData = AppFileMetaData()),
        serverMetadata = ServerMetadata(),
    )

    private fun versionConflict() = ClientException(
        status = 400,
        errorCode = OdinClientErrorCode.VersionTagMismatch,
        message = "versionTag mismatch",
        correlationId = null,
        problem = ProblemDetails(),
    )

    @Test
    fun appliesOnFirstTry() = runTest {
        var sends = 0

        val outcome = patchHeaderWithRetry(
            fileId = fileId,
            fetch = { header() },
            send = { sends++ },
        )

        assertIs<HeaderPatchOutcome.Applied>(outcome)
        assertEquals(1, sends)
    }

    @Test
    fun versionConflict_refetchesAndRetries() = runTest {
        var fetches = 0
        var sends = 0

        val outcome = patchHeaderWithRetry(
            fileId = fileId,
            fetch = { fetches++; header() },
            send = { if (++sends == 1) throw versionConflict() },
        )

        assertIs<HeaderPatchOutcome.Applied>(outcome)
        assertEquals(2, sends)
        assertEquals(2, fetches, "the retry must start from a FRESH header, not the stale one")
    }

    @Test
    fun versionConflict_isBounded() = runTest {
        var sends = 0

        val outcome = patchHeaderWithRetry(
            fileId = fileId,
            maxAttempts = 3,
            fetch = { header() },
            send = { sends++; throw versionConflict() },
        )

        assertIs<HeaderPatchOutcome.Failed>(outcome)
        assertEquals(3, sends)
    }

    @Test
    fun missingHeader_isToleratedNotFailed() = runTest {
        val outcome = patchHeaderWithRetry(
            fileId = fileId,
            fetch = { null },
            send = { error("must not send") },
        )

        assertIs<HeaderPatchOutcome.NotFound>(outcome)
    }

    @Test
    fun notFoundOnSend_isToleratedNotFailed() = runTest {
        // The file was deleted between our fetch and our PATCH.
        val outcome = patchHeaderWithRetry(
            fileId = fileId,
            fetch = { header() },
            send = { throw NotFoundException() },
        )

        assertIs<HeaderPatchOutcome.NotFound>(outcome)
    }

    @Test
    fun otherErrors_failWithoutRetrying() = runTest {
        var sends = 0

        val outcome = patchHeaderWithRetry(
            fileId = fileId,
            fetch = { header() },
            send = { sends++; throw IllegalStateException("network down") },
        )

        assertIs<HeaderPatchOutcome.Failed>(outcome)
        assertTrue(outcome.message.contains("network down"))
        assertEquals(1, sends, "only a version conflict is worth retrying")
    }

    @Test
    fun fetchFailure_isReportedNotSwallowed() = runTest {
        val outcome = patchHeaderWithRetry(
            fileId = fileId,
            fetch = { throw IllegalStateException("read failed") },
            send = { error("must not send") },
        )

        assertIs<HeaderPatchOutcome.Failed>(outcome)
    }
}
