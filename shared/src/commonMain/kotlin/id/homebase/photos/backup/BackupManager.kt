package id.homebase.photos.backup

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.sync.database.EnqueueResult
import id.homebase.api.sync.database.OutboxSync
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Clock

/**
 * Durable upload seam: hands a built [UploadFileRequest] to the copied outbox. Kept as an
 * interface so [BackupManager] is testable with a fake, and so nothing here reimplements the
 * upload envelope — the real impl just calls [OutboxSync.tryEnqueue].
 */
interface PhotoUploadEnqueuer {
    /** Enqueue for durable delivery. Returns true once the row is durably queued (or already was). */
    suspend fun enqueue(request: UploadFileRequest): Boolean
}

/** Production seam over the copied outbox. Enqueue is the durable success gate; the drain is async. */
class OutboxPhotoUploadEnqueuer(
    private val outboxSync: OutboxSync,
) : PhotoUploadEnqueuer {
    override suspend fun enqueue(request: UploadFileRequest): Boolean =
        when (val result = outboxSync.tryEnqueue(request)) {
            is EnqueueResult.Enqueued -> true
            is EnqueueResult.AlreadyQueued -> true          // already durably queued — treat as backed up
            is EnqueueResult.WouldStrandCreate -> false
            is EnqueueResult.Failed -> throw result.cause   // surfaced as lastError, never crashes the pass
        }
}

/**
 * Drives one idempotent backup pass: crawl newest-first → dedup against the ledger → build the
 * descriptor → enqueue → record. Exposes flat [BackupState]. Cooperative stop: [setEnabled]`(false)`
 * lets the in-flight item finish, then halts before the next. Errors on a single item are recorded
 * in `lastError` and skipped — a bad photo never aborts the whole pass.
 */
class BackupManager(
    private val crawler: PhotoLibraryCrawler,
    private val ledger: BackupLedger,
    private val builder: PhotoFileBuilder,
    private val uploader: PhotoUploadEnqueuer,
    private val selectionStore: BackupFolderSelectionStore,
    private val enabledStore: BackupEnabledStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _state = MutableStateFlow(BackupState())
    val state: StateFlow<BackupState> = _state.asStateFlow()

    // Cooperative stop flag, flipped by setEnabled(false). Separate from `enabled` (pure UI intent)
    // so a direct backupNow() can run without resurrecting the toggle.
    private val stopRequested = atomic(false)

    // Guarantees a single pass at a time — a second backupNow() while one runs is a no-op.
    private val runLock = Mutex()

    /** Toggle the backup service. `true` kicks a pass; `false` stops the current pass after this item. */
    fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(enabled = enabled) }
        // Persist the intent so it survives process death (fire-and-forget on the pass scope).
        scope.launch { enabledStore.setEnabled(enabled) }
        if (enabled) {
            stopRequested.value = false
            scope.launch { backupNow() }
        } else {
            stopRequested.value = true
        }
    }

    /**
     * Launch-time reconcile: seed [state] from persistence (the enabled flag + selection count)
     * WITHOUT starting a pass, so the UI reflects reality after process death. Actual runs are driven
     * only by the periodic job or an explicit toggle — never here.
     */
    suspend fun restore() {
        val enabled = enabledStore.enabled()
        val count = selectionStore.selected().size
        _state.update { it.copy(enabled = enabled, selectedFolderCount = count) }
    }

    /** Device folders available to back up. Delegates to the crawler (impl-defined order). */
    suspend fun folders(): List<LibraryFolder> = crawler.folders()

    /** The folderIds currently selected for backup (empty = nothing will upload). */
    suspend fun selectedFolderIds(): Set<String> = selectionStore.selected()

    /**
     * Flip [folderId] in the persisted selection and mirror the new count into [state]. Returns the
     * updated set so the caller (ViewModel) can refresh per-folder checkbox state without re-reading.
     */
    suspend fun toggleFolder(folderId: String): Set<String> {
        val updated = selectionStore.toggle(folderId)
        _state.update { it.copy(selectedFolderCount = updated.size) }
        return updated
    }

    /** Idempotent full pass. Safe to call repeatedly; concurrent calls collapse to one run. */
    suspend fun backupNow() {
        if (!runLock.tryLock()) return
        try {
            stopRequested.value = false
            // D6: back up ONLY selected folders. Nothing selected → upload nothing and complete
            // immediately (done/total 0), never crawling assets — the safety default.
            val selected = selectionStore.selected()
            if (selected.isEmpty()) {
                _state.update {
                    it.copy(done = 0, total = 0, currentName = null, lastError = null, selectedFolderCount = 0, lastCompletedAt = now())
                }
                return
            }
            val assets = crawler.assets(selected)
            _state.update { it.copy(running = true, done = 0, total = assets.size, currentName = null, lastError = null, selectedFolderCount = selected.size) }

            for (asset in assets) {
                if (stopRequested.value) break
                _state.update { it.copy(currentName = asset.fileName) }
                try {
                    if (ledger.backedUpFileId(asset.deviceAssetId) != null) {
                        bumpDone()                          // already backed up — counts toward progress
                        continue
                    }
                    val bytes = crawler.readBytes(asset)
                    if (bytes == null) {
                        _state.update { it.copy(lastError = "Couldn't read ${asset.fileName}") }
                        continue
                    }
                    val request = builder.build(asset, bytes)
                    if (uploader.enqueue(request)) {
                        request.metadata.appData.uniqueId?.let { ledger.record(asset.deviceAssetId, it) }
                        bumpDone()
                    } else {
                        _state.update { it.copy(lastError = "Couldn't queue ${asset.fileName}") }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Logger.w(throwable = e, tag = TAG) { "backup failed for ${asset.fileName}" }
                    _state.update { it.copy(lastError = e.message ?: "Backup error") }
                }
            }
            _state.update { it.copy(lastCompletedAt = now()) }
        } finally {
            _state.update { it.copy(running = false, currentName = null) }
            runLock.unlock()
        }
    }

    private fun bumpDone() = _state.update { it.copy(done = it.done + 1) }

    private companion object {
        const val TAG = "BackupManager"
    }
}
