package id.homebase.photos.library

import id.homebase.photos.data.FavoritesPage
import id.homebase.photos.data.PhotoStatusResult
import id.homebase.photos.data.PhotosRepository
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.search.SearchCriteria
import id.homebase.photos.viewer.VideoHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

/**
 * In-memory [PhotosRepository] shared by the Favorites/Archive/Trash ViewModel tests.
 * Mutations edit the fake's own lists, so the mutate-then-refresh loop each VM runs reads
 * back what the write did — the same loop the real repository goes through.
 */
internal class FakeLibraryPhotosRepository(
    favorites: List<PhotoItem> = emptyList(),
    archived: List<PhotoItem> = emptyList(),
    trashed: List<PhotoItem> = emptyList(),
    /** Override to force a smaller server page than the VM's limit, so a test can exercise loadMore. */
    var favoritesPageSize: Int? = null,
    var setFavoriteThrows: Boolean = false,
    var failFavoriteFor: Set<Uuid> = emptySet(),
    var setArchivedThrows: Boolean = false,
    var failArchivedFor: Set<Uuid> = emptySet(),
    var restoreThrows: Boolean = false,
    var failRestoreFor: Set<Uuid> = emptySet(),
    var permanentDeleteResult: Boolean = true,
    var permanentDeleteThrows: Boolean = false,
    var syncThrows: Boolean = false,
    /** Runs on every [sync] call, before it returns — lets a test prove reads happen after sync
     *  (e.g. append a row here and assert refreshAndWait's result includes it). */
    var onSync: (() -> Unit)? = null,
    /** Parks whichever mutation runs next, so a test can observe the in-flight guard. */
    val mutationGate: CompletableDeferred<Unit>? = null,
) : PhotosRepository {

    val favorites: MutableList<PhotoItem> = favorites.toMutableList()
    val archived: MutableList<PhotoItem> = archived.toMutableList()
    val trashed: MutableList<PhotoItem> = trashed.toMutableList()

    var syncCount = 0
        private set
    val setFavoriteCalls = mutableListOf<Pair<Uuid, Boolean>>()
    val setArchivedCalls = mutableListOf<Pair<List<Uuid>, Boolean>>()
    val restoreCalls = mutableListOf<List<Uuid>>()
    val permanentDeleteCalls = mutableListOf<List<Uuid>>()

    override fun observePhotos(): Flow<List<PhotoItem>> = MutableStateFlow(emptyList<PhotoItem>()).asStateFlow()
    override suspend fun loadPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> = emptyList()
    override suspend fun sync() {
        syncCount++
        if (syncThrows) throw IllegalStateException("sync exploded")
        onSync?.invoke()
    }
    override suspend fun deletePhotos(fileIds: List<Uuid>): Boolean = true
    override suspend fun loadThumbnailBytes(item: PhotoItem, maxDim: Int): ByteArray? = null
    override suspend fun loadOriginalBytes(item: PhotoItem): ByteArray? = null
    override suspend fun prepareVideo(item: PhotoItem): VideoHandle? = null
    override suspend fun disposeVideo(handle: VideoHandle) {}

    override suspend fun setFavorite(fileId: Uuid, favorite: Boolean): Boolean {
        setFavoriteCalls += fileId to favorite
        mutationGate?.await()
        if (setFavoriteThrows) throw IllegalStateException("setFavorite exploded")
        if (fileId in failFavoriteFor) return false
        if (!favorite) favorites.removeAll { it.fileId == fileId }
        return true
    }

    override suspend fun setArchived(fileIds: List<Uuid>, archived: Boolean): PhotoStatusResult {
        setArchivedCalls += fileIds to archived
        mutationGate?.await()
        if (setArchivedThrows) throw IllegalStateException("setArchived exploded")
        val (failed, succeeded) = fileIds.partition { it in failArchivedFor }
        if (!archived) this.archived.removeAll { it.fileId in succeeded.toSet() }
        return PhotoStatusResult(succeeded = succeeded, failed = failed)
    }

    override suspend fun softDelete(fileIds: List<Uuid>): PhotoStatusResult = PhotoStatusResult(succeeded = fileIds)

    override suspend fun restore(fileIds: List<Uuid>): PhotoStatusResult {
        restoreCalls += fileIds
        mutationGate?.await()
        if (restoreThrows) throw IllegalStateException("restore exploded")
        val (failed, succeeded) = fileIds.partition { it in failRestoreFor }
        trashed.removeAll { it.fileId in succeeded.toSet() }
        return PhotoStatusResult(succeeded = succeeded, failed = failed)
    }

    override suspend fun permanentDelete(fileIds: List<Uuid>): Boolean {
        permanentDeleteCalls += fileIds
        mutationGate?.await()
        if (permanentDeleteThrows) throw IllegalStateException("permanentDelete exploded")
        if (permanentDeleteResult) trashed.removeAll { it.fileId in fileIds.toSet() }
        return permanentDeleteResult
    }

    override suspend fun loadFavoritesPage(cursor: String?, limit: Int): FavoritesPage {
        val start = cursor?.toIntOrNull() ?: 0
        val pageSize = favoritesPageSize ?: limit
        val end = (start + pageSize).coerceAtMost(favorites.size)
        val page = if (start >= favorites.size) emptyList() else favorites.subList(start, end)
        val next = if (end < favorites.size) end.toString() else null
        return FavoritesPage(items = page.toList(), nextCursor = next)
    }

    override suspend fun loadArchivedPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> {
        val older = if (beforeUserDate == null) archived else archived.filter { it.userDate < beforeUserDate }
        return older.sortedByDescending { it.userDate }.take(limit)
    }

    override suspend fun loadTrashPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> {
        val older = if (beforeUserDate == null) trashed else trashed.filter { it.userDate < beforeUserDate }
        return older.sortedByDescending { it.userDate }.take(limit)
    }

    override suspend fun search(criteria: SearchCriteria): List<PhotoItem> = emptyList()
}
