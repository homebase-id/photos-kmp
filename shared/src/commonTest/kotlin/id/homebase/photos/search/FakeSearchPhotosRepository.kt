package id.homebase.photos.search

import id.homebase.photos.data.FavoritesPage
import id.homebase.photos.data.PhotoStatusResult
import id.homebase.photos.data.PhotosRepository
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.viewer.VideoHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

/**
 * In-memory [PhotosRepository] for [SearchViewModel] tests. Records every [search] call's
 * [SearchCriteria] (so a test can assert what the VM composed) and serves [resultsByAlbum] /
 * [defaultResults] back, or throws when [searchThrows]. [resultsSequence] lets a test give
 * successive calls different results (e.g. a stale first call vs. a fresh second one);
 * [firstCallGate], when set, parks only the FIRST call so a test can simulate a slow in-flight
 * search that a later filter change should cancel before it resolves.
 */
internal class FakeSearchPhotosRepository(
    private val defaultResults: List<PhotoItem> = emptyList(),
    private val resultsByAlbum: Map<Uuid, List<PhotoItem>> = emptyMap(),
    var searchThrows: Boolean = false,
    private val resultsSequence: MutableList<List<PhotoItem>> = mutableListOf(),
    val firstCallGate: CompletableDeferred<Unit>? = null,
) : PhotosRepository {

    val searchCalls = mutableListOf<SearchCriteria>()
    private var callCount = 0

    override suspend fun search(criteria: SearchCriteria): List<PhotoItem> {
        callCount++
        searchCalls += criteria
        if (callCount == 1) firstCallGate?.await()
        if (searchThrows) throw IllegalStateException("search exploded")
        if (resultsSequence.isNotEmpty()) return resultsSequence.removeAt(0)
        if (criteria.albumIds.isNotEmpty()) {
            val seen = LinkedHashSet<Uuid>()
            val results = mutableListOf<PhotoItem>()
            for (albumId in criteria.albumIds) {
                (resultsByAlbum[albumId] ?: emptyList()).forEach { if (seen.add(it.fileId)) results += it }
            }
            return results
        }
        return defaultResults
    }

    override fun observePhotos(): Flow<List<PhotoItem>> = MutableStateFlow(emptyList<PhotoItem>()).asStateFlow()
    override suspend fun loadPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> = emptyList()
    override suspend fun sync() {}
    override suspend fun deletePhotos(fileIds: List<Uuid>): Boolean = true
    override suspend fun loadThumbnailBytes(item: PhotoItem, maxDim: Int): ByteArray? = null
    override suspend fun loadOriginalBytes(item: PhotoItem): ByteArray? = null
    override suspend fun prepareVideo(item: PhotoItem): VideoHandle? = null
    override suspend fun disposeVideo(handle: VideoHandle) {}
    override suspend fun setFavorite(fileId: Uuid, favorite: Boolean): Boolean = true
    override suspend fun setArchived(fileIds: List<Uuid>, archived: Boolean): PhotoStatusResult = PhotoStatusResult()
    override suspend fun softDelete(fileIds: List<Uuid>): PhotoStatusResult = PhotoStatusResult()
    override suspend fun restore(fileIds: List<Uuid>): PhotoStatusResult = PhotoStatusResult()
    override suspend fun permanentDelete(fileIds: List<Uuid>): Boolean = true
    override suspend fun loadFavoritesPage(cursor: String?, limit: Int): FavoritesPage = FavoritesPage(emptyList(), null)
    override suspend fun loadArchivedPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> = emptyList()
    override suspend fun loadTrashPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> = emptyList()
}
