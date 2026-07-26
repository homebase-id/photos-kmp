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

/** Flat UI state for the Archive grid — local-paged (beforeUserDate), month-sectioned, Timeline-parity selection. */
data class ArchiveUiState(
    val isLoading: Boolean = true,
    val isPaginating: Boolean = false,
    val sections: List<TimelineSection> = emptyList(),
    val pagedItems: List<PhotoItem> = emptyList(),
    val endReached: Boolean = false,
    val error: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val isMutating: Boolean = false, // an unarchive write is in flight
) {
    val inSelectionMode: Boolean get() = selectedIds.isNotEmpty()

    fun isSelected(photo: PhotoItem): Boolean = photo.fileId.toString() in selectedIds

    val selectedPhotos: List<PhotoItem> get() = pagedItems.filter { isSelected(it) }
}

/** One-time events the native layer consumes (kept off the StateFlow). */
sealed interface ArchiveEvent {
    data class Error(val message: String) : ArchiveEvent
    data class Unarchived(val succeeded: Int, val failed: Int) : ArchiveEvent
}

class ArchiveViewModel(
    private val repository: PhotosRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ArchiveUiState())
    val state: StateFlow<ArchiveUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ArchiveEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ArchiveEvent> = _events.asSharedFlow()

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

    /** Fire-and-forget unarchive of the selection (Android). */
    fun unarchiveSelected() {
        viewModelScope.launch { unarchiveSelectedAndWait() }
    }

    /**
     * Restore the selected photos out of Archive, suspending until done — iOS awaits this. Drops
     * the succeeded ones from state and clears the selection unconditionally — no background
     * refresh, so a deep `loadMore` session keeps its loaded depth instead of snapping to page 1.
     */
    suspend fun unarchiveSelectedAndWait() {
        val current = _state.value
        if (current.isMutating || current.selectedIds.isEmpty()) return
        val targets = current.selectedPhotos
        _state.update { it.copy(isMutating = true) }
        val result = try {
            repository.setArchived(targets.map { it.fileId }, archived = false)
        } catch (e: CancellationException) {
            _state.update { it.copy(isMutating = false) }
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "unarchive failed: ${e.message}" }
            _state.update { it.copy(isMutating = false) }
            emitError(e.message ?: "Couldn't unarchive")
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
        _events.tryEmit(ArchiveEvent.Unarchived(succeededIds.size, result.failed.size))
        if (result.failed.isNotEmpty()) emitError("Couldn't unarchive ${result.failed.size} item(s)")
    }

    /** One page, or null when the read fails — callers keep existing content on null. */
    private suspend fun safeLoad(beforeUserDate: Long?): List<PhotoItem>? = try {
        repository.loadArchivedPage(beforeUserDate = beforeUserDate, limit = PAGE_SIZE)
    } catch (e: CancellationException) {
        _state.update { it.copy(isLoading = false, isPaginating = false) }
        throw e
    } catch (e: Exception) {
        Logger.w(tag = TAG) { "loadArchivedPage failed: ${e.message}" }
        emitError(e.message ?: "Couldn't load archive")
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
        _events.tryEmit(ArchiveEvent.Error(message))
    }

    companion object {
        private const val TAG = "ArchiveViewModel"
        const val PAGE_SIZE = 60
    }
}
