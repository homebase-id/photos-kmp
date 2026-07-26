package id.homebase.photos.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.photos.data.PhotosRepository
import id.homebase.photos.data.setFavoriteBatch
import kotlinx.coroutines.flow.filterIsInstance
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
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Flat UI state for the timeline grid. Native screens (SwiftUI + Compose) render this. */
data class TimelineUiState(
    val isLoading: Boolean = true,
    val isPaginating: Boolean = false, // older-page append in flight; footer spinner only
    val sections: List<TimelineSection> = emptyList(), // month-grouped for sticky headers
    val pagedItems: List<PhotoItem> = emptyList(),      // flat list backing the viewer pager
    val endReached: Boolean = false,
    val error: String? = null,
    val selectedIds: Set<String> = emptySet(),          // PhotoItem.fileId.toString() keys
    val isDeleting: Boolean = false,
    val isMutating: Boolean = false,                    // a favorite/archive write is in flight
) {
    val inSelectionMode: Boolean get() = selectedIds.isNotEmpty()

    fun isSelected(photo: PhotoItem): Boolean = photo.fileId.toString() in selectedIds

    /** The selected photos in grid order — what delete and add-to-album act on. */
    val selectedPhotos: List<PhotoItem> get() = pagedItems.filter { isSelected(it) }
}

/** A month bucket: full-month title ("June 2026") + its photos, newest first. */
data class TimelineSection(val title: String, val items: List<PhotoItem>)

/** One-time events the native layer consumes (kept off the StateFlow). */
sealed interface TimelineEvent {
    data class Error(val message: String) : TimelineEvent
    data class Deleted(val count: Int) : TimelineEvent
    data class Favorited(val count: Int) : TimelineEvent
    data class Archived(val count: Int) : TimelineEvent
}

class TimelineViewModel(
    private val repository: PhotosRepository,
    eventBus: EventBus? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(TimelineUiState())
    val state: StateFlow<TimelineUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TimelineEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<TimelineEvent> = _events.asSharedFlow()

    init {
        loadFirstPage()
        // Sync-completion reconcile: every finished sync round re-reads the newest page, so new
        // server files appear regardless of what triggered the sync or whether the UI's refresh
        // task survived (iOS cancels .refreshable tasks freely — QA 2026-07-05).
        eventBus?.let { bus ->
            viewModelScope.launch {
                bus.events
                    .filterIsInstance<BackendEvent.DriveEvent.Stopped>()
                    .collect { reloadNewestIfIdle() } // single mounted drive — no driveId filter needed
            }
        }
    }

    private suspend fun reloadNewestIfIdle() {
        val current = _state.value
        // ponytail: deep-paginated sessions skip the live reload — the next open shows new items.
        if (current.isPaginating || current.pagedItems.size > PAGE_SIZE) return
        val page = safeLoad(beforeUserDate = null) ?: return
        if (page == _state.value.pagedItems) return // unchanged — skip the recompose
        applyReplace(page)
    }

    /** Fire-and-forget refresh (Android pull-to-refresh drives isLoading instead). */
    fun refresh() {
        viewModelScope.launch { refreshAndWait() }
    }

    /** Sync then reload, suspending until done — iOS .refreshable awaits this. */
    suspend fun refreshAndWait() {
        _state.update { it.copy(isLoading = true, error = null) }
        try {
            repository.sync()
        } catch (e: CancellationException) {
            // iOS .refreshable cancels freely — never surface that as an error or drop content.
            _state.update { it.copy(isLoading = false) }
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "refresh sync failed: ${e.message}" }
            emitError(e.message ?: "Sync failed")
        }
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

    /** Fire-and-forget delete of the selection (Android). */
    fun deleteSelected() {
        viewModelScope.launch { deleteSelectedAndWait() }
    }

    /** Batch-delete the selected photos, suspending until done — iOS awaits this. */
    suspend fun deleteSelectedAndWait() {
        val current = _state.value
        if (current.isDeleting || current.selectedIds.isEmpty()) return
        val doomed = current.selectedPhotos
        _state.update { it.copy(isDeleting = true) }
        val deleted = try {
            repository.deletePhotos(doomed.map { it.fileId })
        } catch (e: CancellationException) {
            _state.update { it.copy(isDeleting = false) } // don't wedge future deletes if the awaiting Task dies
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "delete failed: ${e.message}" }
            false
        }
        if (deleted) {
            _state.update {
                val remaining = it.pagedItems - doomed.toSet()
                it.copy(
                    isDeleting = false,
                    selectedIds = emptySet(),
                    pagedItems = remaining,
                    sections = groupIntoMonthSections(remaining),
                )
            }
            _events.tryEmit(TimelineEvent.Deleted(doomed.size))
        } else {
            _state.update { it.copy(isDeleting = false) }
            emitError("Couldn't delete")
        }
    }

    /** Fire-and-forget favorite of the selection (Android). */
    fun favoriteSelected() {
        viewModelScope.launch { favoriteSelectedAndWait() }
    }

    /**
     * Favorite the selected photos, suspending until done — iOS awaits this. Flips `isFavorite`
     * on the matching items in place (they stay in the timeline — only Archive/Trash drop items),
     * clears the selection on success like every other selection mutation, and emits a
     * [TimelineEvent.Error] alongside the count on any partial failure.
     */
    suspend fun favoriteSelectedAndWait() {
        val current = _state.value
        if (current.isMutating || current.selectedIds.isEmpty()) return
        val targets = current.selectedPhotos
        _state.update { it.copy(isMutating = true) }
        val succeededIds = try {
            repository.setFavoriteBatch(targets.map { it.fileId }, favorite = true)
        } catch (e: CancellationException) {
            _state.update { it.copy(isMutating = false) }
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "favorite failed: ${e.message}" }
            _state.update { it.copy(isMutating = false) }
            emitError(e.message ?: "Couldn't favorite")
            return
        }
        // No WebSocket — the local index only sees this write via an explicit sync, so
        // Archive/Trash (and a future loadMore here) don't act on a stale row.
        viewModelScope.launch { runCatching { repository.sync() } }
        _state.update {
            val updated = it.pagedItems.map { p -> if (p.fileId in succeededIds) p.copy(isFavorite = true) else p }
            it.copy(
                isMutating = false,
                selectedIds = emptySet(),
                pagedItems = updated,
                sections = groupIntoMonthSections(updated),
            )
        }
        _events.tryEmit(TimelineEvent.Favorited(succeededIds.size))
        val failed = targets.size - succeededIds.size
        if (failed > 0) emitError("Couldn't favorite $failed item(s)")
    }

    /** Fire-and-forget archive of the selection (Android). */
    fun archiveSelected() {
        viewModelScope.launch { archiveSelectedAndWait() }
    }

    /**
     * Archive the selected photos, suspending until done — iOS awaits this. Mirrors
     * [deleteSelectedAndWait]: drops the succeeded ones from state and clears the selection.
     */
    suspend fun archiveSelectedAndWait() {
        val current = _state.value
        if (current.isMutating || current.selectedIds.isEmpty()) return
        val doomed = current.selectedPhotos
        _state.update { it.copy(isMutating = true) }
        val result = try {
            repository.setArchived(doomed.map { it.fileId }, archived = true)
        } catch (e: CancellationException) {
            _state.update { it.copy(isMutating = false) }
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "archive failed: ${e.message}" }
            _state.update { it.copy(isMutating = false) }
            emitError(e.message ?: "Couldn't archive")
            return
        }
        // No WebSocket — reconcile the local index so Archive can see this photo.
        viewModelScope.launch { runCatching { repository.sync() } }
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
        _events.tryEmit(TimelineEvent.Archived(succeededIds.size))
        if (result.failed.isNotEmpty()) emitError("Couldn't archive ${result.failed.size} item(s)")
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

    private fun loadFirstPage() {
        viewModelScope.launch {
            val page = safeLoad(beforeUserDate = null)
            if (page != null) applyReplace(page)
            // First authenticated launch: the local index is empty until the first sync, so
            // kick one awaited sync+reload. Logged out this is a cheap no-op (sync's start()
            // declines without credentials, so no page ever lands and no loop ensues).
            if (page.isNullOrEmpty()) refreshAndWait()
        }
    }

    /** One page, or null when the read fails — callers keep existing content on null. */
    private suspend fun safeLoad(beforeUserDate: Long?): List<PhotoItem>? = try {
        repository.loadPage(beforeUserDate = beforeUserDate, limit = PAGE_SIZE)
    } catch (e: CancellationException) {
        _state.update { it.copy(isLoading = false, isPaginating = false) }
        throw e
    } catch (e: Exception) {
        Logger.w(tag = TAG) { "loadPage failed: ${e.message}" }
        emitError(e.message ?: "Couldn't load photos")
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
        _events.tryEmit(TimelineEvent.Error(message))
    }

    companion object {
        private const val TAG = "TimelineViewModel"
        const val PAGE_SIZE = 60
    }
}

/**
 * Group [items] into month sections titled "LLLL yyyy" (full English month + year,
 * e.g. "June 2026"). Sections are newest-month-first and items within a section are
 * newest-first, independent of incoming order. Bucketing is done in UTC so the
 * section a photo lands in is stable regardless of the viewer's local zone.
 */
fun groupIntoMonthSections(items: List<PhotoItem>): List<TimelineSection> {
    if (items.isEmpty()) return emptyList()
    // Sort newest-first so LinkedHashMap's first-seen order yields newest-month-first
    // sections and newest-first items within each section, regardless of input order.
    val buckets = LinkedHashMap<String, MutableList<PhotoItem>>()
    for (item in items.sortedByDescending { it.userDate }) {
        val dt = Instant.fromEpochMilliseconds(item.userDate).toLocalDateTime(TimeZone.UTC)
        val title = monthSectionTitle(dt)
        buckets.getOrPut(title) { mutableListOf() }.add(item)
    }
    return buckets.map { (title, list) -> TimelineSection(title, list) }
}

/**
 * Append [page] (already userDate DESC and strictly older than existing) to
 * [sections] without re-sorting or rebuilding prior months. The page's first
 * month bucket merges into the last existing section when titles match.
 */
fun appendToMonthSections(sections: List<TimelineSection>, page: List<PhotoItem>): List<TimelineSection> {
    if (page.isEmpty()) return sections
    val newSections = groupIntoMonthSections(page)
    val lastExisting = sections.lastOrNull()
    val firstNew = newSections.first()
    return if (lastExisting != null && lastExisting.title == firstNew.title) {
        val merged = TimelineSection(lastExisting.title, lastExisting.items + firstNew.items)
        sections.dropLast(1) + merged + newSections.drop(1)
    } else {
        sections + newSections
    }
}

private fun monthSectionTitle(dt: LocalDateTime): String = "${fullMonthName(dt.month)} ${dt.year}"

// Locale-free full month names — the shared section title is stable English;
// native UIs may localise the rendered header if they choose.
private fun fullMonthName(month: Month): String = when (month) {
    Month.JANUARY -> "January"
    Month.FEBRUARY -> "February"
    Month.MARCH -> "March"
    Month.APRIL -> "April"
    Month.MAY -> "May"
    Month.JUNE -> "June"
    Month.JULY -> "July"
    Month.AUGUST -> "August"
    Month.SEPTEMBER -> "September"
    Month.OCTOBER -> "October"
    Month.NOVEMBER -> "November"
    Month.DECEMBER -> "December"
}
