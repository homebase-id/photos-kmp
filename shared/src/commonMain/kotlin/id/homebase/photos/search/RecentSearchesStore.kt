package id.homebase.photos.search

import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.KeyValueWrapper
import okio.ByteString.Companion.encodeUtf8
import kotlin.uuid.Uuid

/**
 * Recent search queries, most-recent-first, capped at [CAP], deduped case-insensitively (a
 * re-searched query moves to the front instead of appearing twice). Reuses the existing
 * `KeyValue` table under one fixed key (no new `.sq` → no `DATABASE_VERSION` bump), same shape
 * as [id.homebase.photos.backup.BackupFolderSelectionStore] — the whole list is one JSON value.
 */
class RecentSearchesStore(
    private val keyValue: KeyValueWrapper,
) {
    /** Most-recent-first, or the empty list if nothing was ever searched. */
    suspend fun load(): List<String> {
        val data = keyValue.selectByKey(RECENTS_KEY)?.data_ ?: return emptyList()
        if (data.isEmpty()) return emptyList()
        return runCatching {
            OdinSystemSerializer.deserialize<List<String>>(data.decodeToString())
        }.getOrDefault(emptyList())
    }

    /** Move [query] to the front, dropping any case-insensitive dupe, then cap at [CAP]. */
    suspend fun push(query: String) {
        if (query.isBlank()) return
        val updated = (listOf(query) + load().filterNot { it.equals(query, ignoreCase = true) }).take(CAP)
        keyValue.upsertValue(RECENTS_KEY, OdinSystemSerializer.serialize(updated).encodeToByteArray())
    }

    suspend fun clear() {
        keyValue.deleteByKey(RECENTS_KEY)
    }

    private companion object {
        const val KEY_NAMESPACE = "homebase-photos.search-recents"
        const val ID_BYTES = 16
        const val CAP = 10
        val RECENTS_KEY: Uuid =
            Uuid.fromByteArray(KEY_NAMESPACE.encodeUtf8().sha256().substring(0, ID_BYTES).toByteArray())
    }
}
