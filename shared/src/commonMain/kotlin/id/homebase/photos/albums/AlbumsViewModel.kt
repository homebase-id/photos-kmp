package id.homebase.photos.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.photos.data.AlbumMembershipResult
import id.homebase.photos.data.AlbumsRepository
import id.homebase.photos.domain.AlbumItem
import id.homebase.photos.domain.PhotoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** One album tile: the album plus its resolved cover photo (null until resolution lands). */
data class AlbumSummary(val album: AlbumItem, val cover: PhotoItem?)

/** Flat UI state for the Collections grid. Native screens (SwiftUI + Compose) render this. */
data class AlbumsUiState(
    val isLoading: Boolean = true,
    val albums: List<AlbumSummary> = emptyList(),
    val error: String? = null,
    val isMutating: Boolean = false, // a create/rename/delete/cover/add write is in flight
)

/** One-time events the native layer consumes (kept off the StateFlow). */
sealed interface AlbumsEvent {
    data class Error(val message: String) : AlbumsEvent
    data class Created(val album: AlbumItem) : AlbumsEvent
    data class Renamed(val album: AlbumItem) : AlbumsEvent
    data class Deleted(val album: AlbumItem) : AlbumsEvent
    data class CoverSet(val album: AlbumItem) : AlbumsEvent
    data class PhotosAdded(val albumTag: Uuid, val added: Int, val failed: Int) : AlbumsEvent
}

class AlbumsViewModel(
    private val repository: AlbumsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumsUiState())
    val state: StateFlow<AlbumsUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AlbumsEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AlbumsEvent> = _events.asSharedFlow()

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

    // --- mutations -----------------------------------------------------------------------

    /** Fire-and-forget create (Android). */
    fun createAlbum(name: String) {
        viewModelScope.launch { createAlbumAndWait(name) }
    }

    /** Creates an album, optimistically inserting it; returns null when the write failed. */
    suspend fun createAlbumAndWait(name: String): AlbumItem? = mutate("Couldn't create album") {
        val album = repository.createAlbum(name.trim(), null)
        _state.update { it.copy(albums = listOf(AlbumSummary(album, cover = null)) + it.albums) }
        _events.tryEmit(AlbumsEvent.Created(album))
        album
    }

    /** Fire-and-forget rename (Android). */
    fun rename(album: AlbumItem, newName: String) {
        viewModelScope.launch { renameAndWait(album, newName) }
    }

    suspend fun renameAndWait(album: AlbumItem, newName: String): AlbumItem? =
        mutate("Couldn't rename album") {
            val renamed = repository.renameAlbum(album, newName.trim())
            replaceAlbum(renamed)
            _events.tryEmit(AlbumsEvent.Renamed(renamed))
            renamed
        }

    /** Fire-and-forget delete (Android). */
    fun delete(album: AlbumItem) {
        viewModelScope.launch { deleteAndWait(album) }
    }

    suspend fun deleteAndWait(album: AlbumItem): Boolean = mutate("Couldn't delete album") {
        if (!repository.deleteAlbum(album)) error("Couldn't delete album")
        _state.update { s -> s.copy(albums = s.albums.filterNot { it.album.fileId == album.fileId }) }
        _events.tryEmit(AlbumsEvent.Deleted(album))
        true
    } ?: false

    /** Fire-and-forget cover pin (Android). */
    fun setCover(album: AlbumItem, photoFileId: Uuid) {
        viewModelScope.launch { setCoverAndWait(album, photoFileId) }
    }

    suspend fun setCoverAndWait(album: AlbumItem, photoFileId: Uuid): AlbumItem? =
        mutate("Couldn't set cover") {
            val updated = repository.setCover(album, photoFileId)
            replaceAlbum(updated)
            _events.tryEmit(AlbumsEvent.CoverSet(updated))
            updated
        }

    /** Fire-and-forget add-to-album, the picker entry point from Timeline/Viewer (Android). */
    fun addToAlbum(albumTag: Uuid, fileIds: List<Uuid>) {
        viewModelScope.launch { addToAlbumAndWait(albumTag, fileIds) }
    }

    /**
     * Tags [fileIds] into the album. One header patch per photo, so a partial result is normal —
     * the returned [AlbumMembershipResult] (and the [AlbumsEvent.PhotosAdded] event) carries the
     * per-file split so the UI can say "3 of 5 added".
     */
    suspend fun addToAlbumAndWait(albumTag: Uuid, fileIds: List<Uuid>): AlbumMembershipResult? =
        mutate("Couldn't add to album") {
            val result = repository.addPhotos(albumTag, fileIds)
            _events.tryEmit(
                AlbumsEvent.PhotosAdded(albumTag, result.succeeded.size, result.failed.size),
            )
            result
        }

    /** "New album" straight from the picker: create, then tag [fileIds] into it. */
    suspend fun createAlbumWithPhotosAndWait(name: String, fileIds: List<Uuid>): AlbumItem? {
        val album = createAlbumAndWait(name) ?: return null
        addToAlbumAndWait(album.albumId, fileIds)
        return album
    }

    /**
     * Album files reach [AlbumsRepository.loadAlbums] only through the local index, which a
     * write doesn't touch — so every mutation reconciles with a sync + reload in the background
     * while the optimistic patch keeps the grid responsive.
     */
    private suspend fun <T> mutate(fallbackMessage: String, block: suspend () -> T): T? {
        if (_state.value.isMutating) return null
        _state.update { it.copy(isMutating = true) }
        return try {
            block().also {
                _state.update { s -> s.copy(isMutating = false) }
                viewModelScope.launch { syncAndReload() }
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(isMutating = false) } // never wedge future writes
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "$fallbackMessage: ${e.message}" }
            _state.update { it.copy(isMutating = false) }
            _events.tryEmit(AlbumsEvent.Error(e.message ?: fallbackMessage))
            null
        }
    }

    private suspend fun syncAndReload() {
        try {
            repository.sync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "post-write sync failed: ${e.message}" }
            return // keep the optimistic state; the next refresh reconciles
        }
        refreshAndWait()
    }

    private fun replaceAlbum(album: AlbumItem) {
        _state.update { s ->
            s.copy(
                albums = s.albums.map {
                    if (it.album.fileId == album.fileId) it.copy(album = album) else it
                },
            )
        }
    }

    /** Pinned cover wins; an unpinned (or unsynced) one falls back to the newest member photo. */
    private suspend fun resolveCover(album: AlbumItem): PhotoItem? = try {
        album.coverFileId?.let { repository.loadPhoto(it) }
            ?: repository.loadNewestAlbumPhoto(album.albumId)
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
