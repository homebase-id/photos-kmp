package id.homebase.photos.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.photos.data.AlbumsRepository
import id.homebase.photos.data.PhotosRepository
import id.homebase.photos.domain.AlbumItem
import id.homebase.photos.timeline.TimelineSection
import id.homebase.photos.timeline.groupIntoMonthSections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Photo vs video chip — `ALL` maps to no [SearchCriteria.isVideo] constraint. */
enum class TypeFilter { ALL, PHOTOS, VIDEOS }

private fun TypeFilter.toIsVideoOrNull(): Boolean? = when (this) {
    TypeFilter.ALL -> null
    TypeFilter.PHOTOS -> false
    TypeFilter.VIDEOS -> true
}

/** Flat UI state for the Search screen. Read-only results — no selection/mutation here. */
data class SearchUiState(
    val query: String = "",
    val fromUserDate: Long? = null,
    val toUserDate: Long? = null,
    val typeFilter: TypeFilter = TypeFilter.ALL,
    val albumFilter: AlbumItem? = null,
    val sections: List<TimelineSection> = emptyList(),
    val recent: List<String> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val error: String? = null,
) {
    /** Nothing to search on — the screen shows recents instead of results. */
    val isIdle: Boolean
        get() = query.isBlank() && fromUserDate == null && toUserDate == null &&
            typeFilter == TypeFilter.ALL && albumFilter == null

    /** A search ran and came back with nothing. */
    val isEmpty: Boolean get() = hasSearched && sections.isEmpty()
}

/**
 * Metadata search (Batch E): date range, type, album, and free-text matching against album
 * names (no filename search — see the plan's scope ruling). [onQueryChange] only edits the
 * text box; [submit] is what actually runs a search, resolves free-text against album names,
 * and records the query in [recentStore]. The filter setters re-run the last search immediately
 * so chip changes feel live without a second explicit submit.
 */
class SearchViewModel(
    private val photosRepository: PhotosRepository,
    private val albumsRepository: AlbumsRepository,
    private val recentStore: RecentSearchesStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    // Tracks the in-flight search launch (filter setters + fire-and-forget submit) so a later
    // request cancels an earlier one still in flight — the last request wins, not the last to
    // finish. Not used for clearRecent/init's recents load, which don't race a search.
    private var searchJob: Job? = null

    init {
        viewModelScope.launch { _state.update { it.copy(recent = recentStore.load()) } }
    }

    /** Text-box edits only — no search until [submit]. */
    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun setDateRange(from: Long?, to: Long?) {
        _state.update { it.copy(fromUserDate = from, toUserDate = to) }
        launchSearch()
    }

    fun setTypeFilter(filter: TypeFilter) {
        _state.update { it.copy(typeFilter = filter) }
        launchSearch()
    }

    fun setAlbumFilter(album: AlbumItem?) {
        _state.update { it.copy(albumFilter = album) }
        launchSearch()
    }

    /** Fire-and-forget submit (Android) — routes through [submitAndWait]'s own job tracking. */
    fun submit() {
        viewModelScope.launch { submitAndWait() }
    }

    /**
     * Records a non-blank query in recents, then runs the search — iOS awaits this. Runs under
     * the same tracked [searchJob] as the filter setters (via [launchTracked]) so a concurrent
     * filter change cancels a stale submit (and vice versa) instead of racing it; the suspend
     * shape is kept so SKIE still exposes this as an awaitable async function on iOS.
     */
    suspend fun submitAndWait() {
        val query = _state.value.query
        launchTracked {
            if (query.isNotBlank()) {
                recentStore.push(query)
                _state.update { it.copy(recent = recentStore.load()) }
            }
            runSearch()
        }.join()
    }

    /** Back to the blank/no-filter state — clears results and every chip. */
    fun clearFilters() {
        _state.update {
            it.copy(fromUserDate = null, toUserDate = null, typeFilter = TypeFilter.ALL, albumFilter = null)
        }
        launchSearch()
    }

    fun clearRecent() {
        viewModelScope.launch {
            recentStore.clear()
            _state.update { it.copy(recent = emptyList()) }
        }
    }

    /** Cancels any in-flight search launch before starting the new one. */
    private fun launchSearch() {
        launchTracked { runSearch() }
    }

    /**
     * Cancels whatever [searchJob] currently tracks, launches [block] as the new one, and
     * records it as the current [searchJob] before returning — the single choke point both
     * [launchSearch] and [submitAndWait] go through so a submit and a filter change can never
     * run their searches concurrently; whichever fires second cancels the other.
     */
    private fun launchTracked(block: suspend () -> Unit): Job {
        searchJob?.cancel()
        val job = viewModelScope.launch { block() }
        searchJob = job
        return job
    }

    /**
     * Builds [SearchCriteria] from the current state and runs it, unless idle (blank query, no
     * filters) in which case the screen just resets to showing recents. A non-blank query is
     * resolved against album names (the scope ruling's free-text semantics) and unioned with any
     * explicit [SearchUiState.albumFilter] pick.
     *
     * A non-blank query that matches no album name — and has no explicit [SearchUiState.albumFilter]
     * to fall back on — means zero results by definition (free-text only ever matches album
     * names), so this short-circuits to an empty result set without calling the repository at
     * all, regardless of any date/type chips also set; otherwise those chips alone would run an
     * unconstrained-by-query search and silently ignore that the query matched nothing.
     */
    private suspend fun runSearch() {
        val current = _state.value
        if (current.isIdle) {
            _state.update { it.copy(sections = emptyList(), hasSearched = false, isSearching = false, error = null) }
            return
        }
        _state.update { it.copy(isSearching = true, error = null) }
        try {
            val matchedAlbumIds = if (current.query.isNotBlank()) {
                albumsRepository.loadAlbums()
                    .filter { it.name.contains(current.query, ignoreCase = true) }
                    .map { it.albumId }
            } else {
                emptyList()
            }
            if (current.query.isNotBlank() && matchedAlbumIds.isEmpty() && current.albumFilter == null) {
                _state.update { it.copy(isSearching = false, hasSearched = true, sections = emptyList(), error = null) }
                return
            }
            val albumIds = (matchedAlbumIds + listOfNotNull(current.albumFilter?.albumId)).distinct()
            val criteria = SearchCriteria(
                fromUserDate = current.fromUserDate,
                toUserDate = current.toUserDate,
                isVideo = current.typeFilter.toIsVideoOrNull(),
                albumIds = albumIds,
            )
            val results = photosRepository.search(criteria)
            _state.update {
                it.copy(isSearching = false, hasSearched = true, sections = groupIntoMonthSections(results))
            }
        } catch (e: CancellationException) {
            // Only reset if this job is still the current one — a superseding search (which
            // cancelled this one) may already be mid-flight with isSearching = true; wiping it
            // here would clobber that newer search's spinner.
            if (currentCoroutineContext()[Job] === searchJob) {
                _state.update { it.copy(isSearching = false) }
            }
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "search failed: ${e.message}" }
            _state.update { it.copy(isSearching = false, hasSearched = true, error = e.message ?: "Search failed") }
        }
    }

    private companion object {
        const val TAG = "SearchViewModel"
    }
}
