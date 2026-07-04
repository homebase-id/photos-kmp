package id.homebase.photos.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One device-folder row in the picker. Flat by design (kept out of the crawler's [LibraryFolder]). */
data class FolderUi(
    val folderId: String,
    val name: String,
    val photoCount: Int,
    val selected: Boolean,
)

/** Flat UI state for the backup card. Native screens (Compose + SwiftUI) render this. */
data class BackupUiState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val currentName: String? = null,
    val lastError: String? = null,
    val lastCompletedAt: Long? = null,
    val selectedFolderCount: Int = 0,
    // Loaded on demand (loadFolders); empty until the folder picker is opened.
    val folders: List<FolderUi> = emptyList(),
)

private fun BackupState.toUiState(folders: List<FolderUi>) = BackupUiState(
    enabled = enabled,
    running = running,
    done = done,
    total = total,
    currentName = currentName,
    lastError = lastError,
    lastCompletedAt = lastCompletedAt,
    selectedFolderCount = selectedFolderCount,
    folders = folders,
)

/**
 * Thin ViewModel over [BackupManager]: mirrors its state and forwards the card actions. The folder
 * list is loaded on demand (not part of [BackupState]) and combined into the exposed UI state.
 */
class BackupViewModel(
    private val manager: BackupManager,
) : ViewModel() {

    // Folder rows for the picker — loaded lazily via loadFolders(), overlaid onto the manager state.
    private val _folders = MutableStateFlow<List<FolderUi>>(emptyList())

    val state: StateFlow<BackupUiState> =
        combine(manager.state, _folders) { s, folders -> s.toUiState(folders) }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                manager.state.value.toUiState(_folders.value),
            )

    /** Card toggle: on kicks a backup pass, off stops it after the current item. */
    fun onToggle(enabled: Boolean) = manager.setEnabled(enabled)

    /** Explicit "back up now" (idempotent). */
    fun onBackupNow() {
        viewModelScope.launch { manager.backupNow() }
    }

    /** Load the device folders and mark each with its persisted selection state. */
    fun loadFolders() {
        viewModelScope.launch {
            val selected = manager.selectedFolderIds()
            _folders.value = manager.folders().map {
                FolderUi(it.folderId, it.name, it.photoCount, it.folderId in selected)
            }
        }
    }

    /** Toggle a folder's selection: persist via the manager and reflect it in the folder rows. */
    fun onFolderToggled(folderId: String) {
        viewModelScope.launch {
            val updated = manager.toggleFolder(folderId)
            _folders.update { rows ->
                rows.map { if (it.folderId == folderId) it.copy(selected = folderId in updated) else it }
            }
        }
    }
}
