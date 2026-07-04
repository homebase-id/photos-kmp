# Plan 009: Real photos — repo swap, post-auth sync, decryptable Android grid

> **Executor instructions**: FIX+VERIFY agent (you may build and must verify).
> Follow steps in order; STOP conditions binding; no commits. The tree is
> uncommitted — verify the excerpts below against live code before editing.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED (first real network sync path; enum/API details verified by scout but re-check while coding)
- **Depends on**: 006–008 DONE (login works; owner has an authenticated identity for device testing)
- **Category**: feature (real photo library)
- **Planned at**: commit `86e57a2`, 2026-07-04

## Why this matters

Login works, but the timeline still renders `MockPhotosRepository`. Everything
needed for real photos exists and is verified by scout: `DriveMainIndex.driveId`
IS the drive alias (no resolution step), the identityId stub is consistent on
write+read (chat-kmp ships the same stub — fine for a single-identity app), and
the minimal sync sequence is `ensureMandatoryMounted() → start() → syncAll()`
(no WebSocket needed; `syncAll()` SUSPENDS until own drives finish — that's the
await). One real defect must be fixed en route: Android's Coil path builds
`HomebaseImageData` with `KeyHeader.empty()` (documented seam), so real
encrypted thumbnails can't decrypt in the grid until `PhotoItem` carries the
crypto/context fields.

## Verified facts (scout, 2026-07-04 — re-verify signatures as you touch them)

- `PhotosRepositoryImpl(driveId: Uuid, driveSyncManager, databaseManager,
  credentialsManager, imageLoader: HomebaseImageLoader)` — all bound in Koin
  EXCEPT `HomebaseImageLoader` (never bound; ctor =
  `(driveFileProvider: DriveFileProvider /* apiModule factoryOf */,
  fileOperationsProvider: FileOperationsProvider /* platformModule single */)`)
  and `driveId` (literal: `Uuid.parseHex(PhotoConfig.DRIVE_ALIAS)` — the SAME
  value bound as the `mandatoryDrives` key in photosModule).
- `DriveSyncManager`: `suspend ensureMandatoryMounted()` (mount = in-memory
  registration, idempotent, needs active credentials); `suspend start()`
  (no-ops without credentials, re-runs ensureMandatoryMounted, sets _isRunning);
  `suspend syncAll()` (skips if !_isRunning; SUSPENDS until own drives finish);
  `fun syncDrive(id)` (fire-and-forget — do NOT use for awaited refresh);
  `syncState: StateFlow<SyncState>` (Idle/Syncing/Completed/Failed);
  `driveStatuses: StateFlow<Map<Uuid, DriveStatus>>`.
- `selectPhotosPage` WHERE: `identityId = ? AND driveId = ? AND fileType = ?
  AND fileState = 1 AND userDate < ?` — identityId/driveId match what DriveSync
  writes as long as the repo passes the alias Uuid and the stub identityId
  (both already true once the binding is right).
- `PhotosRepositoryImpl.loadThumbnailBytes` already builds the FULL
  `HomebaseImageData` (keyHeader from the row, thumbSizes, isEncrypted,
  contentType, lastModified) — iOS is correct without changes.
- Android seam: `CoilSetup.homebaseImageData(fileId, driveId, payloadKey,
  requestedSize, keyHeader)` — callers pass `KeyHeader.empty()` from
  `TimelineScreen.PhotoCell`; fetch of a real encrypted file fails to decrypt.

## Scope

**In scope**:
- `shared/src/commonMain/kotlin/id/homebase/photos/PhotosModule.kt`
- `shared/src/commonMain/kotlin/id/homebase/photos/data/PhotosRepositoryImpl.kt`
- `shared/src/commonMain/kotlin/id/homebase/photos/data/PhotoMapper.kt`
- `shared/src/commonMain/kotlin/id/homebase/photos/data/MockPhotosRepository.kt`
- `shared/src/commonMain/kotlin/id/homebase/photos/domain/PhotoItem.kt`
- `shared/src/commonMain/kotlin/id/homebase/photos/timeline/TimelineViewModel.kt`
- `shared/src/commonTest/kotlin/id/homebase/photos/**` (tests as below)
- `shared/src/jvmTest/kotlin/id/homebase/photos/KoinAuthBootTest.kt` (extend)
- `androidApp/src/main/kotlin/id/homebase/photos/android/ui/CoilSetup.kt`
- `androidApp/src/main/kotlin/id/homebase/photos/android/ui/timeline/TimelineScreen.kt` (ONLY the homebaseImageData call site)
- `iosApp/iosAppUITests/TimelineGridUITest.swift` (assertions only)

**Out of scope**: `id.homebase.api.**` (except reading), Login/auth files,
theme files, `project.yml`, manifest.

## Steps

### Step 1: Enrich `PhotoItem` (Android decrypt seam)

Add to `PhotoItem` (defaults keep the mock + existing tests compiling):

```kotlin
val keyHeader: KeyHeader? = null,        // decryption key for Coil path (null for mock/unencrypted)
val isEncrypted: Boolean = false,
val payloadContentType: String? = null,  // also feeds isVideo upstream — keep both
val lastModified: Long? = null,          // cache-key freshness
val thumbSizes: List<ImageSize> = emptyList(),
```

(Import types exactly as `PhotosRepositoryImpl` does: `id.homebase.api.client.KeyHeader`,
`id.homebase.core.image.ImageSize`, `thumbSizesFrom`.) `PhotoMapper.fromHomebaseFile`
populates all five from the `HomebaseFile` (mirror `loadThumbnailBytes`'s
construction: `file.keyHeader`, `file.fileMetadata.isEncrypted`,
`payload?.contentType`, `payload?.lastModified`, `thumbSizesFrom(payload?.thumbnails)`).
`MockPhotosRepository` seeding: rely on defaults (explicitly pass nothing).

### Step 2: Android — full-fidelity `HomebaseImageData`

`CoilSetup.homebaseImageData(...)` gains the new params (or simplest: change its
signature to take the `PhotoItem` + `requestedSize` and build everything —
preferred, kills the seam permanently):

```kotlin
fun homebaseImageData(photo: PhotoItem, requestedSize: ImageSize): HomebaseImageData =
    HomebaseImageData(
        driveId = photo.driveId, fileId = photo.fileId, payloadKey = photo.payloadKey,
        requestedSize = requestedSize,
        availableThumbSizes = photo.thumbSizes,
        isEncrypted = photo.isEncrypted,
        payloadContentType = photo.payloadContentType,
        lastModified = photo.lastModified,
        keyHeader = photo.keyHeader ?: KeyHeader.empty(),
    )
```

(Check `HomebaseImageData`'s exact ctor param names/optionals before writing —
`previewThumbnail` stays null: the native cells already render the blur
placeholder themselves.) Update the ONE call site in `TimelineScreen.PhotoCell`
accordingly; delete the stale SEAM comment.

### Step 3: The swap + bindings (PhotosModule.kt)

```kotlin
single { HomebaseImageLoader(driveFileProvider = get(), fileOperationsProvider = get()) }
single<PhotosRepository> {
    PhotosRepositoryImpl(
        driveId = Uuid.parseHex(PhotoConfig.DRIVE_ALIAS), // == the mandatoryDrives mount key; DriveMainIndex.driveId IS the alias
        driveSyncManager = get(), databaseManager = get(),
        credentialsManager = get(), imageLoader = get(),
    )
}
```

Remove the mock binding + its TODO comment (keep the MockPhotosRepository CLASS
— commonTest uses it).

### Step 4: Awaited, self-sufficient sync (PhotosRepositoryImpl)

```kotlin
override suspend fun sync() {
    // Chat-canonical minimal sequence; no WebSocket needed for a REST crawl.
    driveSyncManager.ensureMandatoryMounted()
    driveSyncManager.start()      // idempotent; no-op without credentials
    driveSyncManager.syncAll()    // suspends until own drives reach a terminal state
}
```

Drop the `refreshObservable()` call + helper if now unused (deferred PERF-10 —
`observePhotos` keeps returning the (now never-refilled) flow; note it in the
kdoc). Do NOT use `syncDrive()` here (fire-and-forget → refresh would reload
before rows land).

### Step 5: First-launch auto-sync (TimelineViewModel)

In `loadFirstPage()`: after `applyReplace(page)`, `if (page.isEmpty()) refreshAndWait()`
— one line + comment: on the first authenticated launch the local index is
empty until the first sync; logged-out this is a cheap no-op cycle (start()
declines without credentials). No other VM changes.

### Step 6: Tests

- `PhotoMapper` test (extend the existing mapper test file, e.g. next to
  `VideoMarkerTest`): a `HomebaseFile` fixture maps keyHeader/isEncrypted/
  payloadContentType/lastModified/thumbSizes through — follow how existing
  mapper tests build fixtures; if no fixture builder exists for keyHeader,
  assert at minimum isEncrypted/contentType/lastModified passthrough and note it.
- `KoinAuthBootTest`: add `assertNotNull(get<PhotosRepository>() as? PhotosRepositoryImpl)`
  (in-memory DB precondition already in the test).
- `TimelineViewModel` empty-first-page test IF a fake repo pattern exists in
  commonTest (there is `MockPhotosRepository(seedCount = 0)`): first page empty
  → repository.sync() invoked once (subclass/wrap the mock to count sync calls).
- iOS `TimelineGridUITest.swift`: the `-uiTestTimeline` seam now runs against
  the REAL repo with no login in the test env → grid may be EMPTY. Relax
  assertions to: `timeline-root` exists AND (grid OR skeleton OR `timeline-empty`
  exists). Keep the launch argument.

### Step 7: VERIFY (sequential, run_in_background, wait each)

1. `./gradlew :shared:jvmTest --tests "id.homebase.photos.*"` → all pass
2. `./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileAndroidMain` → green
3. `./gradlew :androidApp:assembleDebug` → green
4. `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17' build` → green (K/N flake → link task `--rerun-tasks` once)
5. Reinstall + launch on the booted sim (argent MCP, udid
   `8D8741B1-4B02-4BCE-ACD3-E4F8265E1BB1`, bundleId `id.homebase.photos`) —
   fresh install = logged out → EXPECT the login screen (correct; the owner
   re-logs-in afterward). `describe` to confirm; screenshot for the record.

## Done criteria

- [ ] `get<PhotosRepository>()` is `PhotosRepositoryImpl` (boot test asserts it).
- [ ] `sync()` mounts+starts+awaits via `syncAll()`; no `syncDrive` on the refresh path.
- [ ] `PhotoItem` carries keyHeader/isEncrypted/contentType/lastModified/thumbSizes; mapper populates; Android Coil request uses them (no `KeyHeader.empty()` left in TimelineScreen).
- [ ] First-launch-empty triggers one awaited sync.
- [ ] All shared photos tests + builds green; sim shows login screen post-reinstall.

## STOP conditions

- `HomebaseImageData` ctor doesn't accept the fields listed (re-read it; adapt
  names only — if a required concept is missing, STOP).
- `syncAll()` turns out not to suspend-until-terminal on our copy (read it).
- Any need to touch `id.homebase.api.**` source.

## Maintenance notes

- `observePhotos` is now a dormant API (PERF-10 recorded); when a future wave
  wants live updates, wire it to EventBus BackendEvents or delete it from the contract.
- Backup wave (T14, schema gate) will reuse `syncState`/`driveStatuses` for its
  status card; the drive Write grant is already consented.
- `MockPhotosRepository` remains the fixture for tests and demo screenshots.
