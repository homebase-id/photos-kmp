# Homebase Photos — Handoff

**For:** a fresh Claude Code session opened in `~/Documents/GitHub/homebase-photos`.
**Updated:** 2026-07-05 ~04:00 (keep this file current — owner directive: refresh it at the end of every finishing run).
**Status:** MVP committed (`b256602` on `photos-mvp`) + **Google Photos Round 1 implemented, verified, UNCOMMITTED** in the working tree.

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

## Blockers / owner actions

1. **Redmi lost its login session** — the instrumented-test run reinstalled the app, killing the
   Android Keystore key; Auto-Backup restored ciphertext that can no longer decrypt →
   `hasStoredCredentials()`=true but silent fall-through to login. **Owner must re-login on the
   Redmi**, then run: Android visual QA, the event-backup latency test (push jpg into
   `/sdcard/DCIM/Camera` + `MEDIA_SCANNER_SCAN_FILE` → upload ≤ ~35s), and cross-device
   create→sync→delete.
2. MIUI battery optimization may throttle the JobScheduler trigger / BOOT_COMPLETED on the Redmi —
   whitelist the app in MIUI battery settings for reliable seconds-later backup.

## Next roadmap slices (deferred from Round 1 deliberately)

Album create/rename/add-photos (needs header-update/tag-write path) → share → favorites/archive/
trash → search + month scrubber (Batch 3) → video playback (riskiest, last) → iOS event-driven
backup (PHPhotoLibraryChangeObserver + BGProcessingTask).

## Gotchas (new ones first — older ones still apply)

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
