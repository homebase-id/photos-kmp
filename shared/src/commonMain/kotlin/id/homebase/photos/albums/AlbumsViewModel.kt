package id.homebase.photos.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.photos.data.AlbumsRepository
import id.homebase.photos.domain.AlbumItem
import id.homebase.photos.domain.PhotoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One album tile: the album plus its resolved cover photo (null until resolution lands). */
data class AlbumSummary(val album: AlbumItem, val cover: PhotoItem?)

/** Flat UI state for the Collections grid. Native screens (SwiftUI + Compose) render this. */
data class AlbumsUiState(
    val isLoading: Boolean = true,
    val albums: List<AlbumSummary> = emptyList(),
    val error: String? = null,
)

class AlbumsViewModel(
    private val repository: AlbumsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumsUiState())
    val state: StateFlow<AlbumsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Fire-and-forget refresh (Android). */
    fun refresh() {
        viewModelScope.launch { refreshAndWait() }
    }

    /** Reload albums, suspending until covers land — iOS .refreshable awaits this. */
    suspend fun refreshAndWait() {
        _state.update { it.copy(isLoading = true, error = null) }
        val albums = try {
            repository.loadAlbums()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "loadAlbums failed: ${e.message}" }
            _state.update { it.copy(isLoading = false, error = e.message ?: "Couldn't load albums") }
            return
        }
        // Names paint immediately; covers land in a second emission below.
        _state.update { s -> s.copy(isLoading = false, albums = albums.map { AlbumSummary(it, cover = null) }) }
        val summaries = coroutineScope {
            albums.map { album -> async { AlbumSummary(album, resolveCover(album)) } }.awaitAll()
        }
        _state.update { it.copy(albums = summaries) }
    }

    // ponytail: cover = full album query per album; cap with maxRecords when albums grow
    private suspend fun resolveCover(album: AlbumItem): PhotoItem? = try {
        val photos = repository.loadAlbumPhotos(album.albumId)
        photos.firstOrNull { it.fileId == album.coverFileId } ?: photos.firstOrNull()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(tag = TAG) { "cover load failed for ${album.albumId}: ${e.message}" }
        null
    }

    companion object {
        private const val TAG = "AlbumsViewModel"
    }
}
