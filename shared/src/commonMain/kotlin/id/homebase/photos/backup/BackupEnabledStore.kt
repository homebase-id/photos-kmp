package id.homebase.photos.backup

import id.homebase.api.sync.database.KeyValueWrapper
import okio.ByteString.Companion.encodeUtf8
import kotlin.uuid.Uuid

/**
 * Persisted backup on/off flag (the toggle intent). Reuses the existing `KeyValue` table (no new
 * `.sq` → no `DATABASE_VERSION` bump → no table-wipe on upgrade), under a single fixed, namespaced
 * key — exactly like [BackupFolderSelectionStore]. The default (key never written) is `false`:
 * backup off, so the UI never shows a bogus "On" before the user opts in.
 *
 * Stored as one byte: `1` = on, anything else (incl. absent) = off.
 */
class BackupEnabledStore(
    private val keyValue: KeyValueWrapper,
) {
    /** Whether backup is enabled, or `false` if never set. */
    suspend fun enabled(): Boolean =
        keyValue.selectByKey(ENABLED_KEY)?.data_?.firstOrNull() == 1.toByte()

    /** Persist the enabled flag, replacing any previous value. */
    suspend fun setEnabled(enabled: Boolean) {
        keyValue.upsertValue(ENABLED_KEY, byteArrayOf(if (enabled) 1.toByte() else 0.toByte()))
    }

    private companion object {
        // Single fixed key = first 16 bytes of sha256("<namespace>"), as a Uuid — same collision-safe
        // namespacing as BackupFolderSelectionStore, but keyless (one value for the whole flag).
        const val KEY_NAMESPACE = "homebase-photos.backup-enabled"
        const val ID_BYTES = 16
        val ENABLED_KEY: Uuid =
            Uuid.fromByteArray(KEY_NAMESPACE.encodeUtf8().sha256().substring(0, ID_BYTES).toByteArray())
    }
}
