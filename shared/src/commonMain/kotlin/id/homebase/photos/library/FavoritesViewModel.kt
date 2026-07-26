package id.homebase.photos.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.photos.data.PhotosRepository
import id.homebase.photos.data.setFavoriteBatch
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

/** Flat UI state for the Favorites grid — server-paged (cursor), month-sectioned, Timeline-parity selection. */
data class FavoritesUiState(
    val isLoading: Boolean = true,
    val isPaginating: Boolean = false,
    val sections: List<TimelineSection> = emptyList(),
    val pagedItems: List<PhotoItem> = emptyList(),
    val nextCursor: String? = null,
    val endReached: Boolean = false,
    val error: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val isMutating: Boolean = false, // an unfavorite write is in flight
) {
    val inSelectionMode: Boolean get() = selectedIds.isNotEmpty()

    fun isSelected(photo: PhotoItem): Boolean = photo.fileId.toString() in selectedIds

    val selectedPhotos: List<PhotoItem> get() = pagedItems.filter { isSelected(it) }
}

/** One-time events the native layer consumes (kept off the StateFlow). */
sealed interface FavoritesEvent {
    data class Error(val message: String) : FavoritesEvent
    data class Unfavorited(val succeeded: Int, val failed: Int) : FavoritesEvent
}

class FavoritesViewModel(
    private val repository: PhotosRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FavoritesUiState())
    val state: StateFlow<FavoritesUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<FavoritesEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<FavoritesEvent> = _events.asSharedFlow()

    init {
        refresh()
    }

    /** Fire-and-forget refresh (Android). */
    fun refresh() {
        viewModelScope.launch { refreshAndWait() }
    }

    /** Reload the newest server page, suspending until done — iOS .refreshable awaits this. */
    suspend fun refreshAndWait() {
        _state.update { it.copy(isLoading = true, error = null) }
        val page = try {
            repository.loadFavoritesPage(cursor = null, limit = PAGE_SIZE)
        } catch (e: CancellationException) {
            _state.update { it.copy(isLoading = false) }
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "loadFavoritesPage failed: ${e.message}" }
            _state.update { it.copy(isLoading = false) }
            emitError(e.message ?: "Couldn't load favorites")
            return
        }
        _state.update {
            it.copy(
                isLoading = false,
                pagedItems = page.items,
                sections = groupIntoMonthSections(page.items),
                nextCursor = page.nextCursor,
                endReached = page.nextCursor == null,
            )
        }
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

    /** Fetch the next server page by cursor, appending without regrouping prior months. */
    fun loadMore() {
        val current = _state.value
        if (current.endReached || current.isLoading || current.isPaginating) return
        val cursor = current.nextCursor ?: return
        viewModelScope.launch {
            _state.update { it.copy(isPaginating = true) }
            val page = try {
                repository.loadFavoritesPage(cursor = cursor, limit = PAGE_SIZE)
            } catch (e: CancellationException) {
                _state.update { it.copy(isPaginating = false) }
                throw e
            } catch (e: Exception) {
                Logger.w(tag = TAG) { "loadFavoritesPage (more) failed: ${e.message}" }
                _state.update { it.copy(isPaginating = false) }
                emitError(e.message ?: "Couldn't load more favorites")
                return@launch
            }
            _state.update {
                it.copy(
                    isPaginating = false,
                    pagedItems = it.pagedItems + page.items,
                    sections = appendToMonthSections(it.sections, page.items),
                    nextCursor = page.nextCursor,
                    endReached = page.nextCursor == null,
                )
            }
        }
    }

    /** Fire-and-forget unfavorite of the selection (Android). */
    fun unfavoriteSelected() {
        viewModelScope.launch { unfavoriteSelectedAndWait() }
    }

    /**
     * Unfavorite the selected photos, suspending until done — iOS awaits this. Drops the
     * succeeded ones from state and clears the selection unconditionally — no background
     * refresh, so a deep `loadMore` session keeps its loaded depth instead of snapping to
     * page 1. Emits an [FavoritesEvent.Error] alongside the count on any partial failure.
     */
    suspend fun unfavoriteSelectedAndWait() {
        val current = _state.value
        if (current.isMutating || current.selectedIds.isEmpty()) return
        val targets = current.selectedPhotos
        _state.update { it.copy(isMutating = true) }
        val succeededIds = try {
            repository.setFavoriteBatch(targets.map { it.fileId }, favorite = false)
        } catch (e: CancellationException) {
            _state.update { it.copy(isMutating = false) }
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "unfavorite failed: ${e.message}" }
            _state.update { it.copy(isMutating = false) }
            emitError(e.message ?: "Couldn't unfavorite")
            return
        }
        _state.update {
            val remaining = it.pagedItems.filterNot { p -> p.fileId in succeededIds }
            it.copy(
                isMutating = false,
                selectedIds = emptySet(),
                pagedItems = remaining,
                sections = groupIntoMonthSections(remaining),
            )
        }
        val failed = targets.size - succeededIds.size
        _events.tryEmit(FavoritesEvent.Unfavorited(succeededIds.size, failed))
        if (failed > 0) emitError("Couldn't unfavorite $failed item(s)")
    }

    private fun emitError(message: String) {
        _events.tryEmit(FavoritesEvent.Error(message))
    }

    companion object {
        private const val TAG = "FavoritesViewModel"
        const val PAGE_SIZE = 60
    }
}
