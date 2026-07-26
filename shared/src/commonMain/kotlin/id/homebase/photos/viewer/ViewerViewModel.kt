package id.homebase.photos.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.photos.data.PhotosRepository
import id.homebase.photos.domain.PhotoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Flat UI state for the full-screen viewer pager. Native screens (SwiftUI + Compose) render this. */
data class ViewerUiState(
    val items: List<PhotoItem> = emptyList(),
    val index: Int = 0,
    val isDeleting: Boolean = false,
    val deletedAny: Boolean = false,   // any delete happened this viewer session → hosts refresh on close
) {
    val current: PhotoItem? get() = items.getOrNull(index)

    /** Derived from the current item — no separate field to keep out of sync on swipe. */
    val isFavorite: Boolean get() = current?.isFavorite ?: false
}

/** One-time events the native layer consumes (kept off the StateFlow). */
sealed interface ViewerEvent {
    data class Error(val message: String) : ViewerEvent
    data object Closed : ViewerEvent   // last item deleted → platform dismisses the viewer
}

class ViewerViewModel(
    items: List<PhotoItem>,
    initialIndex: Int,
    private val repository: PhotosRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ViewerUiState(items = items, index = clampIndex(initialIndex, items)),
    )
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ViewerEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ViewerEvent> = _events.asSharedFlow()

    /** Clamps into `items.indices`; no-op when empty. */
    fun setIndex(i: Int) {
        _state.update { if (it.items.isEmpty()) it else it.copy(index = clampIndex(i, it.items)) }
    }

    /** Fire-and-forget delete of the current item (Android). */
    fun deleteCurrent() {
        viewModelScope.launch { deleteCurrentAndWait() }
    }

    /** Delete the current item, suspending until done — iOS awaits this. */
    suspend fun deleteCurrentAndWait() {
        val current = _state.value
        if (current.isDeleting) return
        val doomed = current.current ?: return
        _state.update { it.copy(isDeleting = true) }
        val deleted = try {
            repository.deletePhotos(listOf(doomed.fileId))
        } catch (e: CancellationException) {
            _state.update { it.copy(isDeleting = false) } // don't wedge future deletes if the awaiting Task dies
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "delete failed: ${e.message}" }
            false
        }
        if (deleted) {
            _state.update {
                val remaining = it.items.filterNot { p -> p.fileId == doomed.fileId }
                it.copy(
                    isDeleting = false,
                    items = remaining,
                    index = clampIndex(it.index, remaining),
                    deletedAny = true,
                )
            }
            if (_state.value.items.isEmpty()) _events.tryEmit(ViewerEvent.Closed)
        } else {
            _state.update { it.copy(isDeleting = false) }
            _events.tryEmit(ViewerEvent.Error("Couldn't delete"))
        }
    }

    /** Fire-and-forget favorite toggle of the current item (Android). */
    fun toggleFavoriteCurrent() {
        viewModelScope.launch { toggleFavoriteCurrentAndWait() }
    }

    /**
     * Flip favorite on the current item, suspending until done — iOS awaits this. Optimistic:
     * the `items` entry flips immediately (so swiping away and back keeps it), then reverts with
     * an [ViewerEvent.Error] if the repository write fails.
     */
    suspend fun toggleFavoriteCurrentAndWait() {
        val target = _state.value.current ?: return
        val newValue = !target.isFavorite
        _state.update { s -> s.copy(items = s.items.map { if (it.fileId == target.fileId) it.copy(isFavorite = newValue) else it }) }
        val ok = try {
            repository.setFavorite(target.fileId, newValue)
        } catch (e: CancellationException) {
            _state.update { s -> s.copy(items = s.items.map { if (it.fileId == target.fileId) it.copy(isFavorite = target.isFavorite) else it }) }
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "favorite failed: ${e.message}" }
            false
        }
        if (!ok) {
            _state.update { s -> s.copy(items = s.items.map { if (it.fileId == target.fileId) it.copy(isFavorite = target.isFavorite) else it }) }
            _events.tryEmit(ViewerEvent.Error("Couldn't update favorite"))
        }
    }

    companion object {
        private const val TAG = "ViewerViewModel"

        private fun clampIndex(i: Int, items: List<PhotoItem>): Int =
            i.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }
}
