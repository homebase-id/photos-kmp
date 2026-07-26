# Batch D implementation plan — Favorites · Archive · Trash

Branch `photos-ui-batch-d` (off `photos-ui-batch-c` `d2cb8fd`). Spec: `2026-07-25-ui-batch-d-library-states.md`.
Delegation: shared (2 sequential tasks, TDD) → Android + iOS in parallel (code-only, no builds) → one verifier.

## Schema (owner-signed via Batch C doc; source = official photo-app)

Evidence: `photo-app/packages/common/src/provider/photos/PhotoTypes.ts`, `PhotoProvider.ts`,
`hooks/photoLibrary/photo/usePhoto.ts`; js-lib `DataUtil.ts:114` (`toGuidId = md5(input).toString()` — bare hex).

- **Favorite** = tag `toGuidId('favorite')` = md5("favorite") = hex `8a6b6ea3aa08285be1d4e00725aa9090` in the PHOTO
  file's `appData.tags`. Toggle = header-only patch of tags (same machinery as album membership).
- **Archive** = header patch `archivalStatus = 1`. **Trash (bin)** = `archivalStatus = 2`. **Restore** = `archivalStatus = 0`.
  **Permanent delete** = hard `deleteFile` (our existing `deletePhotos`).
- **Official queries** (`getArchivalStatusFromType`): photos timeline → archivalStatus `[0]` ONLY (archived + binned
  hidden); archive → `[1]`; bin → `[2]`; favorites → `tagsMatchAll [favoriteTag]` + archivalStatus `[0,1,3]`.
- Header patch carries EVERYTHING else (aesKey kept, IV rotated, versionTag, all appData fields, allowDistribution) —
  exactly `AlbumWriteSchema.carryOverAppData` + `headerUpdateRequest` + `HeaderPatchRetry` (×3 on VersionTagMismatch).
- No auto-purge implemented client-side by official app; trash header copy is a neutral note, no fake "N days".

## Global constraints

- Shared module is headless (`StateFlow<UiState>`); UI native SwiftUI + Compose; DRY: reuse existing components
  (`PhotoGridCell`, `SelectionTopBar`, `LibraryRow`, iOS `LibraryRow`/grid, `groupIntoMonthSections`).
- Strict TDD in shared (test first). No builds by writer agents — verifier task builds everything.
- Favorite tag GUID must equal hex `8a6b6ea3aa08285be1d4e00725aa9090` (pin with a test).
- Timeline regression: archived + trashed photos must disappear from the main timeline (test this).
- a11y ids (both platforms, verbatim): `favorites-grid`, `archive-grid`, `trash-grid`, `trash-restore`,
  `trash-delete-forever`, `favorite-toggle`. Keep every existing id intact.
- Minimal comments; match surrounding style. Commit per task on `photos-ui-batch-d`, message suffix
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

## Task 1 — shared: status write layer + repository (TDD)

Files (all under `shared/src/commonMain/kotlin/id/homebase/photos/` unless noted; tests mirror in `commonTest`):

1. `PhotoConfig`: add `val FAVORITE_TAG: Uuid = Md5.toGuidId("favorite")`. Test pins hex
   `8a6b6ea3aa08285be1d4e00725aa9090`.
2. `data/AlbumWriteSchema.kt` `carryOverAppData`: add param `archivalStatus: ArchivalStatus? = existing.archivalStatus`
   and project it. Existing callers unchanged. Test: override sets new value, everything else carried.
3. New `data/PhotoStatusWriter.kt` (compose exactly like `AlbumWriter`: `AlbumDriveGateway` seam +
   `patchHeaderWithRetry`):
   - `suspend fun setFavorite(fileId: Uuid, favorite: Boolean): Boolean` — fresh header → tags ± `FAVORITE_TAG`
     (`withTag`/`withoutTag`) → header patch. Idempotent (already-in-state = success, no write).
   - `suspend fun setArchivalStatus(fileIds: List<Uuid>, status: ArchivalStatus): PhotoStatusResult` — per-file fresh
     header → carryOver with new status → patch; `PhotoStatusResult(succeeded: List<Uuid>, failed: List<Uuid>)`
     (mirror `AlbumMembershipResult`). Skip files already at `status` (count as succeeded).
   Tests via fake gateway (copy `AlbumWriterTest` style): tag add/remove round-trip, status patch, version-conflict
   retry, partial failure split, idempotent skip.
4. `data/PhotosRepository.kt` + `PhotosRepositoryImpl` + `MockPhotosRepository`:
   - `suspend fun setFavorite(fileId: Uuid, favorite: Boolean): Boolean`
   - `suspend fun setArchived(fileIds: List<Uuid>, archived: Boolean): PhotoStatusResult` (true→Archived, false→None)
   - `suspend fun softDelete(fileIds: List<Uuid>): PhotoStatusResult` (→Removed)
   - `suspend fun restore(fileIds: List<Uuid>): PhotoStatusResult` (→None)
   - `suspend fun permanentDelete(fileIds: List<Uuid>): Boolean` — delegate to existing `deletePhotos` path.
   - `suspend fun loadFavoritesPage(cursor: String?, limit: Int): FavoritesPage` — server `queryBatch` via new
     `PhotoQueries.favoritesQuery()`: `fileType=[PHOTO_FILE_TYPE]`, `tagsMatchAll=[FAVORITE_TAG]`,
     `archivalStatus=[0,1,3]`, sorted userDate desc (follow `AlbumsRepositoryImpl.loadAlbumPhotos` pattern; return
     items + next cursor).
   - `suspend fun loadArchivedPage(beforeUserDate: Long?, limit: Int): List<PhotoItem>` and
     `suspend fun loadTrashPage(beforeUserDate: Long?, limit: Int): List<PhotoItem>` — LOCAL `DriveMainIndex` (new
     `.sq` query filtering `archivalStatus = ?`, same shape as `selectPhotosPage`).
   - **Timeline exclusion:** `selectPhotosPage` (or the Kotlin filter in `loadPage`) now excludes rows with
     `archivalStatus` 1 or 2 — timeline = `archivalStatus IS NULL OR 0` (+ existing `isSoftDeleted` filter).
     Regression test at the lowest practical layer (in-memory SQLDelight driver if a JVM test harness exists for the
     DB; otherwise Kotlin-filter unit test) proving archived and trashed rows vanish from the page and restore
     re-includes them.
5. `domain/PhotoItem`: add `val isFavorite: Boolean = false`; `PhotoMapper` sets it from
   `appData.tags` contains `FAVORITE_TAG`. Test mapper both ways.
6. Koin `PhotosModule.kt`: wire `PhotoStatusWriter` into `PhotosRepositoryImpl`.

TDD loop runs `./gradlew :shared:jvmTest` (filtered while iterating, full once before commit) — this is allowed for
shared tasks; do NOT run app builds or iOS compiles (verifier's job). Write tests first (RED before impl).

## Task 2 — shared: ViewModels + Koin/iOS resolvers (TDD, depends on Task 1)

1. New `library/LibraryStateViewModel`s (one file each, copy Timeline/Albums shapes — flat UiState data class,
   `SharedFlow` events, month sections via existing `groupIntoMonthSections`):
   - `FavoritesViewModel`: sections, isLoading, selection (Timeline parity: `toggleSelection/clearSelection`),
     `refresh`/`refreshAndWait` (server-paged via `loadFavoritesPage`), `loadMore`,
     `unfavoriteSelected()`/`unfavoriteSelectedAndWait()`.
   - `ArchiveViewModel`: local-paged via `loadArchivedPage`; `unarchiveSelected()`/`AndWait` (restore to timeline).
   - `TrashViewModel`: local-paged via `loadTrashPage`; `restoreSelected()`/`AndWait`,
     `permanentDeleteSelected()`/`AndWait`.
   All mutations: single-in-flight guard + refresh after (Albums `mutate` pattern is the reference; a lighter local
   guard is fine — no Busy event needed). Errors → event.
2. `TimelineViewModel`: `favoriteSelected()`/`AndWait` (set favorite on selection),
   `archiveSelected()`/`AndWait` (archive + optimistically drop from sections, clear selection — mirrors
   `deleteSelected`).
3. `ViewerViewModel`: `ViewerUiState` gains `isFavorite: Boolean` (derived from current item);
   `toggleFavoriteCurrent()` — optimistic flip, repo call, revert + Error event on failure. Update `items` list
   entry so swiping back keeps state.
4. Koin: `factory` for the three VMs; iOS resolver funs at bottom of `PhotosModule.kt`
   (`favoritesViewModel()`, `archiveViewModel()`, `trashViewModel()`).
5. Tests: fake repository per `FakeAlbumsRepository` style. Cover: month sectioning reuse; favorite toggle
   optimistic+revert; timeline archive removes from sections; trash restore/permanent-delete update state;
   selection semantics; error events.

## Task 3 — Android UI (parallel with Task 4; touch only `androidApp/`)

1. `Routes.kt`: `Route.Favorites`, `Route.Archive`, `Route.Trash`; `AppShell.kt` NavHost entries.
2. `CollectionsScreen.kt` `LibrarySection`: enable Favorites/Archive/Trash rows (drop `"Soon"`), navigate. Utilities
   stays disabled.
3. Three screens under `ui/library/` reusing `PhotoGridCell` + month-section list + `SelectionTopBar` (grid ids
   `favorites-grid`/`archive-grid`/`trash-grid`):
   - Favorites: grid of favorites; selection → unfavorite action.
   - Archive: grid; selection → unarchive.
   - Trash: header note "Items stay in the bin until you delete them permanently."; selection bar shows Restore
     (`trash-restore`) + Delete forever (`trash-delete-forever`, confirm dialog before permanent delete).
   One shared scaffold composable for the three is encouraged (DRY) — e.g. `LibraryStateScreen` in `ui/library/`.
4. Viewer (`ViewerScreen.kt`): favorite toggle in the action bar (heart outline/filled, id `favorite-toggle`) driven
   by `ViewerUiState.isFavorite` — placed first, before Share (Google Photos order).
5. Timeline selection: favorite action (`selection-favorite` — heart icon via `SelectionTopBar` `extraActions`) and
   archive in the overflow. `PhotoGridCell`: small heart badge when `item.isFavorite`.
6. Tests (androidTest, compile-only for verifier): update `CollectionsScreenTest` (rows enabled, nav happens); add a
   compose test asserting trash screen shows Restore/Delete-forever when selection active (fake VM state if needed —
   follow existing test patterns).

## Task 4 — iOS UI (parallel with Task 3; touch only `iosApp/`)

1. `Router.swift`: `case favorites`, `case archive`, `case trash`; `CollectionsView` navigationDestination handles
   them; `LibraryRow`s for the three become enabled + navigate (Utilities stays "Soon").
2. Three SwiftUI screens (one generic month-sectioned grid view reused thrice, in `iosApp/library/`), consuming the
   new VMs via SKIE (`favoritesViewModel()` etc.). Grid a11y ids `favorites-grid`/`archive-grid`/`trash-grid`.
   Selection toolbar: Favorites → Unfavorite; Archive → Unarchive; Trash → Restore (`trash-restore`) + Delete Forever
   (`trash-delete-forever`, confirmation dialog). Trash header note text identical to Android.
3. `ViewerView.swift`: favorite action button (`heart`/`heart.fill`, id `favorite-toggle`) first in the capsule bar,
   wired to `toggleFavoriteCurrent()`; state from `ViewerUiState.isFavorite`.
4. Timeline selection: favorite action added; grid cell heart badge when favorited (match Android).
5. Remember: suspend funs returning Kotlin Boolean bridge as `KotlinBoolean` (`.boolValue`). Post
   `.hbPhotosChanged` after mutations so other screens refresh (existing pattern).
6. Tests: update `CollectionsHubUITest` (rows enabled); add `LibraryStatesUITest` skeleton, XCTSkip-gated on login
   (copy `AlbumLifecycleUITest` gating).

## Task 5 — verifier (after 3 & 4)

Build + test the whole matrix in the worktree; fix mechanical breakage found (report anything non-trivial):
1. `:shared:jvmTest` (all green), `:shared:compileAndroidMain`, `:shared:compileKotlinIosSimulatorArm64`
2. `:androidApp:assembleDebug` + `:androidApp:compileDebugAndroidTestKotlin`
3. iOS `xcodebuild build-for-testing` (workspace/scheme as Batch C; no `CODE_SIGNING_ALLOWED=NO`)
Commit fixes. Report exact commands + tail of outputs.

## Deferred (record in HANDOFF)

On-device QA is the ship gate (needs owner login): favorite round-trip visible in official web app; archive leaves
timeline on-device; trash restore/delete-forever; cross-device flag sync. Auto-purge, shared-trash semantics, Apps(3)
library: out of scope.
