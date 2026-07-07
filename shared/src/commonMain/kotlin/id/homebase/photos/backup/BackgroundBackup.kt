package id.homebase.photos.backup

import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.photos.data.PhotosRepository

/**
 * One background backup pass, platform-agnostic — the whole reusable sequence lives here so both
 * Android's [work.BackupWorker] and a future iOS BGTask handler call the SAME [run]; only the
 * trigger (WorkManager vs BGTaskScheduler) is platform code.
 *
 * Restore the session → gate on the enabled flag → enqueue new photos from the selected folders →
 * bring the outbox online and kick the drain → SUSPEND until the uploads land. The session restore
 * is essential: when WorkManager cold-starts a fresh process (the app was killed), nothing else
 * loads the login — that only happens on UI startup — so without it [DriveSyncManager.start] finds
 * "no active credentials", the outbox stays offline, and nothing ships until the app is next opened.
 */
class BackgroundBackup(
    private val youAuth: YouAuthFlowManager,
    private val enabledStore: BackupEnabledStore,
    private val backupManager: BackupManager,
    private val repository: PhotosRepository,
    private val outboxSync: OutboxSync,
) {
    /** True if the pass completed (or backup is off) and the outbox drained within [uploadTimeoutMs]. */
    suspend fun run(uploadTimeoutMs: Long = DEFAULT_UPLOAD_TIMEOUT_MS): Boolean {
        if (!enabledStore.enabled()) return true          // toggle off → nothing to ship
        // Load persisted credentials + bring the outbox online — a cold worker process has neither.
        youAuth.restoreSession()
        backupManager.backupNow()                         // crawl selected folders → dedup → enqueue
        // ponytail: reuse the foreground sync path — start() already does setOnline(true)+send().
        // Swap for a backup-only online+send seam if the metadata pull here proves too heavy.
        repository.sync()
        return outboxSync.awaitDrained(uploadTimeoutMs)   // keep the process alive until uploads land
    }

    private companion object {
        const val DEFAULT_UPLOAD_TIMEOUT_MS = 9 * 60_000L  // under WorkManager's ~10-min ceiling
    }
}
