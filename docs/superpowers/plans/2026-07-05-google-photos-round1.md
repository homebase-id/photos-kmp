# Google Photos Round 1 — Modular UI + Albums + Multi-Select + Event-Driven Backup

> **For agentic workers:** This plan is executed with the repo's **code-first batched-verify**
> workflow (owner directive, overrides per-task build/run): coding agents WRITE code + tests only —
> they run NO gradle/xcodebuild. One verifier agent builds + tests + device-validates everything in
> a single pass afterwards. Workstreams S / A / I / J are disjoint-file and run in parallel.

**Goal:** Make the app Google-Photos-like and functionally complete for browsing: a modular UI
component system on both platforms (side-by-side parity), bottom navigation with a Collections
(albums) tab, multi-select with delete in the timeline — plus event-driven Android backup that
uploads new media seconds after it appears (JobScheduler content-URI trigger).

**Architecture:** All new state/logic lives in `shared` behind `StateFlow<UiState>` ViewModels
(strict TDD, commonTest). Native layers stay thin renderers, componentized into a `ui/components`
package (Android) and a `Components/` group (iOS) with 1:1 naming parity. Event backup reuses the
existing WorkManager execution path; JobScheduler only *triggers* it.

**Tech Stack:** KMP + Koin + SQLDelight + Ktor (shared), Jetpack Compose M3 (Android), SwiftUI
(iOS), WorkManager + JobScheduler (Android background).

## Global Constraints

- **Clean, easy-to-read code is a hard requirement** (owner, 2026-07-05). Small focused files, one
  component per concern, names that read as English. Match the existing style in each module.
- Minimal comments: terse one-liner only for non-obvious *why* (CLAUDE.md). Deliberate
  simplifications get a `ponytail:` comment naming the ceiling.
- **Coding agents do NOT run gradle/xcodebuild/tests.** Write the failing test first, then the
  implementation; the verifier (WS-V) runs everything in one pass.
- **No commits.** Owner commits after review (CLAUDE.md: don't commit unless asked).
- No new dependencies. Icons come from `material-icons-core` (already a dep). No navigation
  library — plain state hoisting.
- Every interactive element carries a `testTag` (Android) / `accessibilityIdentifier` (iOS);
  identifiers are identical across platforms where listed in the Contracts section.
- Palette/typography: reuse the existing `PhotosTheme` (Android) / `PhotosColor`/`PhotosFont`/
  `PhotosMetrics` (iOS) tokens. Google-Photos-like layout is explicitly approved; keep the earthy
  tokens as the brand fallback.
- **Android look: Material You** (owner /goal 2026-07-05): dynamic color on Android 12+
  (`dynamicLightColorScheme`/`dynamicDarkColorScheme` guarded by `Build.VERSION.SDK_INT >= 31`),
  earthy scheme as the fallback; M3 components only (NavigationBar, TopAppBar, AlertDialog,
  NavigationBarItem); M3 default shapes/tonal elevation — no custom chrome where an M3 component
  exists. See Task A0.
- **iOS look: iOS 26 HIG** (same /goal): stock SwiftUI system components — TabView,
  NavigationStack + large titles, `.ultraThinMaterial`, `confirmationDialog`, SF Symbols — so the
  system's current design language (Liquid Glass) applies natively. No custom chrome where a
  system component exists.
- **Deadline: working, beautiful, on-device-verified by 09:00 today.** Function + polish of what
  is in this plan beats breadth; cut scope from the Deferred list first, never from correctness.
- Shared tests live in `shared/src/commonTest/kotlin/id/homebase/photos/...` and run via
  `./gradlew :shared:jvmTest`.
- `PhotoConfig.ALBUM_FILE_TYPE = 900` already exists; drive GUIDs come from `PhotoConfig` only.
- Uuids are `kotlin.uuid.Uuid`, rendered dashed via `toString()` — never bare hex.

---

## Contracts (all agents code against these — do not drift)

### C1. TimelineViewModel selection + delete (shared)

```kotlin
// TimelineUiState gains:
val selectedIds: Set<String> = emptySet(),   // PhotoItem.fileId.toString() keys
val isDeleting: Boolean = false,
// plus computed members on the data class:
val inSelectionMode: Boolean get() = selectedIds.isNotEmpty()
fun isSelected(photo: PhotoItem): Boolean = photo.fileId.toString() in selectedIds

// TimelineEvent gains:
data class Deleted(val count: Int) : TimelineEvent

// TimelineViewModel gains:
fun toggleSelection(photo: PhotoItem)   // adds/removes; removing the last id exits selection mode
fun clearSelection()
fun deleteSelected()                    // fire-and-forget (Android)
suspend fun deleteSelectedAndWait()     // iOS awaits this
```

### C2. PhotosRepository delete (shared)

```kotlin
// PhotosRepository gains:
/** Soft-delete [fileIds] on the drive (batch). True when every file deleted. */
suspend fun deletePhotos(fileIds: List<Uuid>): Boolean
```

### C3. Albums (shared)

```kotlin
// domain/AlbumItem.kt
data class AlbumItem(
    val fileId: Uuid,       // the album file itself
    val albumId: Uuid,      // the tag photos carry = first tag on the album file
    val name: String,
    val coverFileId: Uuid?,
)

// data/AlbumsRepository.kt
interface AlbumsRepository {
    /** Album files (fileType 900) from the local DriveMainIndex. */
    suspend fun loadAlbums(): List<AlbumItem>
    /** Photos tagged into [albumId], newest first — server queryBatch. */
    suspend fun loadAlbumPhotos(albumId: Uuid): List<PhotoItem>
}

// albums/AlbumsViewModel.kt
data class AlbumSummary(val album: AlbumItem, val cover: PhotoItem?)
data class AlbumsUiState(
    val isLoading: Boolean = true,
    val albums: List<AlbumSummary> = emptyList(),
    val error: String? = null,
)
class AlbumsViewModel(repository: AlbumsRepository) : ViewModel() {
    val state: StateFlow<AlbumsUiState>
    fun refresh()
    suspend fun refreshAndWait()
}

// albums/AlbumDetailViewModel.kt
data class AlbumDetailUiState(
    val isLoading: Boolean = true,
    val title: String = "",
    val sections: List<TimelineSection> = emptyList(),  // groupIntoMonthSections(photos)
    val photos: List<PhotoItem> = emptyList(),          // flat list for the viewer pager
    val error: String? = null,
)
class AlbumDetailViewModel(album: AlbumItem, repository: AlbumsRepository) : ViewModel() {
    val state: StateFlow<AlbumDetailUiState>
    fun refresh()
    suspend fun refreshAndWait()
}

// PhotosModule.kt iOS-callable factories:
fun albumsViewModel(): AlbumsViewModel
fun albumDetailViewModel(album: AlbumItem): AlbumDetailViewModel
```

### C4. Cross-platform identifiers

Existing ids stay (`account-button`, `timeline-month-header`, `timeline-day-header`,
`timeline-empty`, `timeline-error`, `timeline-skeleton`, `logout-confirm`). New, identical on both
platforms:

| id | element |
|---|---|
| `bottom-nav` | bottom navigation container / TabView |
| `tab-photos`, `tab-collections` | the two nav items |
| `selection-topbar` | selection-mode top bar |
| `selection-count` | "N selected" label |
| `selection-close` | X exits selection |
| `selection-delete` | trash action |
| `delete-confirm` | confirm button in the delete dialog |
| `collections-grid` | albums grid |
| `album-card` | one album tile |
| `album-detail-grid` | album photo grid |
| `album-back` | back from album detail |

### C5. Selection interaction spec (identical both platforms)

- Long-press a cell (not in selection mode) → enter selection mode with that photo selected.
- Tap in selection mode toggles; tap outside selection mode opens the viewer (unchanged).
- Selected cell: image insets to ~82% with a small corner radius; filled primary-colored
  check-circle badge top-leading. Unselected cells in selection mode render unchanged.
- Top bar swaps to: X (`selection-close`) · "N selected" · trash (`selection-delete`).
- Bottom nav hides while in selection mode.
- Trash → confirm dialog "Delete N item(s)?" body "They'll be removed from your Homebase photo
  library." destructive confirm (`delete-confirm`) → `deleteSelected()`.
- `TimelineEvent.Deleted(count)` → toast/snackbar "N deleted". Android system back exits
  selection mode first (BackHandler).

---

## Workstream S — Shared logic (strict TDD, disjoint from A/I/J)

### Task S1: Selection state in TimelineViewModel

**Files:**
- Modify: `shared/src/commonMain/kotlin/id/homebase/photos/timeline/TimelineViewModel.kt`
- Test: `shared/src/commonTest/kotlin/id/homebase/photos/timeline/TimelineSelectionTest.kt`

**Interfaces:** Produces C1 (selection members only).

- [ ] **Step 1: Write the failing tests** — follow the fake-repository pattern used by
  `TimelineFirstLaunchSyncTest.kt` in the same package (read it first):

```kotlin
class TimelineSelectionTest {
    // Build the VM with a fake PhotosRepository returning two photos (reuse this package's
    // existing fake/test-date helpers — see TimelineFirstLaunchSyncTest / TestDateHelpers).

    @Test fun toggleSelection_entersSelectionMode_andSelectsPhoto() { /* toggle p1 → state.inSelectionMode true, isSelected(p1) true */ }
    @Test fun toggleSelection_samephotoTwice_exitsSelectionMode() { /* toggle p1, toggle p1 → inSelectionMode false */ }
    @Test fun clearSelection_emptiesSelectedIds() { /* toggle p1+p2, clear → selectedIds empty */ }
    @Test fun selection_keysAreDashedUuidStrings() { /* selectedIds contains p1.fileId.toString() */ }
}
```

- [ ] **Step 2: Implement** — add the C1 fields/members to `TimelineUiState`, the three non-suspend
  methods to the VM (`_state.update` only, no I/O). Keep the data class flat; computed members as in C1.

### Task S2: deletePhotos on the repository

**Files:**
- Modify: `shared/src/commonMain/kotlin/id/homebase/photos/data/PhotosRepository.kt` (C2)
- Modify: `shared/src/commonMain/kotlin/id/homebase/photos/data/PhotosRepositoryImpl.kt`
- Modify: `shared/src/commonMain/kotlin/id/homebase/photos/data/MockPhotosRepository.kt`
- Modify: `shared/src/commonMain/kotlin/id/homebase/photos/PhotosModule.kt` (inject `DriveFileProvider` into `PhotosRepositoryImpl` — it is an existing `apiModule` factory, so just `get()`)
- Test: `shared/src/commonTest/kotlin/id/homebase/photos/data/MockRepositoryDeleteTest.kt`

**Interfaces:** Produces C2. Consumes `DriveFileProvider.deleteFiles(driveId, fileIds, recipients=null): DeleteFileIdBatchResult` (`shared/.../api/client/drives/files/DriveFileProvider.kt:440`).

- [ ] **Step 1: Failing test** — `MockPhotosRepository.deletePhotos` removes the ids from its list
  and returns true; a second loadPage no longer contains them.
- [ ] **Step 2: Implement.** `PhotosRepositoryImpl.deletePhotos`: empty list → true; otherwise call
  `driveFileProvider.deleteFiles(driveId, fileIds)` and AND the per-file outcomes — read
  `DeleteFileResult` (same file, ~line 685) for the exact success field. Exceptions propagate (VM
  handles them).

### Task S3: deleteSelected in TimelineViewModel

**Files:**
- Modify: `shared/src/commonMain/kotlin/id/homebase/photos/timeline/TimelineViewModel.kt`
- Test: `shared/src/commonTest/kotlin/id/homebase/photos/timeline/TimelineDeleteTest.kt`

**Interfaces:** Consumes C2. Produces C1 delete members + `TimelineEvent.Deleted`.

- [ ] **Step 1: Failing tests:**

```kotlin
@Test fun deleteSelected_removesPhotosFromStateAndEmitsDeleted() {
    // select p1 → deleteSelectedAndWait() → pagedItems/sections lack p1, selection cleared,
    // events emitted Deleted(1), repo received [p1.fileId]
}
@Test fun deleteSelected_onRepositoryFailure_keepsSelectionAndEmitsError() {
    // fake repo returns false / throws → state unchanged, TimelineEvent.Error emitted
}
@Test fun deleteSelected_whileDeleting_isNoOp() { /* isDeleting guard */ }
```

- [ ] **Step 2: Implement** `deleteSelectedAndWait()`:

```kotlin
suspend fun deleteSelectedAndWait() {
    val current = _state.value
    if (current.isDeleting || current.selectedIds.isEmpty()) return
    val doomed = current.pagedItems.filter { current.isSelected(it) }
    _state.update { it.copy(isDeleting = true) }
    val deleted = try {
        repository.deletePhotos(doomed.map { it.fileId })
    } catch (e: Exception) {
        Logger.w(tag = TAG) { "delete failed: ${e.message}" }
        false
    }
    if (deleted) {
        _state.update {
            val remaining = it.pagedItems - doomed.toSet()
            it.copy(isDeleting = false, selectedIds = emptySet(),
                    pagedItems = remaining, sections = groupIntoMonthSections(remaining))
        }
        _events.tryEmit(TimelineEvent.Deleted(doomed.size))
    } else {
        _state.update { it.copy(isDeleting = false) }
        emitError("Couldn't delete")
    }
}
fun deleteSelected() { viewModelScope.launch { deleteSelectedAndWait() } }
```

### Task S4: Albums read path (mapper + repository)

**Files:**
- Create: `shared/src/commonMain/kotlin/id/homebase/photos/domain/AlbumItem.kt` (C3)
- Create: `shared/src/commonMain/kotlin/id/homebase/photos/data/AlbumMapper.kt`
- Create: `shared/src/commonMain/kotlin/id/homebase/photos/data/AlbumsRepository.kt` (interface, C3)
- Create: `shared/src/commonMain/kotlin/id/homebase/photos/data/AlbumsRepositoryImpl.kt`
- Test: `shared/src/commonTest/kotlin/id/homebase/photos/data/AlbumMapperTest.kt`

**Interfaces:** Produces C3 repository half. Consumes `selectPhotosPage` (DriveMainIndexWrapper.kt:155 — same call PhotosRepositoryImpl.loadPage makes, with `fileType = PhotoConfig.ALBUM_FILE_TYPE`), `PhotoQueries.albumQuery(albumId)` (exists), and `DriveQueryProvider.queryBatch` — read `QueryBatchTagFilterTest.kt` (commonTest, this package's neighbor) for the exact queryBatch call shape before writing `loadAlbumPhotos`.

- [ ] **Step 1: Failing mapper tests** — reuse the `HomebaseFile` fixture pattern from
  `PhotoMapperTest.kt` (same package):

```kotlin
@Test fun albumFile_mapsNameCoverAndAlbumIdFromFirstTag()
@Test fun albumFile_withoutTags_isSkipped()          // returns null
@Test fun albumFile_withBlankOrMissingContent_fallsBackToUntitled()
```

- [ ] **Step 2: Implement** `AlbumMapper` (pure, no I/O — mirrors `PhotoMapper`):

```kotlin
@Serializable
private data class AlbumContent(val name: String? = null, val coverFileId: String? = null)

object AlbumMapper {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Null when the row can't act as an album (no tag to look photos up by). */
    fun fromHomebaseFile(file: HomebaseFile): AlbumItem? {
        val albumId = file.fileMetadata.appData.tags?.firstOrNull() ?: return null
        val content = file.fileMetadata.appData.content
            ?.let { runCatching { json.decodeFromString<AlbumContent>(it) }.getOrNull() }
        return AlbumItem(
            fileId = file.fileId,
            albumId = albumId,
            name = content?.name?.takeIf { it.isNotBlank() } ?: "Untitled",
            coverFileId = content?.coverFileId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
        )
    }
}
```

  (Verify the exact `appData.tags` / `appData.content` property names against `HomebaseFile.kt` —
  `ServerFile.kt:88` already reads `appData.content`.)

- [ ] **Step 3: Implement** `AlbumsRepositoryImpl(driveId, databaseManager, credentialsManager, driveQueryProvider)`:
  - `loadAlbums()`: mirror `PhotosRepositoryImpl.loadPage`'s identity guard, then
    `selectPhotosPage(fileType = PhotoConfig.ALBUM_FILE_TYPE.toLong(), beforeUserDate = Long.MAX_VALUE, limit = 500)`
    → `filterNot { it.isSoftDeleted() }` → `mapNotNull(AlbumMapper::fromHomebaseFile)`.
    `// ponytail: 500-album ceiling, page when someone actually has more`
  - `loadAlbumPhotos(albumId)`: `driveQueryProvider.queryBatch(...)` with
    `PhotoQueries.albumQuery(albumId)` → map results through `PhotoMapper.fromHomebaseFile`,
    dropping soft-deleted.

### Task S5: Albums ViewModels + Koin + iOS factories

**Files:**
- Create: `shared/src/commonMain/kotlin/id/homebase/photos/albums/AlbumsViewModel.kt`
- Create: `shared/src/commonMain/kotlin/id/homebase/photos/albums/AlbumDetailViewModel.kt`
- Modify: `shared/src/commonMain/kotlin/id/homebase/photos/PhotosModule.kt`
- Test: `shared/src/commonTest/kotlin/id/homebase/photos/albums/AlbumsViewModelTest.kt`
- Test: `shared/src/commonTest/kotlin/id/homebase/photos/albums/AlbumDetailViewModelTest.kt`

**Interfaces:** Produces C3 ViewModel half. Consumes S4's `AlbumsRepository`.

- [ ] **Step 1: Failing tests** (fake `AlbumsRepository`):

```kotlin
// AlbumsViewModelTest
@Test fun loadsAlbums_thenResolvesCovers()      // first emission: albums w/ null covers, isLoading false; later: covers filled
@Test fun coverPrefersCoverFileId_elseFirstPhoto()
@Test fun repositoryFailure_setsError()
// AlbumDetailViewModelTest
@Test fun titleSeededFromAlbumName_beforeLoadCompletes()
@Test fun loadGroupsPhotosIntoMonthSections()   // reuse groupIntoMonthSections expectations from TimelineGroupingTest
@Test fun failure_setsError()
```

- [ ] **Step 2: Implement.** `AlbumsViewModel.refreshAndWait()`: load albums → emit summaries with
  null covers → resolve covers concurrently (`coroutineScope { map { async { ... } } }`,
  cover = photos.firstOrNull { it.fileId == coverFileId } ?: photos.firstOrNull()) → emit final.
  `// ponytail: cover = full album query per album; cap with maxRecords when albums grow`
  `AlbumDetailViewModel`: seed `title = album.name`, `load()` in init →
  `repository.loadAlbumPhotos(album.albumId)` → sections via `groupIntoMonthSections`.
- [ ] **Step 3: Koin + factories** in `PhotosModule.kt`:

```kotlin
single<AlbumsRepository> {
    AlbumsRepositoryImpl(
        driveId = Uuid.parseHex(PhotoConfig.DRIVE_ALIAS),
        databaseManager = get(), credentialsManager = get(), driveQueryProvider = get(),
    )
}
factory { AlbumsViewModel(get()) }

fun albumsViewModel(): AlbumsViewModel = KoinPlatform.getKoin().get()
fun albumDetailViewModel(album: AlbumItem): AlbumDetailViewModel =
    AlbumDetailViewModel(album, KoinPlatform.getKoin().get())
```

---

## Workstream A — Android UI (Compose). Files disjoint from S except none.

Read first: `TimelineScreen.kt`, `Theme.kt`, `MainActivity.kt`, `BackupStatusCard.kt`, existing
`androidTest` files (patterns to copy). All components go in
`androidApp/src/main/kotlin/id/homebase/photos/android/ui/components/`.

### Task A0: Material You dynamic color

**Files:**
- Modify: `ui/theme/Theme.kt`

- [ ] In `PhotosTheme`, when `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`, use
  `dynamicDarkColorScheme(context)` / `dynamicLightColorScheme(context)` in place of the static
  schemes; otherwise keep the existing earthy schemes. Keep `PhotosTheme.extended` tokens
  (gridGap, overlayChrome, onOverlay) working under both. Verify every screen still reads its
  colors from `MaterialTheme.colorScheme` — fix any hardcoded color that breaks under dynamic
  color (the placeholder gradients in `PhotoGridCell` are intentional and stay).

### Task A1: Extract the component package (pure refactor, no behavior change)

**Files:**
- Create: `ui/components/PhotoGridCell.kt` — move `PhotoCell` + `VideoBadge` + gradient/placeholder
  helpers out of `TimelineScreen.kt` (lines 96–104, 531–605), renamed `PhotoGridCell`, public, with
  new params `selected: Boolean = false`, `selectionMode: Boolean = false`,
  `onLongPress: () -> Unit = {}` (use `Modifier.combinedClickable(onClick, onLongClick)`).
  Selected rendering per C5 (inset 0.82 via padding + `clip(RoundedCornerShape(8.dp))`, filled
  `Icons.Filled.CheckCircle` primary-tint badge top-start, `testTag("timeline-cell-check")`).
- Create: `ui/components/SectionHeaders.kt` — move `MonthHeader`, `DaySubhead` (public).
- Create: `ui/components/GridStates.kt` — move `SkeletonGrid`, `EmptyState`, `ErrorState`,
  `FooterLoading` (public; keep testTags).
- Create: `ui/components/TopBars.kt` — move `PhotosTopBar`; add `SelectionTopBar(count, onClose,
  onDelete)` per C4/C5 ids.
- Modify: `ui/timeline/TimelineScreen.kt` — shrink to composition of the above (grid + render
  model + state branching stay).
- Test: `androidTest/.../ui/components/ComponentsTest.kt` — `SelectionTopBar` shows count and fires
  both callbacks; `PhotoGridCell(selected=true)` shows the check badge.

**Interfaces:** Produces the components package consumed by A2–A4.

- [ ] Step 1: write the failing component test (createComposeRule pattern from `BackupCardTest.kt`).
- [ ] Step 2: move code, make public, add selection params. No visual drift outside C5.

### Task A2: Selection mode in the timeline

**Files:**
- Modify: `ui/timeline/TimelineScreen.kt`
- Modify: `MainActivity.kt` (collect `TimelineEvent.Deleted` → snackbar "N deleted", same pattern
  as the existing error-event collection)
- Test: `androidTest/.../ui/timeline/SelectionFlowTest.kt`

**Interfaces:** Consumes C1 (VM already provides it — S1/S3), components from A1. Implements C5.

- [ ] Step 1: failing test — render `TimelineScreen` with a fake `TimelineUiState` containing
  `selectedIds`; assert `selection-topbar` + `selection-count` text "2 selected"; tapping
  `selection-delete` opens dialog with `delete-confirm`.
- [ ] Step 2: wire long-press → `viewModel::toggleSelection` (enter), tap → toggle when
  `state.inSelectionMode`, `BackHandler(state.inSelectionMode) { viewModel.clearSelection() }`,
  top bar swap, delete confirm dialog → `viewModel.deleteSelected()`.

### Task A3: Bottom nav + home scaffold

**Files:**
- Create: `ui/components/HomeBottomBar.kt`:

```kotlin
/** Two-destination bottom bar: Photos and Collections. Hidden by callers during selection. */
@Composable
fun HomeBottomBar(selectedTab: HomeTab, onTabSelected: (HomeTab) -> Unit) {
    NavigationBar(modifier = Modifier.testTag("bottom-nav")) {
        NavigationBarItem(
            selected = selectedTab == HomeTab.Photos,
            onClick = { onTabSelected(HomeTab.Photos) },
            icon = { Icon(Icons.Outlined.Photo, contentDescription = null) },
            label = { Text("Photos") },
            modifier = Modifier.testTag("tab-photos"),
        )
        NavigationBarItem(
            selected = selectedTab == HomeTab.Collections,
            onClick = { onTabSelected(HomeTab.Collections) },
            icon = { Icon(Icons.Outlined.Collections, contentDescription = null) },
            label = { Text("Collections") },
            modifier = Modifier.testTag("tab-collections"),
        )
    }
}
enum class HomeTab { Photos, Collections }
```

  (If `Icons.Outlined.Collections` is absent from material-icons-core, use `Icons.Outlined.PhotoLibrary`.)
- Create: `ui/home/HomeScreen.kt` — `rememberSaveable` tab state; Photos tab = existing
  `TimelineScreen` (backup card slot intact), Collections tab = `CollectionsScreen` (A4); bottom
  bar hidden when timeline `state.inSelectionMode`.
- Modify: `MainActivity.kt` — host `HomeScreen` where `TimelineScreen` was; keep viewer/login
  wiring unchanged.
- Test: `androidTest/.../ui/home/HomeBottomBarTest.kt` — tab click fires callback; both tags exist.

**Interfaces:** Consumes A1/A4 screens. Produces `HomeTab`, `HomeScreen(...)`.

### Task A4: Collections + album detail screens

**Files:**
- Create: `ui/components/AlbumCard.kt` — square cover (Coil via existing `homebaseImageData` when
  `summary.cover != null`, else the deterministic gradient from `PhotoGridCell`'s palette), 12dp
  radius, name below in `titleSmall`, `testTag("album-card")`.
- Create: `ui/collections/CollectionsScreen.kt` — 2-column `LazyVerticalGrid` of `AlbumCard`,
  states (loading skeleton of 6 gray squares / empty "No albums yet" / error+retry) reusing
  `GridStates` components, `testTag("collections-grid")`; stateful overload collects
  `albumsViewModel().state`.
- Create: `ui/collections/AlbumDetailScreen.kt` — top bar: back arrow (`album-back`) + album name;
  grid reuses `MonthHeader`/`DaySubhead`/`PhotoGridCell` over `AlbumDetailUiState.sections`
  (`testTag("album-detail-grid")`); photo tap → `onPhotoClick(photo)` hoisted; no selection mode
  here (round 1).
- Modify: `ui/home/HomeScreen.kt` + `MainActivity.kt` — plain-state navigation:
  `var openAlbum by rememberSaveable(stateSaver = ...) { mutableStateOf<AlbumItem?>(null) }` is
  NOT saveable — hold it as plain `mutableStateOf` (config change re-lands on Collections; fine,
  note it with a `ponytail:` comment). `BackHandler(openAlbum != null) { openAlbum = null }`.
  Album detail photo tap opens the existing `ViewerScreen` with `AlbumDetailUiState.photos`.
- Test: `androidTest/.../ui/collections/CollectionsScreenTest.kt` — fake state with 2 albums →
  2 `album-card`s, click fires callback; error state shows retry.

**Interfaces:** Consumes C3 (`AlbumsUiState`, `AlbumSummary`, `AlbumDetailUiState`,
`albumsViewModel()`, `albumDetailViewModel(album)`), A1 components.

---

## Workstream I — iOS UI (SwiftUI). Mirrors A task-for-task.

Read first: `TimelineView.swift`, `Theme.swift`, `RootView.swift`, `ViewerView.swift`,
`TimelineModel.swift` (the `@StateObject`+SKIE collection pattern), existing `iosAppUITests`.
New components live in `iosApp/iosApp/components/`; register new files in the Xcode project the
same way existing ones are (check `project.pbxproj` handling — if the project uses
`fileSystemSynchronizedGroups` no registration is needed; otherwise add entries).

### Task I1: Extract components

**Files:**
- Create: `components/PhotoCell.swift` — move `PhotoCell` + `VideoBadge` from `TimelineView.swift`
  (lines 289–423), public within module; add `selected: Bool = false`,
  `selectionMode: Bool = false`, `onLongPress: () -> Void = {}`
  (`.onLongPressGesture` + `.simultaneousGesture` so tap still works). Selected rendering per C5
  (`.padding(6)` + `RoundedRectangle(cornerRadius: 8)` clip + `checkmark.circle.fill` primary
  badge top-leading, `accessibilityIdentifier("photo-cell-check")`).
- Create: `components/SectionHeaders.swift` — move `MonthHeader`, `DayHeader`.
- Create: `components/GridStates.swift` — move skeleton/empty/error/footer builders as small
  `View` structs (`SkeletonGrid(columns:)`, `EmptyStateView`, `ErrorStateView(message:onRetry:)`,
  `PaginationFooter`), keeping accessibility ids.
- Create: `components/SelectionTopBar.swift` — an HStack bar per C4/C5 ids (rendered via
  `.safeAreaInset(edge: .top)` overlay when selection is active — replacing the nav bar is
  fiddlier than covering it).
- Modify: `timeline/TimelineView.swift` — compose the moved pieces; behavior unchanged.

**Interfaces:** Produces the components consumed by I2–I4.

- [ ] Step 1: move + parameterize; keep every existing `accessibilityIdentifier`.

### Task I2: Selection mode in the timeline

**Files:**
- Modify: `timeline/TimelineView.swift`, `timeline/TimelineModel.swift`
- Test: `iosAppUITests/SelectionUITest.swift`

**Interfaces:** Consumes C1 via SKIE: `model.vm.toggleSelection(photo:)`, `clearSelection()`,
`try? await model.vm.deleteSelectedAndWait()`; `state.inSelectionMode` / `state.isSelected(photo:)`.

- [ ] Step 1: failing UITest — long-press first `photo-cell` → `selection-count` exists; tap
  `selection-close` → gone. (Smoke-level, follows `TimelineGridUITest` conventions.)
- [ ] Step 2: wire long-press/tap per C5; `SelectionTopBar` overlay when
  `state.inSelectionMode`; `.confirmationDialog` for delete (destructive, `delete-confirm`);
  deleted-count toast reuses the existing `toastView` (`model.toastMessage`) — set it from a
  SKIE subscription to `vm.events` in `TimelineModel` (same pattern as its error handling).

### Task I3: Tab bar

**Files:**
- Create: `home/HomeTabView.swift`:

```swift
/// Two-tab home: Photos (timeline) and Collections. Hidden tab bar during selection is handled
/// inside TimelineView via .toolbar(.hidden, for: .tabBar).
struct HomeTabView: View {
    var body: some View {
        TabView {
            TimelineView()
                .tabItem { Label("Photos", systemImage: "photo.on.rectangle") }
                .accessibilityIdentifier("tab-photos")
            CollectionsView()
                .tabItem { Label("Collections", systemImage: "rectangle.stack") }
                .accessibilityIdentifier("tab-collections")
        }
        .accessibilityIdentifier("bottom-nav")
    }
}
```

- Modify: `RootView.swift` — authenticated branch shows `HomeTabView` instead of `TimelineView`.
- Modify: `timeline/TimelineView.swift` — `.toolbar(model.uiState?.inSelectionMode == true ? .hidden : .visible, for: .tabBar)`.
- Test: `iosAppUITests/TabsUITest.swift` — tap Collections tab → `collections-grid` (or its
  empty state) appears; tap Photos → timeline root back.

### Task I4: Collections + album detail

**Files:**
- Create: `collections/CollectionsModel.swift` — `@MainActor final class CollectionsModel:
  ObservableObject` owning ONE `AlbumsViewModel` (`PhotosModuleKt.albumsViewModel()`), collecting
  `state` via SKIE `for await` (copy `TimelineModel`'s pattern), `@Published var uiState: AlbumsUiState?`.
- Create: `collections/CollectionsView.swift` — `NavigationStack`; 2-column `LazyVGrid` of
  `AlbumCard`; loading/empty/error states from `GridStates`; `navigationDestination` pushes
  `AlbumDetailView(album:)`; `.refreshable { try? await model.vm.refreshAndWait() }`;
  ids per C4.
- Create: `components/AlbumCard.swift` — square cover via `ThumbnailLoader.shared.image(for:
  summary.cover, maxDim: 300)` when cover non-nil else gradient fallback (reuse `PhotoCell`'s
  gradient helper), 12pt radius, name below in `PhotosFont.dateSubhead`, id `album-card`.
- Create: `collections/AlbumDetailView.swift` + `collections/AlbumDetailModel.swift` — model owns
  `PhotosModuleKt.albumDetailViewModel(album:)`; view: back handled by NavigationStack (tag the
  back button `album-back` via `.navigationBarBackButtonHidden` + custom `ToolbarItem` only if the
  default back can't carry the id — otherwise skip the id and note it), title = `state.title`,
  month/day grid reusing `SectionHeaders` + `PhotoCell` (no selection), id `album-detail-grid`;
  photo tap → `fullScreenCover` `ViewerView(items: state.photos, initialIndex:)`.

**Interfaces:** Consumes C3 factories + I1 components + existing `ThumbnailLoader`/`ViewerView`.

---

## Workstream J — Android event-driven backup (files disjoint from A)

Read first: `BackupScheduler.kt`, `BackupWorker.kt`, `PhotosApp.kt`, `AndroidManifest.xml`.

### Task J1: Media-watch job + re-arm lifecycle

**Files:**
- Create: `androidApp/src/main/kotlin/id/homebase/photos/android/work/MediaWatchScheduler.kt`:

```kotlin
/**
 * Event-driven backup trigger: a JobScheduler job that fires shortly after new rows land in
 * MediaStore (images or video), then re-arms itself — content-trigger jobs are one-shot by
 * design. Execution stays on the existing WorkManager path; this only *triggers* it.
 */
object MediaWatchScheduler {

    private const val JOB_ID = 4243

    fun schedule(context: Context) {
        context.getSystemService(JobScheduler::class.java).schedule(jobInfo(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }

    internal fun jobInfo(context: Context): JobInfo =
        JobInfo.Builder(JOB_ID, ComponentName(context, MediaWatchJobService::class.java))
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                )
            )
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                )
            )
            .setTriggerContentUpdateDelay(5_000)   // let bursts (screenshots, bursts) settle
            .setTriggerContentMaxDelay(30_000)     // upload starts within ~30s of a new photo
            .build()
}
```

- Create: `androidApp/src/main/kotlin/id/homebase/photos/android/work/MediaWatchJobService.kt`:

```kotlin
/** Fires when MediaStore changes: kicks one backup pass via WorkManager and re-arms the watch. */
class MediaWatchJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        BackupScheduler.backupNow(this)
        MediaWatchScheduler.schedule(this) // one-shot by design — re-arm for the next change
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
```

- Create: `androidApp/src/main/kotlin/id/homebase/photos/android/work/BootCompletedReceiver.kt`:

```kotlin
/**
 * Content-trigger jobs can't be persisted across reboot — re-arm on boot. Unconditional:
 * BackupWorker itself gates on the shared enabled flag, so a disabled backup stays silent.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) MediaWatchScheduler.schedule(context)
    }
}
```

- Modify: `BackupScheduler.kt` — extract the expedited one-shot enqueue into
  `fun backupNow(context: Context)`; `enable()` = `backupNow(context)` + periodic +
  `MediaWatchScheduler.schedule(context)`; `disable()` also `MediaWatchScheduler.cancel(context)`.
- Modify: `BackupWorker.kt` — first line of `doWork()`:

```kotlin
val koin = GlobalContext.get()
if (!koin.get<BackupEnabledStore>().enabled()) return Result.success()
```

- Modify: `PhotosApp.kt` — in `onCreate`, after Koin init: `MediaWatchScheduler.schedule(this)`
  (idempotent same-id replace; covers force-stop clearing scheduled jobs).
- Modify: `androidApp/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<!-- inside <application> -->
<service
    android:name=".work.MediaWatchJobService"
    android:permission="android.permission.BIND_JOB_SERVICE"
    android:exported="false" />
<receiver android:name=".work.BootCompletedReceiver" android:exported="false">
    <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter>
</receiver>
```

**Interfaces:** Consumes existing `BackupWorker` path + `BackupEnabledStore` (Koin single).

### Task J2: Job-info test

**Files:**
- Test: `androidApp/src/androidTest/kotlin/id/homebase/photos/android/work/MediaWatchJobInfoTest.kt`

- [ ] Instrumented test (plain JUnit4 + `InstrumentationRegistry.getInstrumentation().targetContext`):
  `MediaWatchScheduler.jobInfo(context)` has 2 trigger content uris (images + video), update delay
  5000, max delay 30000, and targets `MediaWatchJobService`.

---

## Workstream V — Batched verification (single agent, runs AFTER S/A/I/J all report done)

- [ ] **V1: Shared:** `./gradlew :shared:jvmTest :shared:compileKotlinIosSimulatorArm64` — all green.
- [ ] **V2: Android build:** `./gradlew :androidApp:assembleDebug`.
- [ ] **V3: iOS build:** xcodebuild via the repo's proven invocation (see
  `ios-static-framework-sqlite-link` memory: `-lsqlite3` OTHER_LDFLAGS, iPhone 17 / iOS 26.5 sim,
  never `CODE_SIGNING_ALLOWED=NO`).
- [ ] **V4: Fix loop:** compile/test failures get fixed directly by the verifier when mechanical;
  design-level failures fan back to a fix agent per workstream.
- [ ] **V5: Device QA via argent** (iPhone 17 sim + Redmi Note 5 Pro USB serial `6057f11e`, both
  already logged in):
  - Bottom nav shows Photos/Collections; Collections lists real albums from the drive (or a clean
    empty state); album opens with its photos; viewer opens from album.
  - Selection: long-press → count bar; select 1 photo **that QA itself uploaded this session**
    (adb-push a test image → event-backup uploads it); delete it; confirm toast + grid removal.
    NEVER delete pre-existing photos.
  - Event backup (Redmi): `adb push` a jpg into `/sdcard/DCIM/Camera/` + media-scan broadcast →
    the photo appears in the timeline (upload + sync) without opening the backup card. Confirm
    ≤ ~1 min latency. Note MIUI battery-optimization caveat in the report.
  - Android instrumented tests (`:androidApp:connectedDebugAndroidTest`) and iOS XCUITests run
    on whichever target is available; report, don't block on emulator flakiness.
- [ ] **V6: Android compose androidTest + iOS XCUITest suites pass (new tests included).**

## Final task (main session, after V passes)

- [ ] Update `HANDOFF.md` — owner standing directive (2026-07-05): HANDOFF.md must be refreshed at
  the end of every finishing run (status, what shipped, what's next, new gotchas).
- [ ] Update memory: Google-Photos look explicitly approved when functional; event-backup shipped.
- [ ] Report to owner: what shipped, test evidence, device screenshots, what's deferred
  (album create/edit, share, video playback, iOS event-driven backup via PHPhotoLibraryChangeObserver).

## Deferred (explicitly out of this round)

- Album create / rename / add-photos-to-album (needs the header-update/tag-write path).
- Share, favorites, archive, trash-with-restore, search, month scrubber (Batch 3).
- Video playback (spec: riskiest slice, last).
- iOS background event-driven backup (BGTask + PHPhotoLibraryChangeObserver — Batch 2 iOS half).
