# Homebase Photos — Handoff

**For:** a fresh Claude Code session opened in `~/Documents/GitHub/homebase-photos`.
**Updated:** 2026-07-26 (keep this file current — owner directive: refresh it at the end of every finishing run).
**Status:** MVP + Round 1 + background-backup (Android `f57a3da` + iOS `32ebd7e`) committed on `photos-mvp`. **UI/UX redesign underway** — a master plan drives per-batch, per-platform rebuilds. Batches **A** (`photos-ui-batch-a`), **B**, **C** (`photos-ui-batch-c` `a6d8cf2..d2cb8fd`) done; **Batch D (Favorites/Archive/Trash) DONE, review-clean, committed `6b4435f..c5dc8e4` on `photos-ui-batch-d`**, worktree `.claude/worktrees/photos-ui-batch-a`. On-device QA for B/C/D = the ship gate (owner login needed).

---

## UI/UX redesign (2026-07-25 →)

**Per-batch plans (one session each):** `docs/superpowers/plans/2026-07-25-ui-redesign-index.md` + a detailed file per
batch B–G. Open the index, then the batch file, and run its pipeline.

Owner-approved decisions: **GP-core scope, no ML** · **Google Photos 2026 nav IA** (Photos·Collections·Create + Search)
· **fully platform-divergent chrome** (Android = Material 3 Expressive + Material You; iOS = iOS-26 HIG — deploy
target is now **26.0** (bumped on `photos-mvp` `32ebd7e`, merged here), so Liquid-Glass APIs are fair game). Master plan sequences 7 batches: **A** Foundation → **B** Viewer
(actions/zoom/video) → **C** Collections & album management → **D** Favorites/Archive/Trash (schema-gated) → **E** Search
(metadata) → **F** Memories → **G** Settings & backup. Each batch = shared-headless agent (if needed) → Android + iOS
agents in parallel → one verifier build pass. Shared `StateFlow<UiState>` stays the source of truth; new on-drive formats
need owner schema sign-off.

**Batch D — Favorites · Archive · Trash (done, review-clean on `photos-ui-batch-d` `6b4435f..c5dc8e4`; 2026-07-26; on-device QA outstanding):**
- **SCHEMA (mirrors the official photo-app exactly — no new on-drive shape minted):** favorite = tag
  `Md5.toGuidId("favorite")` = hex `8a6b6ea3aa08285be1d4e00725aa9090` in the PHOTO's `appData.tags` (test-pinned,
  byte-identical to js-lib `toGuidId` = bare-hex md5); archive/trash/restore = header-only patch `archivalStatus`
  1/2/0 (same carry-everything machinery as album membership: aesKey kept, IV rotated, versionTag + all appData +
  allowDistribution, retry ×3); permanent delete = existing hard delete. Official query shapes: timeline =
  archivalStatus `[0]` ONLY (SQL-level exclusion in `selectPhotosPage`, regression-tested on in-memory SQLDelight incl.
  restore-reincludes); favorites = `tagsMatchAll [favoriteTag]` + `[0,1,3]` (server queryBatch — tags aren't indexed
  locally); archive/trash pages read the LOCAL index (`archivalStatus = 1/2`). Evidence: plan doc
  `2026-07-26-ui-batch-d-plan.md` + photo-app `PhotoProvider.ts`/`usePhoto.ts`.
- **Shared:** `PhotoConfig.FAVORITE_TAG`; `carryOverAppData` gained optional `archivalStatus` override;
  `PhotoStatusWriter` (setFavorite idempotent, setArchivalStatus batch → `PhotoStatusResult`); repository
  setFavorite/setArchived/softDelete/restore/permanentDelete + loadFavoritesPage(cursor)/loadArchivedPage/loadTrashPage;
  `PhotoItem.isFavorite`; `setFavoriteBatch` chunked(8). VMs: `FavoritesViewModel`/`ArchiveViewModel`/`TrashViewModel`
  (month sections reuse `groupIntoMonthSections`, Timeline-parity selection, mutations keep pagination depth +
  fire-and-forget `repository.sync()` reconcile — Archive/Trash `refreshAndWait` sync-then-read);
  `TimelineViewModel.favoriteSelected/archiveSelected`; `ViewerViewModel.isFavorite + toggleFavoriteCurrent`
  (optimistic + revert). `:shared:jvmTest` 1171 green.
- **Android:** `Route.Favorites/Archive/Trash` + one `LibraryStateScreen` scaffold; Collections library rows enabled;
  viewer heart toggle FIRST in action bar; timeline selection favorite/archive + heart badge on cells; VM events →
  snackbars; trash confirm dialog. **iOS:** Router cases + one `LibraryStateView` (xcodegen project — pbxproj is
  generated); same features/ids; `.hbPhotosChanged` posted after mutations; toasts incl. new Favorited/Archived.
- **Ids (both platforms):** `favorites-grid`, `archive-grid`, `trash-grid`, `trash-restore`, `trash-delete-forever`,
  `favorite-toggle`, `selection-favorite`, `selection-archive`, `favorites-unfavorite`, `archive-unarchive`,
  `delete-confirm`, `trash-header-note`. Trash note copy (both): "Items stay in the bin until you delete them
  permanently."
- **Verified:** full matrix green (jvmTest 1171/0, shared android+iosSim compiles, assembleDebug + androidTest compile,
  iOS build-for-testing iPhone 17/iOS 26.5). K/N link-cache flake fix: `:shared:linkDebugFrameworkIosSimulatorArm64
  --rerun-tasks`.
- **OWNER DECISION NEEDED:** nothing routes into the bin yet — "Delete" in Timeline/Viewer is still a hard delete;
  `softDelete` exists in the repo but no UI calls it. Google-Photos convention would be Delete → bin, "Delete forever"
  only in Trash. One-line-ish change per platform once decided.
- **Parked (ruled):** iOS library mutations post `.hbPhotosChanged` → always-alive Timeline refreshes to page 1
  (pre-existing observer behavior; content correctness beats scroll position; future fix = self-filtering observer).
- **Deferred minors:** viewer `toggleFavoriteCurrent` lacks in-flight guard (double-tap converges via version-retry);
  3 library VMs share a ~150-line skeleton per platform (matches per-screen convention); `timeline-favorite-badge` tag
  misnomer (renders in library grids too); "Couldn't load Trash" copy; viewer capsule now 5 buttons in 360pt (eyeball
  on-device); viewer from Archive/Trash still offers hard Delete/Add-to-album; Android trash note always visible vs iOS
  hides in selection.
- **On-device QA gate (owner login):** favorite round-trip visible in the official web app (tag interop); archive →
  leaves Timeline (and loadMore doesn't resurrect it) → shows in Archive after the background sync lands; trash →
  restore → back in Timeline; trash → delete forever; cross-device flag sync.

**Batch C — Collections & album management (done, review-clean on `photos-ui-batch-c` `a6d8cf2..7da26ff`; 2026-07-25 night; on-device QA outstanding):**
- **SCHEMA (owner-signed, supersedes spec §4's album detail):** the official Odin Photos app source at
  `~/Documents/GitHub/photo-app` (+ js-lib in `~/Documents/GitHub/dotyoucore-js`) is the album-schema source of truth.
  **Album = fileType 400** — NOT 900 (900 = the PhotoLibraryMetadata file; our shipped read path queried 900 and could
  never see real albums — fixed this batch). Album identity = bare-hex (no dashes) UUID in content JSON `tag` +
  `appData.uniqueId`; `appData.tags` is EMPTY on album files. Content `{name, description?, tag, coverFileId?}` —
  `coverFileId` is OUR owner-approved extension (official app has no stored cover; fallback = newest member photo).
  Membership = album tag in the PHOTO file's `appData.tags`, written per-photo via header-only `updateFileByFileId`
  (fresh header, keep aesKey, rotate IV, carry versionTag + EVERY appData field + `allowDistribution`, re-encrypt
  content; VersionTagMismatch → re-fetch retry ×3). Delete = softDeleteFile of the album file only (dangling member
  tags are official behavior). Full evidence + shapes: `docs/superpowers/plans/2026-07-25-ui-batch-c-album-schema.md`.
- **Shared:** `PhotoConfig.ALBUM_FILE_TYPE=400` + `LIBRARY_METADATA_FILE_TYPE=900`; `AlbumMapper` rewritten (identity
  from content.tag, lenient, unknown-field-preserving `patchAlbumContent` over raw JsonObject); mutations
  `createAlbum/renameAlbum/setCover/deleteAlbum/addPhotos/removePhotos` via `AlbumWriter` over an `AlbumDriveGateway`
  seam (+ `HeaderPatchRetry`); `AlbumsViewModel` (create/rename/delete/setCover/addToAlbum/createAlbumWithPhotos, each
  fire-and-forget + `AndWait`, events sealed `AlbumsEvent` incl. `Busy`; silent `reconcile()` that unions
  not-yet-indexed optimistic albums and preserves covers); `AlbumDetailViewModel` selection = Timeline parity +
  `removeSelected`. 59 album tests; `:shared:jvmTest` 1100 green.
- **Android:** Collections hub = library rows (Favorites/Archive/Trash/Utilities, disabled "Soon" until D) + album grid;
  album detail long-press selection (`SelectionTopBar` gained `onAction`/`extraActions` — Timeline unaffected), overflow
  Rename/Delete/Set-as-cover (single-selection-gated), remove-from-album; Create sheet → real create dialog → opens
  album; add-to-album picker from Timeline + Viewer selection (components: `LibraryRow`, `NameInputDialog`,
  `AlbumMenu`, `AlbumPickerSheet`). `NewAlbumScreen`/`Route.Create` placeholder deleted.
- **iOS:** same features SwiftUI-native (`CollectionsView` + `LibrarySection`, `AlbumDetailView` selection +
  menu, `AlbumNameSheet`, `AddToAlbumSheet`; viewer got a 4th action `viewer-addto`); `CollectionsModel`→`AlbumsModel`
  rename; `.hbAlbumsChanged` notification keeps the hub fresh across screens (mirrors `.hbPhotosChanged`);
  `album-setcover` lives on the selection bar (nav bar hidden during selection — deliberate deviation). New
  `CollectionsHubUITest` + `AlbumLifecycleUITest` (real-drive, self-cleaning, XCTSkip-gated on login).
- **New ids (both platforms):** `collections-library-row-{favorites,archive,trash,utilities}`, `album-menu`,
  `album-rename`, `album-delete`, `album-setcover`, `album-remove`, `create-album-dialog`, `addto-album-sheet`.
- **Verified:** full matrix green (shared android+iosSim compile, `:shared:jvmTest` 1100/0, `:androidApp:assembleDebug`
  + androidTest compile, iOS `build-for-testing` incl. UITest runner). Gotcha for future verifiers: suspend funs
  returning Kotlin primitives bridge to Swift as boxed `KotlinBoolean` (`.boolValue`); the real Android task name is
  `:shared:compileAndroidMain`.
- **Outstanding — on-device QA is the ship gate** (Redmi + iOS sim, owner login needed): create → visible in official
  web app (checks our dashed `uniqueId` + bare-hex tag interop); add-from-timeline / remove / rename / delete round-trip;
  old-900 "albums" (if any were minted pre-C) are now invisible by design; local DriveMainIndex picks up fileType-400
  rows; add-to-album of a large selection (N sequential PATCHes, no progress UI yet). Deferred minors live in the batch
  ledger (dual SnackbarHost overlap, membership-only writes skip `.hbAlbumsChanged`, no retry backoff, write-only
  AlbumsViewModel cost in Create tab — Batch D cleanup candidates).

**Batch B — Viewer (done, committed `a6d8cf2` on `photos-ui-batch-b`, merged into `photos-ui-batch-c`; 2026-07-25 evening):**
- **Contract:** `docs/superpowers/plans/2026-07-25-ui-batch-b-contracts.md` (frozen before the parallel writers ran).
- **Shared:** `viewer/ViewerViewModel` (`ViewerUiState` items/index/isDeleting/`deletedAny`, events `Error`/`Closed`,
  `setIndex` clamped, `deleteCurrent[AndWait]` mirroring Timeline's delete) + `viewer/VideoHandle`. `PhotosRepository`
  gained `loadOriginalBytes` (via `HomebaseImageLoader.loadFullPayload`, 48 MiB memo cache), `prepareVideo`
  (decrypt-to-temp via `streamPayloadDecryptedToPath`; segmented/HLS → null, deferred), `disposeVideo`.
  `PhotosRepositoryImpl` ctor gained `fileOps` (Koin updated). Factories: `viewerViewModel(items, initialIndex)` +
  top-level `loadOriginalBytes`/`prepareVideo`/`disposeVideo`; iOS `loadOriginalData` NSData bridge. 15 new shared
  tests green (`ViewerViewModelTest` ×10, `PrepareVideoTest` ×5).
- **Android:** VM-driven `ViewerScreen` + bottom action bar **Share · Delete · Info** (favorite/add-to-album deliberately
  absent until D/C); `ViewerInfoSheet` (ModalBottomSheet); zoom via **telephoto** `ZoomableAsyncImage`
  (`me.saket.telephoto:zoomable-image-coil3 0.16.0` — owner directive: use the established package; it also owns
  pan-vs-page arbitration, hand-rolled `components/Zoomable.kt` deleted); `VideoPlayerPage` (media3 ExoPlayer over
  `prepareVideo` temp file); share via `cacheDir/share/` + FileProvider; `DeleteConfirmDialog` extracted to
  `ui/components/` and reused; `ViewerBridge.onClosed(deletedAny)` → host grid refresh. New ids: `viewer-actionbar`,
  `viewer-share`, `viewer-delete`, `viewer-info`, `viewer-info-sheet`, `viewer-video-surface`. Rewritten `ViewerScreenTest`.
- **iOS:** `viewer/ViewerModel.swift` over the shared VM (SKIE pattern); action bar (`.glassEffect` capsule) with the
  same three actions; `ViewerInfoSheet`; zoom via reusable `components/Zoomable.swift` (Magnify + pan + double-tap;
  gates paging & swipe-dismiss while zoomed); `ViewerVideoPage` (AVKit `VideoPlayer` over `prepareVideo`); share via
  `components/ShareSheet.swift` (originals, thumb fallback); `hbPhotosChanged` notification → Timeline/AlbumDetail
  refresh; `components/ToastCapsule.swift` extracted. Ids: `viewer-actionbar/-share/-delete/-info/-info-sheet/-video`.
  Chrome intentionally does NOT auto-hide on video pages (AVKit controls own taps). `ViewerUITest` updated (delete
  test cancels the alert — real library).
- **Logout fix (shared, found during QA):** `YouAuthFlowManager.logout()` now hard-caps the backend-notify POST at 5s
  (`withTimeoutOrNull`) — previously an unreachable identity host blocked local logout for minutes (Ktor socket
  timeout). Local teardown always proceeds.
- **Verified:** `:shared` JVM + iosSimulatorArm64 compile, `:shared:jvmTest` all green, `:androidApp:assembleDebug` +
  androidTest compile, `xcodegen` + `xcodebuild` green (iPhone 17 / iOS 26.5) — zero verifier fixes needed. **Redmi live
  QA (offline):** action bar ✓, info sheet ✓ (date/dims/type/modified), telephoto pinch/pan/double-tap ✓, back-dismiss ✓.
- **BLOCKED on the identity-host outage (`*.demo.rocks` down during QA):** hi-res thumbnails, video playback, share
  originals, delete round-trip, and ALL iOS on-device QA (sim session got logged out; login needs the server — see
  Blockers). Argent QA gaps to close when the server returns: video plays, share sheet, delete on a sim-stock upload,
  iOS full viewer pass, Android video-page bottom-band overlap eyeball.

**Batch A — Foundation (done, COMMITTED `fa16d47`..`fea44ce`):**
- **Android:** real `NavHost` router (`ui/nav/Routes.kt`, `ui/home/AppShell.kt`) replacing hoisted-state nav; deleted
  `HomeScreen.kt`/`HomeBottomBar.kt`. GP-2026 **floating pill** (`ui/components/FloatingNavBar.kt`) = Photos·Collections·
  Create + round Search. Branded splash + Android-12 `installSplashScreen()`; adaptive launcher icon (white leaf on moss).
  Placeholder `ui/create/CreateScreen.kt` + `ui/search/SearchScreen.kt`. Deps added: `navigation-compose 2.9.6`,
  `core-splashscreen 1.0.1`. New `AppShellNavTest`.
- **iOS:** shared `navigation/Router.swift` (`NavigationPath` + `Route`); shell-hosted viewer `fullScreenCover`; 4-tab
  native `TabView` (Photos·Collections·Create·Search) in `home/HomeTabView.swift`; `SplashView`; `create/CreateView` +
  `search/SearchView` placeholders; new `Assets.xcassets` + generated leaf `AppIcon` (**final icon art still a pending
  design deliverable**). `project.yml` gained `ASSETCATALOG_COMPILER_APPICON_NAME`. New `ShellNavUITest`.
- **Verified:** `:shared` compiles (untouched); `:androidApp:assembleDebug` green; Android unit + androidTest compile
  (no emulator run); iOS `xcodegen` + `xcodebuild build`/`build-for-testing` green on iPhone 17 / iOS 26.5. iOS shell
  smoke-tested live via the `-uiTestTimeline` bypass (4 tabs render, Search placeholder navigates). Fixes made by
  verifier: added `ExperimentalMaterial3Api` import in SearchScreen.kt; copied gitignored `local.properties` into worktree.
- **a11y ids:** all existing preserved; added `tab-create`, `tab-search`/`search-button`, `splash-root`, plus
  create/search screen ids.
- **Backup card removed from the timeline** (owner: "not needed anymore" — it also overlapped the new pill). Deleted
  Android `ui/backup/BackupStatusCard.kt` + `BackupCardTest.kt` and all `backupCard` wiring in MainActivity/AppShell/
  TimelineScreen. **Backup engine kept intact** (BackupManager/BackupScheduler/BackgroundBackup/MediaWatch + shared
  `BackupViewModel`); background backup still runs off the persisted enabled-flag + folder selection. Backup UI (toggle +
  folder picker) will resurface as the proper **Settings→Backup screen in Batch G**. Verified live on the Redmi.
- **Known-follow-ups:** Android on-device QA otherwise login-gated (owner login needed); final iOS app-icon art pending;
  no in-app backup control until Batch G.

---

## What this is

A native (SwiftUI + Jetpack Compose) Google-Photos-class app on the Homebase/Odin protocol over a
copied headless `homebase-api` KMP layer. Full design: `docs/superpowers/specs/2026-06-21-homebase-photos-design.md`.
Round 1 plan (what the current working tree implements): `docs/superpowers/plans/2026-07-05-google-photos-round1.md`.

## Current state (2026-07-05 early morning)

**Committed (b256602):** YouAuth login, DriveSync timeline (paginated, month/day headers), encrypted
Coil/ThumbnailLoader pipeline, fullscreen viewer, folder-selective backup (MediaStoreCrawler → dedup
ledger → PhotoFileBuilder per-payload-IV → outbox), 6h WorkManager periodic, both native UIs.
Device-verified 2026-07-04 on iPhone 17 sim + Redmi Note 5 Pro.

**In the working tree (Round 1, verified but not committed — owner commits after review):**
- **Shared (TDD, commonTest):** timeline multi-select (`selectedIds`/`inSelectionMode`/`isSelected`),
  batch delete (`PhotosRepository.deletePhotos` → `DriveFileProvider.deleteFiles`; not-found counts
  as deleted), albums read path (`AlbumItem`/`AlbumMapper`/`AlbumsRepository`: local index
  fileType 900 + server `queryBatch` tag filter for members), `AlbumsViewModel` (+cover resolution),
  `AlbumDetailViewModel`, iOS factories `albumsViewModel()`/`albumDetailViewModel(album)`.
  Refresh resilience: `CancellationException` always rethrown, failed reload never wipes content
  (`TimelineRefreshResilienceTest`).
- **Android:** `ui/components/` package (PhotoGridCell/SectionHeaders/GridStates/TopBars/
  HomeBottomBar/AlbumCard), Material You dynamic color (SDK 31+, earthy fallback), selection mode +
  delete confirm, HomeScreen bottom nav (Photos/Collections), CollectionsScreen, AlbumDetailScreen,
  4 new compose androidTest files.
- **iOS:** `components/` group (PhotoCell/SectionHeaders/GridStates/SelectionTopBar/AlbumCard),
  HomeTabView, CollectionsView + AlbumDetailView (+models), selection mode + delete **alert**,
  2 new XCUITest files. Project is **XcodeGen-managed** — run `xcodegen generate` in `iosApp/`
  after adding files.
- **Android event-driven backup:** `MediaWatchScheduler`/`MediaWatchJobService` (JobScheduler
  id 4243, `addTriggerContentUri` images+video, 5s/30s delays, self re-arm), `BootCompletedReceiver`,
  enabled-flag gate in `BackupWorker`, app-start re-arm in `PhotosApp`. 6h periodic kept as net.

**Verification (2026-07-05):** `:shared:jvmTest` 1015 green · `:androidApp:assembleDebug` +
46/46 `connectedDebugAndroidTest` on the Redmi · iOS xcodebuild green · XCUITests 5 pass /
4 designed skips. iOS device QA: tabs, collections, selection, delete-alert, refresh — all pass.

**Background-backup work (2026-07-07, working tree, Redmi-verified — UNCOMMITTED):**
- **Real background upload.** `BackupWorker` now delegates to a shared `BackgroundBackup.run()`
  (commonMain, iOS-reusable): `restoreSession` → enqueue → `repository.sync()` (brings the outbox
  online) → `OutboxSync.awaitDrained()` (new; keeps the worker alive until uploads finish). Before
  this, the worker enqueued durably then returned, and nothing brought the outbox online in a
  worker-only process — rows shipped only on next app open.
- **Cold-start session restore (THE fix).** A WorkManager cold-started process has no login;
  `BackgroundBackup.run()` calls `youAuth.restoreSession()` first. Without it: `mountDrive … skipped
  — no active credentials`, upload silently skipped. See gotcha + memory
  `background-workers-need-restoresession`.
- **Video backup.** `MediaStoreCrawler` now enumerates images **and** video (merged buckets, `vid:`
  prefix on video IDs to avoid API-28 `_ID` collisions), + `readPosterFrame` via native
  `MediaMetadataRetriever` (no FFmpeg — poster only; HLS/transcode still deferred). `PhotoFileBuilder`
  splits `payloadBytes` (video, byte-for-byte, drives the dedup hash + MIME) from `thumbnailBytes`
  (poster). `BackupManager` branches video→poster with a 200 MB skip-guard (pre-read) until streaming
  upload lands. Photo path byte-identical (thumbnailBytes defaults to payloadBytes).
- **Verified on the Redmi (2026-07-07):** shared JVM/Android/iOS compile green, backup jvmTests 12/12.
  A 1.1 MB video uploaded+`completed` to the real drive (dedup id == `sha256(bytes)[:16]` hash-match).
  Cold-start proven: `am kill` the app, force job 4243 → fresh PID logged `Session restored` and a
  killed-app screenshot went `sending → completed` (hash-matched). iOS bindings exist
  (`backgroundBackup()` factory) but the iOS BGTask trigger + PHAsset crawler are NOT built yet.

**iOS background backup (2026-07-25, working tree, sim-verified — UNCOMMITTED):**
Brought iOS to Android parity. ~95% was already shared `commonMain` (`BackgroundBackup.run()`,
`BackupManager`, `PhotoFileBuilder`, outbox drain, `BackupViewModel`) — only the crawler + a Swift
trigger + config were new.
- **`PHAssetCrawler`** (`shared/src/nativeMain/.../backup/PHAssetCrawler.kt`) — real `PhotoLibraryCrawler`
  over the Photos framework (Kotlin/Native `platform.Photos`/`AVFoundation`), replacing the no-op
  `StubPhotoLibraryCrawler`. Mirrors `MediaStoreCrawler`: `folders()` groups `PHAssetCollection`,
  `assets()` fetches image+video newest-first, `readBytes()` streams originals via
  `PHAssetResourceManager`, `readPosterFrame()` via `AVAssetImageGenerator` (JPEG ~0.9). No `vid:`
  prefix — `deviceAssetId` = `PHAsset.localIdentifier` (globally unique). Bound in `PlatformModule.native.kt`.
  Pure `utiToMimeType` factored to commonMain + `AssetMimeTypeTest`.
- **Swift trigger** (`iosApp/iosApp/BackgroundBackupTrigger.swift`) — two paths, both calling the same
  shared `run()`, registered in `iOSApp.init()` (no AppDelegate):
  - `BGProcessingTask` (`id.homebase.photos.backup`) — opportunistic auto/periodic (the ONLY way iOS
    wakes a backgrounded app), 25s ceiling, re-arms each run. Best-effort.
  - `BGContinuedProcessingTask` (`id.homebase.photos.backup.now`, **iOS 26**) — user-initiated
    "Back up now". Submitted from the foreground; keeps running with a system progress bar (bridged
    from shared `BackupState` done/total) if the user leaves the app. `.queue` strategy, 9-min ceiling.
    Foreground-initiated only — does NOT wake a killed app. **Min deployment target bumped 18.2 → 26.0.**
- **Backup settings UI** (`iosApp/iosApp/backup/BackupView.swift` + `BackupModel.swift`) over the shared
  `BackupViewModel` (new `backupViewModel()` factory) — toggle + folder picker + progress; enable path
  requests `PHPhotoLibrary` readWrite auth first. Entry point: cloud button in `TimelineView` toolbar.
- **Config** (`iosApp/project.yml`) — static `Info.plist` now owns `NSPhotoLibraryUsageDescription`,
  `UIBackgroundModes: [processing]`, `BGTaskSchedulerPermittedIdentifiers`. `GENERATE_INFOPLIST_FILE` → NO.
- **Verified on iPhone 17 sim / iOS 26.5 (2026-07-25):** shared iOS/JVM/Android compile + link green,
  iOS app (iOS 26 target) + `:androidApp:assembleDebug` green, mime test green. Live E2E: enable →
  full-access prompt (shows the configured usage string) → `folders()` = "Recents, 9 items" → select →
  a full pass iterated all 9 real assets (incl. a 3840×2160 spatial video), pre-encrypted each to
  `outbox-staging`, opened a live upload to the Photos drive (`/api/v2/drives/6483b7b1-…/files`), no
  errors. `BGContinuedProcessingTask` submission verified via OS log (request built with title/subtitle/
  `.queue`); **its handler execution is device-only** — the simulator neither auto-launches nor
  `_simulateLaunch`es a continued task (that private API covers only BGProcessingTask/BGAppRefreshTask),
  but the handler runs the same already-proven `backgroundBackup().run()`. Confirm on a real iPhone (26+).

## Blockers / owner actions

0. **Identity host outage (2026-07-25 evening):** `peter.parker.demo.rocks` (and `frodo.baggins.demo.rocks`)
   unreachable — connect/socket timeouts from both the Mac and devices. Consequences: hi-res thumbnails/video/
   share/delete unverifiable; **the iPhone 17 sim is now LOGGED OUT** (owner logged out during the outage; login
   can't complete against a dead host — the Safari YouAuth page can't load). When the server is back: log the sim
   in (`peter.parker.demo.rocks`), then run the deferred Argent QA listed under Batch B. The Redmi is still
   logged in (do NOT log it out). Logout itself was hardened this session (5s notify cap in
   `YouAuthFlowManager.logout()`), so offline logout is instant now.
1. **Redmi login is HEALTHY again** (2026-07-07) — logged in as `peter.parker.demo.rocks`, survived
   several `installDebug` reinstalls this session and cold-restores fine. The earlier Keystore-wipe
   blocker is resolved. (Still true: `connectedDebugAndroidTest` reinstall can wipe the session — see
   gotcha — but a plain `installDebug` did not.)
2. MIUI battery optimization may throttle the JobScheduler trigger / BOOT_COMPLETED on the Redmi —
   whitelist the app in MIUI battery settings for reliable seconds-later backup. Cold-start upload
   was verified by forcing job 4243 (`cmd jobscheduler run -f id.homebase.photos 4243`); confirm the
   natural content-trigger fires unforced under MIUI before trusting seconds-latency in the field.
3. **Test uploads on the real drive (2026-07-07):** a `vidtest.mp4` (copy of a GoPro clip) + a few
   QA screenshots were backed up to the owner's Photos drive during verification. Delete from the
   app/web if unwanted.

## Next roadmap slices (deferred from Round 1 deliberately)

Album create/rename/add-photos (needs header-update/tag-write path) → share → favorites/archive/
trash → search + month scrubber (Batch 3) → **video playback** (backup now works; playback +
HLS/transcode still the riskiest, last) → large-video streaming upload (removes the 200 MB skip-guard).
**iOS background backup is DONE** (2026-07-25) — deferred within it: `PHPhotoLibraryChangeObserver`
while-alive trigger (skipped v1, iOS has no true background content-wake anyway); confirm the
`BGProcessingTask` actually fires in the field (sim-forced only so far).

## Gotchas (new ones first — older ones still apply)

- **iOS has NO true background content-wake** (2026-07-25). Android's JobScheduler content trigger
  wakes a killed app ~30s after a new photo; iOS has no equivalent. `PHPhotoLibraryChangeObserver`
  fires only while the app is alive, and `BGProcessingTask` runtime is opportunistic (OS-scheduled, not
  exact 6h). New-photo latency in the background is inherently best-effort on iOS — don't chase parity.
- **iOS folder list must reload AFTER the Photos grant** (2026-07-25). `PHAssetCrawler.folders()`
  returns empty until authorization, so `BackupModel.onToggle` calls `vm.loadFolders()` again right
  after `.authorized`/`.limited` — otherwise the picker shows "No device folders found" until the sheet
  is reopened. Keep that second `loadFolders()`.
- **iOS Photos permission = the switch, not the row label.** The backup `Toggle` only fires from the
  switch control; tapping the row label doesn't flip it (standard SwiftUI). Not a bug — relevant for AX/QA.
- **Cold-started background processes have NO login session** (2026-07-07). WorkManager cold-starts a
  process that never runs the UI startup path, so `YouAuthFlowManager.init{}`'s `restoreSession()`
  never fires → `DriveSync … no active credentials`, `OutboxSync: send() skipped — offline`, uploads
  silently skipped. Any headless entrypoint (BackupWorker, future iOS BGTask) MUST await
  `youAuth.restoreSession()` before sync/upload. To test a true cold start: `am kill <pkg>` (keeps
  jobs; `force-stop` cancels them) then `cmd jobscheduler run -f id.homebase.photos 4243`.
- **Enqueue ≠ upload** — the outbox only drains when `isOnline` AND `send()` were kicked, both done by
  `DriveSyncManager.start()`. A worker that only enqueues returns before anything ships; keep
  `BackgroundBackup.run()`'s `repository.sync()` + `OutboxSync.awaitDrained()`.
- **Kotlin block comments NEST** — a literal `/*` inside a KDoc (e.g. writing `` `video/*` ``) opens a
  nested comment and the `*/` closes THAT, leaving the doc comment unterminated → cascading
  "Unresolved reference" errors far below. Write `video/…` in comments.
- **Video backup = original bytes + native poster** — poster via `MediaMetadataRetriever`
  (Android) / `AVAssetImageGenerator` (iOS), NOT FFmpeg (FFmpeg is the viewer/decode + future HLS).
  Video `deviceAssetId` is `vid:`-prefixed; keep image ids bare (changing them orphans the ledger).
  Poster extraction only runs on-device (androidMain) — jvmTest covers the wiring with a fake.
- **M3 `surfaceTint` defaults to primary** — any tonal-elevated surface (NavigationBar, cards) gets
  accent-tinted. GPhotos-pure grounds need `surfaceTint = surface` in every scheme incl. dynamic `.copy`.
- **iOS `.refreshable` tasks die before the reload step** (synthetic and possibly real pulls) — the
  timeline now self-reconciles via `EventBus DriveEvent.Stopped → reloadNewestIfIdle()` in
  TimelineViewModel; don't remove it. Cross-device latency proven: new Android photo → visible on
  iOS in seconds (upload itself ~7s from MediaStore scan).
- **2026-07-05 later session:** full GPhotos visual parity landed (white/black surfaces, Google-blue
  fallback accents, GPhotos headers, portrait locked both platforms). Design contract lives in the
  gphotos-visual-parity workflow script + design-direction memory.

- **Running `connectedDebugAndroidTest` on the Redmi wipes the login session** (reinstall kills the
  Keystore key). Prefer an emulator for instrumented runs, or expect to re-login after.
- **iOS 26 `confirmationDialog` renders with no visible Cancel** — use `.alert` for destructive
  confirms (done for delete).
- **iOS 26 AX id shadowing:** `.accessibilityElement(children: .contain)` + `accessibilityIdentifier`
  on a single-AX-child view collapses onto the child and the outer id erases the inner one. Put
  container ids on a drawn layer (see timeline/collections root fixes).
- **Kotlin/Native stale incremental-link cache** can fail the Xcode "Build Shared.framework" phase
  (`No module deserializer for FUN ...`): run `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64`
  once to rebuild the cache.
- **Test event collection:** collect VM SharedFlows via test-scope `launch` + `advanceUntilIdle()`
  BEFORE acting, `collector.cancel()` at the end (no-replay flows drop unobserved emissions;
  `backgroundScope` collectors proved unreliable here).
- Known a11y debt (report 2026-07-05): iOS viewer `fullScreenCover` hides its own AX tree
  (VoiceOver can't reach viewer controls); tab-bar SF Symbol glyphs surface as phantom AXTextFields.
- Payload-key regex `^[a-z0-9_]{8,10}$`; byte-for-byte original upload; per-payload IV (never the
  file keyHeader IV); iOS FFmpegKit serial-queue + never `CODE_SIGNING_ALLOWED=NO`; Android webp
  encode `@RequiresApi(30)` risk; PHAssetResourceManager for iOS video; `stateIn` cache-seed;
  `-lsqlite3` in OTHER_LDFLAGS; drive GUIDs dashed via `Uuid.toString()`, never bare hex;
  appId always `32f0bdbf-017f-4fc0-8004-2d4631182d1e`; drift pin `chat-kmp e67130cd`.

## Reference paths

| What | Where |
|---|---|
| Copy source (protocol layer) | `~/Documents/GitHub/chat-kmp/homebase-api/` (pin `e67130cd`) |
| Round 1 plan + contracts | `docs/superpowers/plans/2026-07-05-google-photos-round1.md` |
| Odin Photos format source of truth | `~/Documents/GitHub/DotYouCore/`, `~/Documents/GitHub/homebase-web/` |
| Devices | iPhone 17 sim (iOS 26.5) · Redmi Note 5 Pro USB `6057f11e` (wireless adb flaky) |

## Workflow (owner directives, all still standing)

Strict TDD on shared logic + UI flow tests per screen · code-first batched verify (parallel writers,
ONE builder) · clean, easy-to-read code · Material You (Android) + iOS 26 HIG (iOS), Google-Photos
look approved · argent MCP for all device work · don't commit/push unless asked · refresh this file
at the end of every run.
