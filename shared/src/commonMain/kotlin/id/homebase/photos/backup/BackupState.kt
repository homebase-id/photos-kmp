package id.homebase.photos.backup

/**
 * Backup pipeline state, mirrored into the UI by [BackupViewModel]. Flat by convention.
 * `done`/`total` count every asset the current pass has resolved — both fresh uploads and
 * already-backed-up skips — so a re-run of an already-complete library reads `done == total`.
 */
data class BackupState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val currentName: String? = null,
    val lastError: String? = null,
    val lastCompletedAt: Long? = null,
    // Folders the user chose to back up (D6). 0 = nothing will upload — enabling backup is a no-op
    // until folders are selected. Mirrored from BackupFolderSelectionStore on every pass / toggle.
    val selectedFolderCount: Int = 0,
)
