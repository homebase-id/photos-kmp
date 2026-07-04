# Plan 010: Android auto-backup (approved spec → working upload loop)

> **Executor instructions**: two WRITER agents (no builds) + one FIX+VERIFY
> agent, per the repo's code-first/batched-verify rule. Tree is uncommitted —
> no commits. Spec of record (APPROVED, D1–D5 locked):
> `docs/superpowers/specs/2026-07-04-backup-spec.md` — read it first; this plan
> adds contracts and file placement, it does not restate the schema.

## Status

- **Priority**: P0 (owner: backup is the main remaining MVP code; demo Tuesday 2026-07-08)
- **Effort**: L
- **Depends on**: 001–009 DONE + per-payload-IV hotfix (2026-07-04)
- **Planned at**: commit `86e57a2`, 2026-07-04 evening

## Locked contracts (both writers code against these; do not renegotiate)

```kotlin
// shared/src/commonMain/kotlin/id/homebase/photos/backup/
data class LibraryAsset(
    val deviceAssetId: String,   // MediaStore _ID as string (stable per device)
    val fileName: String,
    val mimeType: String?,
    val takenAtMillis: Long?,    // MediaStore DATE_TAKEN — D3 fallback input
    val addedAtMillis: Long?,    // DATE_ADDED millis — last-resort D3 fallback
    val sizeBytes: Long?,
)

interface PhotoLibraryCrawler {            // bound in platformModule()
    suspend fun assets(): List<LibraryAsset>          // newest-first, images only
    suspend fun readBytes(asset: LibraryAsset): ByteArray?
}

class BackupLedger(/* KeyValue/db dep per scout facts */) {
    suspend fun backedUpFileId(deviceAssetId: String): Uuid?
    suspend fun record(deviceAssetId: String, fileId: Uuid)
}

class PhotoFileBuilder(/* no platform deps; pure + copied image utils */) {
    suspend fun build(asset: LibraryAsset, bytes: ByteArray): /* upload-descriptor
        bundle type per scout facts — descriptor + thumbnail files + tiny thumb */
}

data class BackupState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val currentName: String? = null,
    val lastError: String? = null,
    val lastCompletedAt: Long? = null,
)

class BackupManager(crawler, ledger, builder, /* upload seam */, scope) {
    val state: StateFlow<BackupState>
    fun setEnabled(enabled: Boolean)   // false mid-run stops after current item
    suspend fun backupNow()            // idempotent full pass: crawl → dedup → build → upload → record
}

class BackupViewModel(manager) // StateFlow<BackupUiState> mirror + onToggle()/onBackupNow(); flat UiState data class per repo MVVM convention
```

- Upload seam: enqueue through the COPIED outbox path exactly as chat-kmp does
  for a new media file (scout documents the concrete call chain:
  UploadInstructionSet/DescriptorBuilder → Outbox → DriveOutboxUploader).
  Per-payload IVs come from the copied envelope — never hand-encrypt
  (memory: per-payload-iv).
- D1 uniqueId: okio `ByteString.sha256()` first 16 bytes → Uuid.
- iOS/native actual of `PhotoLibraryCrawler` = stub returning emptyList()
  (keeps iOS compiling; iOS crawler is a later wave).

## Writer A — shared pipeline (strict TDD: tests first)

Files: `shared/src/commonMain/kotlin/id/homebase/photos/backup/*` (new pkg),
`platformModule` actuals (androidMain gets the real crawler binding seam,
nativeMain/jvm the stub), photosModule bindings,
`shared/src/commonTest|jvmTest/kotlin/id/homebase/photos/backup/*`:
BackupLedgerTest, PhotoFileBuilderTest (EXIF fixtures: full EXIF / no-EXIF /
no-GPS; D1 determinism; D2 offset-vs-device-TZ; D3 fallbacks),
BackupManagerTest (fake crawler/uploader/ledger: dedup skip, progress counts,
toggle-off-mid-run stops, error recorded not thrown), FormatGateTest (spec §2)
against the real-row fixture at `shared/src/jvmTest/resources/real-photo-row.json`
(scout provides it; test diffs descriptor shape field-by-field).

## Writer B — Android platform + UI

- `MediaStoreCrawler` (androidMain or androidApp per Koin context reach —
  scout says where Context is available; images only, `DATE_ADDED DESC`).
- WorkManager: add `androidx.work:work-runtime-ktx` to
  `gradle/libs.versions.toml` + androidApp (established dep — allowed).
  `BackupWorker` calls shared `backupNow()`. Toggle ON → expedited one-shot +
  periodic (6h, `ExistingPeriodicWorkPolicy.UPDATE`); toggle OFF → cancel both.
- Permission on enable: Android 13+ `READ_MEDIA_IMAGES`, else
  `READ_EXTERNAL_STORAGE`, via `ActivityResultContracts.RequestPermission`
  (androidx.activity already present). Denied → toggle reverts + snackbar.
- UI: REPLACE the dead BackupFab with a minimal BackupStatusCard on the
  timeline (toggle, `done/total` progress while running, relative
  last-backed-up time). Stateless/stateful split, testTags `backup-card`,
  `backup-toggle`, `backup-progress`. Compose flow test (BackupCardTest)
  following LoginScreenTest pattern.
- Design tokens only (PhotosTheme.extended / design-system.md §3); no new icons
  beyond what exists (task #10 swaps icon sources later).

## Verifier block (FIX+VERIFY, single pass, ≤2 fix rounds)

1. `./gradlew :shared:jvmTest --tests "id.homebase.photos.*"` → green (FormatGateTest included).
2. `./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileAndroidMain :androidApp:assembleDebug` → green.
3. iOS regression: `xcodebuild ... -scheme iosApp -sdk iphonesimulator build` still green (shared API changed).
4. Redmi e2e (USB `6057f11e`, login preserved — NEVER uninstall):
   `adb push` 2 known test JPEGs (with EXIF) to `/sdcard/DCIM/Camera/` + media-scan broadcast →
   install `-r` APK → grant media permission (`adb shell pm grant` ok) → enable backup toggle →
   the 2 test photos are newest so they upload FIRST → **toggle backup OFF once done≥2**
   (guard: do NOT let backfill continue into the owner's personal photos) →
   pull-to-refresh timeline → the 2 test photos render sharp from the drive. Screenshots.
5. Sim spot-check: iOS app still boots to sharp timeline (no backup UI expected there).

## STOP conditions

- The copied outbox cannot express "create new file with payload+thumbnails"
  (report the exact API gap — do not build a parallel uploader).
- FormatGateTest reveals an unexplained divergence from the real row → report,
  do not "fix" by weakening the test.
- Redmi upload visibly rejected by the server (4xx on descriptor) → capture the
  response body and STOP the device phase.

## Maintenance notes

- iOS crawler/BGTask = next wave; albums/video/Wi-Fi-toggle recorded follow-ups.
- Owner may delete the 2 test photos from the drive afterwards.
