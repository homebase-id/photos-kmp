package id.homebase.photos.data

import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.viewer.VideoHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

// 8 base64 webp micro-placeholders (20x27 px, ~100 bytes each, earthy tones) so the
// mock actually renders instead of drawing blank squares.
private val PLACEHOLDERS = listOf(
    "UklGRl4AAABXRUJQVlA4IFIAAABQBACdASoUABsAPvVmpk6qpaMiMAwBUB6JYwCBhgiujA8I5hzGxPMZfuwAAP7DVu/ZRmguqSat6LVRP7DRkv/FAWwNtys4aj47l+m7+Wo7IAAA",
    "UklGRmQAAABXRUJQVlA4IFgAAAAwBACdASoUABsAPwFqrE8rJiQiMAgBYCAJZwCuHBgc8BlzjlpkGboOBIAA/RxpZSvu7saH8DK3aO30TaxqbmYY21oWLDRNmzBl9X3Iv88XmbCGmMWmgAAA",
    "UklGRmAAAABXRUJQVlA4IFQAAACwBACdASoUABsAPwFwrlCrP6QisBgIA/AgCWMAtRevAOjSroj8GoVoA+aYAHUQAP7ea7fhDFGtaQMCGQ3uViBmEWfMrXWSBTljqgPNIJt6UD7DAAA=",
    "UklGRlwAAABXRUJQVlA4IFAAAABQBACdASoUABsAPwFysFIrJr4iqAqrwCAJZwC/7A9mWX1sa26ZYUegzuoAAP7TmzdJCRxFVG1Abk7Vwmt7AORxFSHg8Vz+TM1GcjKk1KAAAA==",
    "UklGRlwAAABXRUJQVlA4IFAAAAAwBACdASoUABsAPv1urU6rJrwiMBgMA4AfiWcAzNAQ6B5ShJW9pATttEAA/q6cVj16Hk+rqTts9iZ1W0gTSJwFL63jGILIUXF+tJ9D52AAAA==",
    "UklGRmIAAABXRUJQVlA4IFYAAADQBACdASoUABsAPvlqqE6qpiOiMAwBUB8JZwDA3CHftRgGZJvFL4AyisueQzVOAAD+nODXP0kno187i6L2//24zo8aqjP3wLOqgJ6gDFAYjprUfwJwAA==",
    "UklGRmAAAABXRUJQVlA4IFQAAACQBACdASoUABsAPwFoqlArJbqisBgMA1AgCWUAvzgQtgx2Q1z29y6mOLMxrzAA/ucfpJV50dumL440AhdFFhYbuhdrF8nMzx8D3+TM1CMsy4YAAAA=",
    "UklGRmAAAABXRUJQVlA4IFQAAACwBACdASoUABsAPwFssFCrJaSisBgIAWAgCWUAwNwGncIH9D8OimoJ+4IDhlQAAPcEFOepGpQBmq0qiwhTPIslSqWlqcjnnjcWAYsaJ4p7oGUeAAA=",
)

// Per-day cluster shape: shots taken on each calendar day walking backward from the
// anchor. Bursts (5/8/7/6…) fill real 4-column grid rows; 0s are gap days with no
// photos — a realistic camera roll. The given 16-day pattern packs ~40 shots into 16
// days (120 items ≈ 48 days ≈ under 2 months), too dense to span 3 months, so it is
// extended with 24 trailing gap days: cycle = 40 days, 40 shots each → 120 items land
// across ~95 days (~3 months). Non-zero bursts are preserved verbatim and in order.
private val COUNTS: List<Int> =
    listOf(5, 0, 3, 8, 0, 1, 4, 0, 0, 6, 2, 0, 7, 1, 0, 3) + List(24) { 0 }

/**
 * Seeded in-memory repository so the timeline grid renders BEFORE login/sync
 * exist. ~120 synthetic items grouped into per-day CLUSTERS (bursts of up to 8
 * shots on active days, gap days in between) spread across ~3 months, so a
 * 4-column grid fills real rows instead of one lonely photo per day. Deterministic
 * ordering, real userDate-DESC + before-cursor pagination so the ViewModel and the
 * native grid behave exactly as they will against the real index.
 */
class MockPhotosRepository(
    seedCount: Int = 120,
    // Anchor "now-ish" so the seed lands on recognisable recent months. Fixed for
    // determinism (tests + reproducible screenshots); not wall-clock.
    anchorUserDate: Long = 1_782_648_000_000L, // 2026-06-28T12:00Z
) : PhotosRepository {

    // userDate DESC, newest first. Walk calendar days back from the anchor emitting
    // COUNTS[day] shots per day, ~7 min apart within a day (descending, so the whole
    // list stays strictly userDate DESC), until seedCount items exist.
    // var: deletePhotos shrinks the seed like a real drive would.
    private var all: List<PhotoItem> = buildList {
        val oneDayMs = 24L * 60 * 60 * 1000
        val stepMs = 7L * 60 * 1000 // ~7 min between shots in the same day
        var index = 0
        var day = 0
        while (index < seedCount) {
            val count = COUNTS[day % COUNTS.size]
            val dayAnchor = anchorUserDate - day.toLong() * oneDayMs
            for (j in 0 until count) {
                if (index >= seedCount) break
                add(
                    PhotoItem(
                        fileId = Uuid.random(),
                        uniqueId = Uuid.random(),
                        userDate = dayAnchor - j * stepMs,
                        isVideo = index % 9 == 0, // a sprinkling of videos
                        pixelWidth = 900,
                        pixelHeight = 1200,
                        previewPlaceholder = PLACEHOLDERS[index % PLACEHOLDERS.size],
                        driveId = Uuid.random(),
                        payloadKey = "dflt_key",
                    ),
                )
                index++
            }
            day++
        }
    }.sortedByDescending { it.userDate }

    private val _photos = MutableStateFlow(all)

    /** Mirrors the real backend's single `archivalStatus` field — never both at once. */
    private enum class MockStatus { ARCHIVED, TRASHED }

    private val favoriteIds = mutableSetOf<Uuid>()
    private val statusById = mutableMapOf<Uuid, MockStatus>() // absent = None

    override fun observePhotos(): Flow<List<PhotoItem>> = _photos.asStateFlow()

    override suspend fun loadPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> {
        val older = if (beforeUserDate == null) all else all.filter { it.userDate < beforeUserDate }
        return older.filterNot { it.fileId in statusById }.take(limit)
    }

    override suspend fun sync() {
        // No-op: the mock is already "synced".
    }

    override suspend fun deletePhotos(fileIds: List<Uuid>): Boolean {
        val doomed = fileIds.toSet()
        all = all.filterNot { it.fileId in doomed }
        favoriteIds -= doomed
        doomed.forEach(statusById::remove)
        _photos.value = all
        return true
    }

    override suspend fun setFavorite(fileId: Uuid, favorite: Boolean): Boolean {
        if (favorite) favoriteIds += fileId else favoriteIds -= fileId
        all = all.map { if (it.fileId == fileId) it.copy(isFavorite = favorite) else it }
        _photos.value = all
        return true
    }

    override suspend fun setArchived(fileIds: List<Uuid>, archived: Boolean): PhotoStatusResult {
        if (archived) fileIds.forEach { statusById[it] = MockStatus.ARCHIVED } else fileIds.forEach(statusById::remove)
        return PhotoStatusResult(succeeded = fileIds)
    }

    override suspend fun softDelete(fileIds: List<Uuid>): PhotoStatusResult {
        fileIds.forEach { statusById[it] = MockStatus.TRASHED }
        return PhotoStatusResult(succeeded = fileIds)
    }

    override suspend fun restore(fileIds: List<Uuid>): PhotoStatusResult {
        fileIds.forEach(statusById::remove)
        return PhotoStatusResult(succeeded = fileIds)
    }

    override suspend fun permanentDelete(fileIds: List<Uuid>): Boolean = deletePhotos(fileIds)

    override suspend fun loadFavoritesPage(cursor: String?, limit: Int): FavoritesPage {
        // Mirrors the real favoritesQuery's archivalStatus=[0,1,3] — everything but the bin.
        val items = all.filter { it.fileId in favoriteIds && statusById[it.fileId] != MockStatus.TRASHED }
            .take(limit)
        return FavoritesPage(items = items, nextCursor = null)
    }

    override suspend fun loadArchivedPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> {
        val older = if (beforeUserDate == null) all else all.filter { it.userDate < beforeUserDate }
        return older.filter { statusById[it.fileId] == MockStatus.ARCHIVED }.take(limit)
    }

    override suspend fun loadTrashPage(beforeUserDate: Long?, limit: Int): List<PhotoItem> {
        val older = if (beforeUserDate == null) all else all.filter { it.userDate < beforeUserDate }
        return older.filter { statusById[it.fileId] == MockStatus.TRASHED }.take(limit)
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun loadThumbnailBytes(item: PhotoItem, maxDim: Int): ByteArray? =
        item.previewPlaceholder?.let { Base64.decode(it) }

    // Deterministic small bytes so share/save paths have something real to hand over.
    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun loadOriginalBytes(item: PhotoItem): ByteArray? =
        item.previewPlaceholder?.let { Base64.decode(it) } ?: item.fileId.toString().encodeToByteArray()

    override suspend fun prepareVideo(item: PhotoItem): VideoHandle? = null

    override suspend fun disposeVideo(handle: VideoHandle) {}
}
