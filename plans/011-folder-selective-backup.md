# Plan 011: Folder-selective backup (owner amendment D6)

> **Executor instructions**: WRITER agents (no builds) + one FIX+VERIFY agent.
> Tree uncommitted — no commits. Spec of record:
> `docs/superpowers/specs/2026-07-04-backup-spec.md` incl. amendment **[D6]**.
> Depends on plan 010 (pipeline) + the delivery-fix wave (outbox online +
> durable staging) being merged into the tree first — verify both are present
> before editing (BackupManager exists; staging no longer under the swept cacheDir).

## Why

Owner (2026-07-04): "we provide backup options from the folders — which folders
has which photos that can be selected." Google-Photos "device folders" model.
Also the safety fix for the racy whole-roll backfill: **default = no folders
selected**, so enabling backup uploads nothing until folders are chosen.

## Locked contract changes (shared)

```kotlin
// backup/LibraryFolder.kt (new)
data class LibraryFolder(
    val folderId: String,     // MediaStore BUCKET_ID as string
    val name: String,         // BUCKET_DISPLAY_NAME ("Camera", "Screenshots", …)
    val photoCount: Int,
)

// PhotoLibraryCrawler gains folder awareness (BREAKING — update stub + Android impl):
interface PhotoLibraryCrawler {
    suspend fun folders(): List<LibraryFolder>                     // newest-activity-first or count-desc; pick one, document
    suspend fun assets(folderIds: Set<String>): List<LibraryAsset> // ONLY selected folders, newest-first; emptySet() → emptyList()
    suspend fun readBytes(asset: LibraryAsset): ByteArray?
}

// backup/BackupFolderSelectionStore.kt (new): persisted Set<String> of folderIds
// over the existing KeyValue table (namespaced sha256 key, same pattern as
// BackupLedger — NO new .sq table; DATABASE_VERSION bump wipes all tables).

// BackupState += val selectedFolderCount: Int (0 = nothing will upload)
// BackupManager: backupNow() reads the selection store; empty → completes
//   immediately with done=0/total=0 (and state reflects selectedFolderCount=0).
// BackupViewModel/UiState += folders: List<FolderUi> (folderId, name, photoCount,
//   selected), loadFolders(), onFolderToggled(folderId) — persists via the store.
```

MediaStore folder enumeration (Android impl): query BUCKET_ID +
BUCKET_DISPLAY_NAME (+ DATE_ADDED), aggregate counts IN MEMORY (portable across
API levels; the Redmi is API 28 — no GROUP BY guarantees). Images only.

## UI (Android; iOS stays stub)

BackupStatusCard gains a "Choose folders" affordance (visible when enabled OR
when toggling on with zero selected — in that case open the picker instead of
starting an empty backup). Folder picker = ModalBottomSheet: one row per folder
(checkbox, name, photo count), testTags `backup-folder-sheet`,
`backup-folder-row` (+ per-row tag suffix), `backup-folder-done`. Selection
persists immediately on toggle. Minimal, design-tokens only.

## Tests (strict TDD)

- Crawler contract: stub + fake return folders; `assets(emptySet())` == empty.
- SelectionStore: persist/read/clear round-trip (in-memory DB pattern).
- BackupManager: empty selection → no crawl of assets, state done/total 0;
  selected subset → only those folders' assets enqueue; dedup still applies.
- ViewModel: folder toggle persists + reflects in UiState.
- Compose flow test: sheet lists folders w/ counts; toggling row invokes
  callback; toggle-on-with-zero-selection opens sheet (not a backup run).

## Verifier block

1. `./gradlew :shared:jvmTest --tests "id.homebase.photos.*"` green.
2. Compile gates + `:androidApp:assembleDebug` + androidTest compile green.
3. iOS regression build green (crawler interface changed — stub must match).
4. Redmi UI smoke WITHOUT enabling any folder: install -r, open backup card,
   open folder sheet → real device folders + counts listed (screenshot).
   Do NOT select folders / do NOT enable backup — the owner performs the real
   upload verification themselves (login + pick folder), per plan.

## STOP conditions

- Plan 010 delivery fixes absent from the tree (staging still in swept cacheDir
  or setOnline still uncalled) → STOP, report — this plan depends on them.
- MediaStore bucket queries fundamentally unavailable on API 28 → report.

## Owner verification (after verifier green)

Ping owner: log in with their account (device of their choice), open backup,
choose a small folder, watch it upload, confirm photos appear in the timeline
(and on the second device after sync). This is the acceptance for plan 010+011.
