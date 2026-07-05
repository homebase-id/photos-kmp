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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
)

class AlbumDetailViewModel(
    private val album: AlbumItem,
    private val repository: AlbumsRepository,
) : ViewModel() {

    // Title seeds from the album name so the bar paints before the load lands.
    private val _state = MutableStateFlow(AlbumDetailUiState(title = album.name))
    val state: StateFlow<AlbumDetailUiState> = _state.asStateFlow()

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

    companion object {
        private const val TAG = "AlbumDetailViewModel"
    }
}
