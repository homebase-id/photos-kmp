package id.homebase.photos.data

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.HomebaseFile
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

/** What a fetch → patch → send round did to one file. */
internal sealed interface HeaderPatchOutcome {
    data object Applied : HeaderPatchOutcome

    /** File is gone — the caller's goal state already holds, so callers treat this as success. */
    data object NotFound : HeaderPatchOutcome

    data class Failed(val message: String) : HeaderPatchOutcome
}

internal const val MAX_VERSION_CONFLICT_ATTEMPTS = 3

/**
 * Fetch a FRESH header, patch it, send it — retrying the whole round on `VersionTagMismatch`
 * (another writer won the race, so our carried versionTag is stale and the rebuild must start
 * from their header). Bounded: a wedged conflict loop would otherwise spin forever.
 */
internal suspend fun patchHeaderWithRetry(
    fileId: Uuid,
    maxAttempts: Int = MAX_VERSION_CONFLICT_ATTEMPTS,
    fetch: suspend (Uuid) -> HomebaseFile?,
    send: suspend (HomebaseFile) -> Unit,
): HeaderPatchOutcome {
    repeat(maxAttempts) {
        val existing = try {
            fetch(fileId) ?: return HeaderPatchOutcome.NotFound
        } catch (e: NotFoundException) {
            return HeaderPatchOutcome.NotFound
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return HeaderPatchOutcome.Failed(e.message ?: "Couldn't read $fileId")
        }
        try {
            send(existing)
            return HeaderPatchOutcome.Applied
        } catch (e: NotFoundException) {
            return HeaderPatchOutcome.NotFound
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClientException) {
            if (e.errorCode != OdinClientErrorCode.VersionTagMismatch) {
                return HeaderPatchOutcome.Failed(e.message ?: "Couldn't update $fileId")
            }
            Logger.i(tag = "AlbumsRepository") { "versionTag conflict on $fileId — re-fetching" }
        } catch (e: Exception) {
            return HeaderPatchOutcome.Failed(e.message ?: "Couldn't update $fileId")
        }
    }
    return HeaderPatchOutcome.Failed("Version conflict after $maxAttempts attempts")
}
