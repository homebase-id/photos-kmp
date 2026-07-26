package id.homebase.photos.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.photos.data.PhotosRepository
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.timeline.TimelineSection
import id.homebase.photos.timeline.appendToMonthSections
import id.homebase.photos.timeline.groupIntoMonthSections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Flat UI state for the Trash grid — local-paged (beforeUserDate), month-sectioned, Timeline-parity selection. */
data class TrashUiState(
    val isLoading: Boolean = true,
    val isPaginating: Boolean = false,
    val sections: List<TimelineSection> = emptyList(),
    val pagedItems: List<PhotoItem> = emptyList(),
    val endReached: Boolean = false,
    val error: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val isMutating: Boolean = false, // a restore or permanent-delete write is in flight
) {
    val inSelectionMode: Boolean get() = selectedIds.isNotEmpty()

    fun isSelected(photo: PhotoItem): Boolean = photo.fileId.toString() in selectedIds

    val selectedPhotos: List<PhotoItem> get() = pagedItems.filter { isSelected(it) }
}

/** One-time events the native layer consumes (kept off the StateFlow). */
sealed interface TrashEvent {
    data class Error(val message: String) : TrashEvent
    data class Restored(val succeeded: Int, val failed: Int) : TrashEvent
    data class PermanentlyDeleted(val count: Int) : TrashEvent
}

class TrashViewModel(
    private val repository: PhotosRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TrashEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<TrashEvent> = _events.asSharedFlow()

    init {
        refresh()
    }

    /** Fire-and-forget refresh (Android). */
    fun refresh() {
        viewModelScope.launch { refreshAndWait() }
    }

    /** Reload the newest local-index page, suspending until done — iOS .refreshable awaits this. */
    suspend fun refreshAndWait() {
        _state.update { it.copy(isLoading = true, error = null) }
        val page = safeLoad(beforeUserDate = null)
        if (page != null) applyReplace(page) else _state.update { it.copy(isLoading = false) }
    }

    /** Add/remove [photo] from the selection; removing the last id exits selection mode. */
    fun toggleSelection(photo: PhotoItem) {
        _state.update {
            val key = photo.fileId.toString()
            val ids = if (key in it.selectedIds) it.selectedIds - key else it.selectedIds + key
            it.copy(selectedIds = ids)
        }
    }

    /** Drop every selected id — exits selection mode. */
    fun clearSelection() {
        _state.update { it.copy(selectedIds = emptySet()) }
    }

    /** Paginate older by userDate, appending to the flat list without regrouping prior months. */
    fun loadMore() {
        val current = _state.value
        if (current.endReached || current.isLoading || current.isPaginating) return
        val cursor = current.pagedItems.lastOrNull()?.userDate ?: return
        viewModelScope.launch {
            _state.update { it.copy(isPaginating = true) }
            val page = safeLoad(beforeUserDate = cursor)
            if (page != null) applyAppend(page) else _state.update { it.copy(isPaginating = false) }
        }
    }

    /** Fire-and-forget restore of the selection (Android). */
    fun restoreSelected() {
        viewModelScope.launch { restoreSelectedAndWait() }
    }

    /**
     * Restore the selected photos out of Trash, suspending until done — iOS awaits this. Drops
     * the succeeded ones from state and clears the selection unconditionally — no background
     * refresh, so a deep `loadMore` session keeps its loaded depth instead of snapping to page 1.
     */
    suspend fun restoreSelectedAndWait() {
        val current = _state.value
        if (current.isMutating || current.selectedIds.isEmpty()) return
        val targets = current.selectedPhotos
        _state.update { it.copy(isMutating = true) }
        val result = try {
            repository.restore(targets.map { it.fileId })
        } catch (e: CancellationException) {
            _state.update { it.copy(isMutating = false) }
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "restore failed: ${e.message}" }
            _state.update { it.copy(isMutating = false) }
            emitError(e.message ?: "Couldn't restore")
            return
        }
        val succeededIds = result.succeeded.toSet()
        _state.update {
            val remaining = it.pagedItems.filterNot { p -> p.fileId in succeededIds }
            it.copy(
                isMutating = false,
                selectedIds = emptySet(),
                pagedItems = remaining,
                sections = groupIntoMonthSections(remaining),
            )
        }
        _events.tryEmit(TrashEvent.Restored(succeededIds.size, result.failed.size))
        if (result.failed.isNotEmpty()) emitError("Couldn't restore ${result.failed.size} item(s)")
    }

    /** Fire-and-forget permanent delete of the selection (Android). */
    fun permanentDeleteSelected() {
        viewModelScope.launch { permanentDeleteSelectedAndWait() }
    }

    /**
     * Irreversibly delete the selected photos, suspending until done — iOS awaits this.
     * All-or-nothing like [id.homebase.photos.timeline.TimelineViewModel.deleteSelectedAndWait]:
     * on failure nothing is mutated and the selection stays put.
     */
    suspend fun permanentDeleteSelectedAndWait() {
        val current = _state.value
        if (current.isMutating || current.selectedIds.isEmpty()) return
        val doomed = current.selectedPhotos
        _state.update { it.copy(isMutating = true) }
        val deleted = try {
            repository.permanentDelete(doomed.map { it.fileId })
        } catch (e: CancellationException) {
            _state.update { it.copy(isMutating = false) }
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "permanentDelete failed: ${e.message}" }
            false
        }
        if (deleted) {
            _state.update {
                val remaining = it.pagedItems - doomed.toSet()
                it.copy(
                    isMutating = false,
                    selectedIds = emptySet(),
                    pagedItems = remaining,
                    sections = groupIntoMonthSections(remaining),
                )
            }
            _events.tryEmit(TrashEvent.PermanentlyDeleted(doomed.size))
        } else {
            _state.update { it.copy(isMutating = false) }
            emitError("Couldn't delete")
        }
    }

    /** One page, or null when the read fails — callers keep existing content on null. */
    private suspend fun safeLoad(beforeUserDate: Long?): List<PhotoItem>? = try {
        repository.loadTrashPage(beforeUserDate = beforeUserDate, limit = PAGE_SIZE)
    } catch (e: CancellationException) {
        _state.update { it.copy(isLoading = false, isPaginating = false) }
        throw e
    } catch (e: Exception) {
        Logger.w(tag = TAG) { "loadTrashPage failed: ${e.message}" }
        emitError(e.message ?: "Couldn't load trash")
        null
    }

    private fun applyReplace(page: List<PhotoItem>) {
        _state.update {
            it.copy(
                isLoading = false,
                pagedItems = page,
                sections = groupIntoMonthSections(page),
                endReached = page.size < PAGE_SIZE,
                error = null,
            )
        }
    }

    private fun applyAppend(page: List<PhotoItem>) {
        _state.update {
            it.copy(
                isPaginating = false,
                pagedItems = it.pagedItems + page,
                sections = appendToMonthSections(it.sections, page),
                endReached = page.size < PAGE_SIZE,
            )
        }
    }

    private fun emitError(message: String) {
        _events.tryEmit(TrashEvent.Error(message))
    }

    companion object {
        private const val TAG = "TrashViewModel"
        const val PAGE_SIZE = 60
    }
}
