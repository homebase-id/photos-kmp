package id.homebase.photos.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.photos.data.AlbumsRepository
import id.homebase.photos.domain.AlbumItem
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.timeline.TimelineSection
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

/** Flat UI state for one album's photo grid. Native screens (SwiftUI + Compose) render this. */
data class AlbumDetailUiState(
    val isLoading: Boolean = true,
    val title: String = "",
    val sections: List<TimelineSection> = emptyList(), // groupIntoMonthSections(photos)
    val photos: List<PhotoItem> = emptyList(),         // flat list for the viewer pager
    val error: String? = null,
    val selectedIds: Set<String> = emptySet(),         // PhotoItem.fileId.toString() keys
    val isRemoving: Boolean = false,
) {
    val inSelectionMode: Boolean get() = selectedIds.isNotEmpty()

    fun isSelected(photo: PhotoItem): Boolean = photo.fileId.toString() in selectedIds

    /** The selected photos in grid order — what "remove from album" acts on. */
    val selectedPhotos: List<PhotoItem> get() = photos.filter { isSelected(it) }
}

/** One-time events the native layer consumes (kept off the StateFlow). */
sealed interface AlbumDetailEvent {
    data class Error(val message: String) : AlbumDetailEvent
    data class Removed(val count: Int) : AlbumDetailEvent
}

class AlbumDetailViewModel(
    private val album: AlbumItem,
    private val repository: AlbumsRepository,
) : ViewModel() {

    // Title seeds from the album name so the bar paints before the load lands.
    private val _state = MutableStateFlow(AlbumDetailUiState(title = album.name))
    val state: StateFlow<AlbumDetailUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AlbumDetailEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AlbumDetailEvent> = _events.asSharedFlow()

    init {
        refresh()
    }

    /** Fire-and-forget refresh (Android). */
    fun refresh() {
        viewModelScope.launch { refreshAndWait() }
    }

    /** Reload the album's photos, suspending until done — iOS .refreshable awaits this. */
    suspend fun refreshAndWait() {
        _state.update { it.copy(isLoading = true, error = null) }
        try {
            val photos = repository.loadAlbumPhotos(album.albumId)
            _state.update {
                it.copy(isLoading = false, photos = photos, sections = groupIntoMonthSections(photos))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "album load failed: ${e.message}" }
            _state.update { it.copy(isLoading = false, error = e.message ?: "Couldn't load album") }
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

    /** Fire-and-forget remove-from-album of the selection (Android). */
    fun removeSelected() {
        viewModelScope.launch { removeSelectedAndWait() }
    }

    /**
     * Untags the selected photos from this album, suspending until done — iOS awaits this.
     * The photos themselves are untouched; only the album tag comes off, one header patch per
     * photo, so a partial result is normal and only the ones that landed leave the grid.
     */
    suspend fun removeSelectedAndWait() {
        val current = _state.value
        if (current.isRemoving || current.selectedIds.isEmpty()) return
        val doomed = current.selectedPhotos
        _state.update { it.copy(isRemoving = true) }
        val result = try {
            repository.removePhotos(album.albumId, doomed.map { it.fileId })
        } catch (e: CancellationException) {
            _state.update { it.copy(isRemoving = false) } // don't wedge future removes
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "remove failed: ${e.message}" }
            _state.update { it.copy(isRemoving = false) }
            _events.tryEmit(AlbumDetailEvent.Error(e.message ?: "Couldn't remove from album"))
            return
        }
        val removed = result.succeeded.toSet()
        _state.update {
            val remaining = it.photos.filterNot { p -> p.fileId in removed }
            it.copy(
                isRemoving = false,
                selectedIds = emptySet(),
                photos = remaining,
                sections = groupIntoMonthSections(remaining),
            )
        }
        if (removed.isNotEmpty()) _events.tryEmit(AlbumDetailEvent.Removed(removed.size))
        if (result.failed.isNotEmpty()) {
            _events.tryEmit(AlbumDetailEvent.Error("Couldn't remove ${result.failed.size} photo(s)"))
        }
    }

    companion object {
        private const val TAG = "AlbumDetailViewModel"
    }
}
