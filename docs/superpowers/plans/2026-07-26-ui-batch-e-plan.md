# Batch E implementation plan — Search (metadata, no ML)

Master doc: `2026-07-25-ui-batch-e-search.md`. Branch `photos-ui-batch-e` (off batch D).
Replaces the Batch-A Search placeholder with real metadata search: **date range, type
(photo/video), album, free-text matching album names**. No ML, no new on-drive formats.

## Scope rulings (this session)

- **Filename search: DEFERRED** (master doc default — `PhotoItem`/`DriveMainIndex` have no
  filename column; adding a projection is a follow-up).
- **Source/folder filter: DEFERRED** — same gap as filename: no backing field anywhere in the
  local index or `PhotoItem`. The master doc lists it but it is unimplementable without a new
  projected field. Owner note in HANDOFF.
- **Library-metadata aggregate file (fileType 900): SKIPPED** per master doc default — local DB
  queries only; revisit only if Redmi latency measures slow. No schema gate triggered.
- **Free-text semantics (no filename): query matches album *names*** (case-insensitive
  substring); results = union of matching albums' member photos, deduped by fileId. Blank query
  with no filters = idle.

## Global constraints

- Shared module is **headless**: stops at `StateFlow<UiState>`; flat data class,
  `_uiState.update { }`; UI is native SwiftUI (iOS 26) + Jetpack Compose (Material 3).
- Follow existing VM conventions (`TimelineViewModel`): `androidx.lifecycle.ViewModel`,
  Koin `factory`, top-level iOS resolver fn in `PhotosModule.kt`.
- Reuse shared month grouping: `groupIntoMonthSections` (timeline package).
- Local photo queries: `archivalStatus = 0` only, `fileState = 1`, newest-first
  (`userDate DESC, rowId DESC`) — match `selectPhotosPage` predicate shape exactly.
- Album membership stays the server `queryBatch` path (`PhotoQueries.albumQuery`,
  archivalStatus [0,1,3]) — no new local tag query.
- Tests: `kotlin.test` + `kotlinx-coroutines-test` (StandardTestDispatcher, no Turbine).
- a11y/test ids (both platforms, exact): keep `search-screen`, `search-back`, `search-field`;
  new `search-results-grid`, `search-chip-date`, `search-chip-type`, `search-chip-album`,
  `search-recent`, `search-empty`.
- DRY: reuse `ui/components/` (Android) and `iosApp/components/` (iOS) — `PhotoGridCell`/
  `PhotoCell`, `MonthHeader`, `GridStates`, `AlbumPickerSheet` etc. before writing anything new.
- Platform tasks are code-only (no builds); one verifier builds everything (project rule).
- Minimal comments; commit per task on `photos-ui-batch-e`, trailer
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## Task 1 — shared: search layer + SearchViewModel (TDD)

Files: `shared/src/commonMain/kotlin/id/homebase/photos/search/` (new package),
`data/PhotosRepository.kt` + `PhotosRepositoryImpl.kt` + `MockPhotosRepository.kt`,
`sqldelight/.../DriveMainIndex.sq`, `PhotosModule.kt`; tests in
`shared/src/commonTest/kotlin/id/homebase/photos/search/`.

1. **`SearchCriteria`** (search package): flat data class — `fromUserDate: Long?`,
   `toUserDate: Long?`, `isVideo: Boolean?` (null = any), `albumIds: List<Uuid>` (empty = no
   album constraint). `val isEmpty` helper (all null/empty).
2. **`.sq`**: add `selectPhotosInDateRangePage` to `DriveMainIndex.sq` — same predicate as
   `selectPhotosPage` plus `userDate BETWEEN :from AND :to`, `ORDER BY userDate DESC, rowId
   DESC LIMIT :limit`.
3. **`PhotosRepository.search(criteria: SearchCriteria): List<PhotoItem>`** (suspend):
   - `albumIds` non-empty → server `albumQuery` per album (reuse the Batch-C query path), union,
     dedupe by fileId, then Kotlin-filter by date range + `isVideo`, drop archived/bin if
     present per timeline convention? NO — album results keep archivalStatus [0,1,3] like the
     Albums screens.
   - else → local `selectPhotosInDateRangePage` (missing bounds → `Long.MIN_VALUE`/
     `Long.MAX_VALUE`), Kotlin-filter `isVideo`.
   - Cap 500 results newest-first. `// ponytail: unpaged 500-cap, paginate if Redmi says so`.
4. **`RecentSearchesStore`** (search package): `KeyValue.sq` blob store à la
   `BackupFolderSelectionStore` — fixed Uuid key, JSON `List<String>`, most-recent-first,
   dedupe (case-insensitive), cap 10. API: `load(): List<String>`, `push(q: String)`, `clear()`.
5. **`SearchViewModel(photosRepository, albumsRepository, recentStore)`** —
   `SearchUiState(query: String = "", fromUserDate: Long? = null, toUserDate: Long? = null,
   typeFilter: TypeFilter = ALL /* ALL|PHOTOS|VIDEOS */, albumFilter: AlbumItem? = null,
   sections: List<TimelineSection> = emptyList(), recent: List<String> = emptyList(),
   isSearching: Boolean = false, hasSearched: Boolean = false, error: String? = null)` with
   derived `isIdle` (no query, no filters) and `isEmpty` (hasSearched && sections empty).
   Intents: `onQueryChange(String)` (state only), `setDateRange(Long?, Long?)`,
   `setTypeFilter(TypeFilter)`, `setAlbumFilter(AlbumItem?)`, `submit()` + `suspend
   submitAndWait()`, `clearFilters()`, `clearRecent()`. Behavior: filter setters re-run the
   search immediately when non-idle; `submit` with non-blank query resolves album-name matches
   via albumsRepository, pushes query to recents, searches; blank query + no filters → reset
   to idle (sections cleared). Errors → `error` in state (no event flow needed — read-only
   screen). Results grouped via `groupIntoMonthSections`.
6. **Koin**: `factory { SearchViewModel(get(), get(), get()) }` (+ `single` for the store if
   needed) and iOS resolver `fun searchViewModel(): SearchViewModel`.

**TDD (write first):** criteria composition (date+type+album each narrow a fake repo's
results); album-name free-text resolves to album filter; blank query → idle + sections
cleared; recents cap/dedupe/order; error path sets `error`. Extend `MockPhotosRepository`.
Run: `./gradlew :shared:jvmTest`.

## Task 2 — Android UI (parallel with Task 3; touch only `androidApp/`)

Replace placeholder body in `ui/search/SearchScreen.kt` (keep `search-screen`, `search-back`,
`search-field` tags; enable the field):

- `DockedSearchBar`-style top field (or keep `OutlinedTextField` enabled — whichever reuses
  more) wired to `SearchViewModel` via Koin; IME search action = `submit()`.
- Filter chip row under the bar: `FilterChip`s Date · Type · Album
  (`search-chip-date/type/album`). Date → material3 `DateRangePicker` in a dialog; Type →
  cycles/menu All/Photos/Videos; Album → reuse `AlbumPickerSheet`. Selected chips show the
  active value and clear affordance.
- Results: month-sectioned `LazyVerticalGrid` composed from `MonthHeader` + `PhotoGridCell`
  (Favorites screen is the reference), tag `search-results-grid`; tap opens the viewer the
  same way Favorites does.
- Idle: recent searches list (`search-recent`, tap = set query + submit) via existing list
  idioms; empty: `EmptyState` ("No results") tagged `search-empty`; searching: `SkeletonGrid`.
- Update `AppShellNavTest.tappingSearch_opensSearchPlaceholder` (placeholder text is gone) and
  add `SearchFlowTest` (androidTest, fake repo/Koin-override pattern like `TrashScreenTest`):
  type query → submit → grid shows results; type filter narrows; clear → idle recents.
  **Code-only: do NOT run builds or tests** — the verifier does.

## Task 3 — iOS UI (parallel with Task 2; touch only `iosApp/`)

Rework `search/SearchView.swift` (keep `.searchable`; keep `search-empty` semantics but move
the id to the empty *state* view):

- `.searchable` + `.searchScopes` (All/Photos/Videos → `setTypeFilter`); submit on
  `.onSubmit(of: .search)`.
- Filter chips row (Date · Album [+ Type if scopes feel insufficient]) with ids
  `search-chip-date`, `search-chip-type`, `search-chip-album`; Date → sheet with two
  `DatePicker`s (range); Album → simple list sheet fed by the albums the VM exposes (reuse
  existing sheet/list components where possible).
- Results month grid from `MonthHeader` + `PhotoCell` (LibraryStates screens are the
  reference), id `search-results-grid`; tap opens viewer like Favorites/Archive do.
- Idle = recents list (`search-recent`); empty = `EmptyStateView` (`search-empty`); searching
  = `SkeletonGrid`. VM via `searchViewModel()` resolver, SKIE async sequences as elsewhere.
- Add `SearchUITest.swift` (XCUITest) mirroring the Android flow; follow the existing
  launch-arg bypass pattern (`-uiTestTimeline` analog) if Search needs one.
  **Code-only: do NOT build** — verifier does.

## Task 4 — verifier (after 2 & 3)

Build + test everything; fix mechanical breakage only (report anything structural):
`./gradlew :shared:jvmTest :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64`,
`:androidApp:assembleDebug`, Android instrumented tests if an emulator is available (else
compile `:androidApp:assembleDebugAndroidTest`), iOS: xcodebuild build + XCUITests on the
iPhone 17 / iOS 26.5 simulator (see HANDOFF for signing gotchas). Green matrix = done.

## Task 5 — close-out

HANDOFF.md refresh (Batch E section: shipped surface, deferred filename/source/aggregate,
on-device QA gate joins B/C/D list). Ledger + memory updates happen in the controller session.

## Deferred (record in HANDOFF)

Filename search (needs filename projected into the index); source/folder filter (no backing
field — owner call on adding one); fileType-900 aggregate (only if Redmi latency demands);
on-device Redmi/owner-login QA (ship gate, shared with B/C/D).
