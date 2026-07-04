package id.homebase.photos.backup

import id.homebase.api.sync.database.KeyValueWrapper
import okio.ByteString.Companion.encodeUtf8
import kotlin.uuid.Uuid

/**
 * Cross-run dedup ledger: `deviceAssetId -> backed-up id`. Reuses the existing `KeyValue` table
 * (no new `.sq` → no `DATABASE_VERSION` bump → no table-wipe on upgrade). Each entry is stored
 * under a deterministic, collision-safe namespaced key so the ledger can't clash with other
 * `KeyValue` consumers (cursors, preferences).
 *
 * The stored id is whatever the caller records at build time — the D1 deterministic `uniqueId`
 * (a content hash) — because the server-assigned `fileId` isn't known until the async outbox
 * drain, long after enqueue. That's sufficient for dedup: the same asset always hashes the same,
 * so a second pass finds it already recorded and skips it.
 */
class BackupLedger(
    private val keyValue: KeyValueWrapper,
) {
    /** The recorded id for [deviceAssetId], or null if this asset was never backed up. */
    suspend fun backedUpFileId(deviceAssetId: String): Uuid? {
        val data = keyValue.selectByKey(ledgerKey(deviceAssetId))?.data_ ?: return null
        // Value is the 16 raw id bytes; guard against a longer/legacy record by taking the first 16.
        if (data.size < ID_BYTES) return null
        return Uuid.fromByteArray(if (data.size == ID_BYTES) data else data.copyOf(ID_BYTES))
    }

    /** Record [fileId] as the backed-up id for [deviceAssetId]. Idempotent (upsert). */
    suspend fun record(deviceAssetId: String, fileId: Uuid) {
        keyValue.upsertValue(ledgerKey(deviceAssetId), fileId.toByteArray())
    }

    /**
     * Deterministic per-asset key: first 16 bytes of `sha256("<namespace>:<assetId>")`, as a Uuid.
     * The namespace prefix keeps backup rows from ever colliding with another KeyValue consumer.
     */
    private fun ledgerKey(deviceAssetId: String): Uuid =
        Uuid.fromByteArray(
            (KEY_NAMESPACE + deviceAssetId).encodeUtf8().sha256().substring(0, ID_BYTES).toByteArray()
        )

    private companion object {
        const val KEY_NAMESPACE = "homebase-photos.backup-ledger:"
        const val ID_BYTES = 16
    }
}
