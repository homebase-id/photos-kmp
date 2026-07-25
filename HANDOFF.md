# Homebase Photos — Handoff

**For:** a fresh Claude Code session opened in `~/Documents/GitHub/homebase-photos`.
**Updated:** 2026-07-25 ~17:25 (keep this file current — owner directive: refresh it at the end of every finishing run).
**Status:** MVP + Round 1 + Android background backup committed (`f57a3da` on `photos-mvp`). **iOS background backup implemented & sim-verified end-to-end, UNCOMMITTED** in the working tree (owner commits after review).

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
