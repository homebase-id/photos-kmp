package id.homebase.photos.backup

import id.homebase.api.sync.database.KeyValueWrapper
import okio.ByteString.Companion.encodeUtf8
import kotlin.uuid.Uuid

/**
 * Persisted set of folderIds the user chose to back up (D6, folder-selective backup). Reuses the
 * existing `KeyValue` table (no new `.sq` → no `DATABASE_VERSION` bump → no table-wipe on upgrade),
 * exactly like [BackupLedger] — but under a single fixed, namespaced key, because the whole
 * selection is one value rather than one row per asset.
 *
 * The default (key never written) is the empty set: **nothing selected**, so enabling backup
 * uploads nothing until folders are deliberately chosen.
 *
 * Serialization: folderIds joined with a single [SEPARATOR]. Safe because a folderId is a MediaStore
 * BUCKET_ID rendered as a string — always `[0-9-]` (a signed 32-bit hash), so it can never contain
 * a comma. If a future crawler ever surfaces non-numeric folderIds this assumption must be revisited.
 */
class BackupFolderSelectionStore(
    private val keyValue: KeyValueWrapper,
) {
    /** The currently selected folderIds, or the empty set if nothing was ever chosen. */
    suspend fun selected(): Set<String> {
        val data = keyValue.selectByKey(SELECTION_KEY)?.data_ ?: return emptySet()
        if (data.isEmpty()) return emptySet()
        return data.decodeToString().split(SEPARATOR).filter { it.isNotEmpty() }.toSet()
    }

    /** Persist [folderIds] as the whole selection, replacing any previous value. */
    suspend fun setSelected(folderIds: Set<String>) {
        keyValue.upsertValue(SELECTION_KEY, folderIds.joinToString(SEPARATOR).encodeToByteArray())
    }

    /** Flip [folderId] in the selection, persist, and return the updated set. */
    suspend fun toggle(folderId: String): Set<String> {
        val current = selected()
        val updated = if (folderId in current) current - folderId else current + folderId
        setSelected(updated)
        return updated
    }

    private companion object {
        // Single fixed key = first 16 bytes of sha256("<namespace>"), as a Uuid — same collision-safe
        // namespacing as BackupLedger, but keyless (one value for the whole selection).
        const val KEY_NAMESPACE = "homebase-photos.backup-folder-selection"
        const val SEPARATOR = ","
        const val ID_BYTES = 16
        val SELECTION_KEY: Uuid =
            Uuid.fromByteArray(KEY_NAMESPACE.encodeUtf8().sha256().substring(0, ID_BYTES).toByteArray())
    }
}
