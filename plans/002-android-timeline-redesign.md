# Plan 002: Android timeline redesign — chrome, states, placeholders, pagination, Coil hardening

> **Executor instructions**: Follow this plan step by step. You are a WRITER in a
> code-first / batched-verify workflow: **do NOT run gradle or any build/test
> command** — a single verifier agent builds everything after all writers finish.
> Self-review each edit instead. Touch only the files listed as in scope. If any
> STOP condition occurs, stop and report — do not improvise. Do NOT commit.
> Do NOT update `plans/README.md`.
>
> **Drift check (run first)**: The source tree is uncommitted; compare the
> "Current state" excerpts below against the live files. On a mismatch, STOP.
>
> **Contract dependency**: This plan codes against Plan 001's API, which a
> sibling writer is producing IN PARALLEL. Do not read half-written shared
> files to "check" — code against the signatures given here:
> `TimelineUiState.isPaginating: Boolean`, `TimelineViewModel.refreshAndWait()`
> (suspend), and mock items now carrying `previewPlaceholder` base64 webp.

## Status

- **Priority**: P1
- **Effort**: L
- **Risk**: MED (largest UI rewrite of the wave; verifier + Compose UI tests gate it)
- **Depends on**: plans/001-shared-timeline-contract-prep.md
- **Category**: ui-ux + perf
- **Planned at**: commit `86e57a2`, 2026-07-04 (tree uncommitted — excerpts are ground truth)

## Why this matters

The owner rejected the first-pass UI ("absolutely ugly"). Audit findings AUI-01,
AUI-02, AUI-03, AUI-05, AUI-06, AUI-07, AUI-08, AUI-09, AUI-10, AUI-13, PERF-06,
PERF-07, PERF-11: the Android timeline has no top bar, no loading/empty/error
states, draws under the status bar, double-draws the sticky month header at the
top, never uses the blur placeholder, never paginates past 60 photos, has no
pull-to-refresh, no day sub-headers, is invisible to TalkBack, and its Coil
loader would (with real data) write decrypted photos into a plaintext 250 MB
disk cache. This plan brings the screen to the design-system §5.2 spec and the
chat-kmp quality bar while keeping the Conservatory palette.

## Current state

- `androidApp/src/main/kotlin/id/homebase/photos/android/ui/timeline/TimelineScreen.kt`
  (265 lines) — bare `BoxWithConstraints` + `LazyVerticalGrid`; renders ONLY
  `state.sections`; overlay pinned header via `derivedStateOf { topVisibleSectionTitle(...) }`
  drawn unconditionally at `Alignment.TopStart`; `PhotoCell` = `AsyncImage`
  with no placeholder; `contentPadding = PaddingValues(0.dp)`; `GRID_GAP = 1.5.dp`;
  `columnsFor(widthDp)` breakpoints 3/4/6/8/10. Key excerpt:

```kotlin
// TimelineScreen.kt:145
val stuckTitle by remember(rows) {
    derivedStateOf { topVisibleSectionTitle(gridState, rows) }
}
if (stuckTitle != null) {
    MonthHeader(title = stuckTitle!!, modifier = Modifier.align(Alignment.TopStart))
}
// TimelineScreen.kt:189 — no placeholder/error painter
AsyncImage(
    model = homebaseImageData(...GRID_THUMB_SIZE..., keyHeader = KeyHeader.empty()),
    imageLoader = imageLoader, contentDescription = null,
    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
)
```

- `androidApp/src/main/kotlin/id/homebase/photos/android/MainActivity.kt` —
  `enableEdgeToEdge()` then `PhotosTheme { TimelineScreen(vm, imageLoader) }`;
  never collects `viewModel.events`; no `onPhotoClick`.
- `androidApp/src/main/kotlin/id/homebase/photos/android/ui/CoilSetup.kt` —
  `ImageLoader.Builder(context).components{ Keyer + HomebaseImageFetcher }.crossfade(true).build()`
  — no memoryCache cap, no `diskCache(null)`, no singleton install.
- `androidApp/src/main/res/values/themes.xml:6` — hard-codes
  `android:statusBarColor` to the window background (fights edge-to-edge).
- Shared state (after Plan 001): `TimelineUiState(isLoading, isPaginating,
  sections, pagedItems, endReached, error)`; `TimelineSection(title, items)`;
  `PhotoItem(fileId, uniqueId, userDate, isVideo, pixelWidth, pixelHeight,
  previewPlaceholder, driveId, payloadKey)`; VM: `refresh()`, `refreshAndWait()`,
  `loadMore()`, `events: SharedFlow<TimelineEvent>` with `TimelineEvent.Error(message)`.
- Theme: `PhotosTheme` (M3) + `PhotosTheme.extended` (gridGap, overlayChrome,
  onOverlay, onSurfaceVariantDim, surface ladder...). Type roles: monthHeader =
  `headlineSmall`, dateSubhead = `titleSmall`, titleLarge = app bar title.
- Versions: compose BOM `2026.06.00` (material3 includes `PullToRefreshBox`),
  Coil 3.4.0, minSdk 28 (java.time OK), Kotlin 2.3.21 (strong skipping on).
- Design spec: `docs/design/design-system.md` §4.3/§4.4/§5.2. Quality exemplar
  (structure only, keep Conservatory colors): chat-kmp
  `homebase-core/src/androidMain/kotlin/id/homebase/core/di/AppModule.android.kt:85-123`
  (Coil config) and `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/moments/MomentsAlbumGrid.kt`
  (grid keys/contentType/placeholder-tinted cells).

Verified chat-kmp Coil exemplar (my read, transplant with our two components):

```kotlin
.memoryCache { MemoryCache.Builder().maxSizePercent(androidContext(), 0.25).build() }
.diskCache(null)
.build()
.also(::installAsCoilSingleton)   // SingletonImageLoader.setSafe { loader }
```

## Commands you will need

NONE (writer). Verifier runs `./gradlew :androidApp:assembleDebug` and
`:androidApp:connectedDebugAndroidTest` (device: Pixel_9 AVD).

## Suggested executor toolkit

- If available, consult the `frontend-design` skill's restraint principles and
  the `kmp-compose-multiplatform` skill for Compose idioms. Not required.

## Scope

**In scope**:
- `androidApp/src/main/kotlin/id/homebase/photos/android/ui/timeline/TimelineScreen.kt` (rewrite)
- `androidApp/src/main/kotlin/id/homebase/photos/android/MainActivity.kt`
- `androidApp/src/main/kotlin/id/homebase/photos/android/ui/CoilSetup.kt`
- `androidApp/src/main/res/values/themes.xml`
- `androidApp/src/androidTest/kotlin/id/homebase/photos/android/ui/timeline/TimelineScreenTest.kt`
- You MAY create `ui/timeline/TimelineCells.kt` if TimelineScreen.kt exceeds
  ~500 lines; otherwise keep one file (fewest files wins).

**Out of scope** (do NOT touch):
- Anything under `shared/` (Plan 001's writer owns it) or `iosApp/` (Plan 003's).
- `ui/theme/Theme.kt`, `Color.kt` — the palette/tokens are approved; consume, don't edit.
- `androidApp/build.gradle.kts` — no new dependencies. Everything below is in
  the existing compose BOM / material3 / coil3 artifacts.
- No fullscreen viewer (Plan 004) and no pinch-to-zoom (Plan 005): wire
  `onPhotoClick` up to MainActivity as a parameter, leave the Activity handler
  `{ /* viewer: plan 004 */ }`.

## Git workflow

None. Working tree only, no commits.

## Steps

### Step 1: Stateless-screen API + state branches (AUI-01)

Extend the stateless `TimelineScreen` overload's parameters:

```kotlin
@Composable
fun TimelineScreen(
    state: TimelineUiState,
    onPhotoClick: (PhotoItem) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    imageLoader: ImageLoader? = null,
    modifier: Modifier = Modifier,
)
```

Branch INSIDE the scaffold content (Step 2):
- `state.isLoading && state.sections.isEmpty()` → skeleton grid: the normal
  grid layout but `columns * 12` plain `Box(aspectRatio 1f, background =
  PhotosTheme.extended.gridGap)` cells, `testTag("timeline-skeleton")`. No spinner
  (design §5.2: "no spinner per cell... photos-shaped skeleton").
- `!state.isLoading && state.sections.isEmpty() && state.error != null` →
  error state: `display` "Couldn't load photos", `bodyMedium` message in
  `onSurfaceVariant`, `Button(onClick = onRetry)` "Try again" (primary),
  `testTag("timeline-error")`.
- `!state.isLoading && state.sections.isEmpty()` → empty state per §5.2:
  `Text("No photos yet", style = displaySmall)`, `Text("Back up your camera
  roll to see it here.", bodyMedium, onSurfaceVariant)`, primary pill button
  "Back up" → `onBackupClick`. `testTag("timeline-empty")`.
- else → the grid (Step 3+).

### Step 2: Scaffold, top bar, FAB, insets (AUI-02, AUI-05, AUI-13)

- Wrap in `Scaffold(containerColor = MaterialTheme.colorScheme.background)`
  with `topBar = { PhotosTopBar(scrolled = gridState.canScrollBackward) }` and
  a `SnackbarHost`. Hoist `gridState` above the Scaffold.
- `PhotosTopBar`: `TopAppBar` (`@OptIn(ExperimentalMaterial3Api::class)`),
  title `Text("Photos", style = MaterialTheme.typography.titleLarge)`, actions:
  a 32dp `Icon(Icons.Outlined.AccountCircle, tint = onSurfaceVariant)` inside a
  44dp click target (no-op for now, `contentDescription = "Account"`).
  `colors = TopAppBarDefaults.topAppBarColors(containerColor = surface,
  scrolledContainerColor = surface)`. Below the bar draw a 1dp `outline`
  hairline ONLY when `scrolled` (design §4.3 Level-1): e.g. a `HorizontalDivider`
  in a Column, alpha-animated with `animateFloatAsState`.
- FAB: `ExtendedFloatingActionButton` pill (`shape = MaterialTheme.shapes.extraLarge`,
  container `primary`, content `onPrimary`), icon `Icons.Outlined.CloudUpload`
  (or `Upload` if unavailable in the bundled material-icons-core set — pick one
  that exists in core icons; do NOT add the extended-icons dependency; fallback:
  `Icons.Outlined.Add`), text "Back up" → `onBackupClick`. `testTag("backup-fab")`.
- Grid `contentPadding`: `PaddingValues(top = innerPadding top, bottom =
  innerPadding bottom + 96.dp)` (clears FAB); horizontal stays 0 (edge-to-edge).
- `themes.xml`: DELETE the `android:statusBarColor` item; keep `windowBackground`.
  `enableEdgeToEdge()` stays as-is in MainActivity (auto style is correct).

### Step 3: Grid content — day sub-headers + keys (AUI-10)

Derive day groups natively (UTC, consistent with shared month bucketing) and
build the grid item list per section:

- Month header item (full span, `contentType = "month-header"`, key
  `"month-${section.title}"`) — visual style unchanged (headlineSmall on
  surface @ 0.92) but now sits below the app bar, not under the status bar.
- For each day within the section (`remember(state.sections)` a precomputed
  render model — see Step 4): a full-span `dateSubhead` item: `titleSmall`,
  `onSurfaceVariant`, padding 16dp h / 8dp top / 4dp bottom, `contentType =
  "day-header"`, key `"day-<epochDay>-<section.title>"`, format via
  `DateTimeFormatter.ofPattern("EEE, MMM d")` on
  `Instant.ofEpochMilli(userDate).atZone(ZoneOffset.UTC)`. Omit the year (the
  month header carries it).
- Cells: `items(day.items, key = { it.fileId }, contentType = { "cell" })` —
  unchanged keys.

### Step 4: Precomputed render model + fixed sticky header (AUI-03, PERF-06, AUI-12 partial)

Replace `topVisibleSectionTitle`'s per-frame linear scan with a prefix-indexed
model, computed once per `state.sections` change:

```kotlin
/** Flattened grid rows + prefix index: grid item index -> owning month title,
 *  and whether that index IS a month-header item. */
private class TimelineRenderModel(sections: List<TimelineSection>) { ... }
val model = remember(state.sections) { TimelineRenderModel(state.sections) }
```

Sticky overlay rule (kills the double-draw): compute via `derivedStateOf`:
`val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull()`; overlay is
shown only when `first != null && !model.isMonthHeader(first.index)`; title =
`model.titleFor(first.index)` (O(log n) binary search over the prefix array or
O(1) array lookup — build an IntArray of month-title indices at model
construction). The overlay renders inside the Scaffold content aligned below
the top bar (it must never sit under the status bar).

### Step 5: Cell placeholders + a11y (AUI-06, AUI-07)

- Placeholder painter, remembered per cell:

```kotlin
val placeholder = remember(photo.fileId) { photo.previewPlaceholder?.let { b64 ->
    runCatching {
        val bytes = Base64.decode(b64, Base64.DEFAULT) // android.util.Base64
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            ?.let(::BitmapPainter)
    }.getOrNull()
} }
```

- Fallback for null/undecodable placeholder: deterministic earthy vertical
  gradient behind the image — pick 2 colors by `photo.fileId.hashCode()` from a
  private list of 6 Conservatory-adjacent pairs (e.g. `0xFFD5E0C7→0xFF8FA382`,
  `0xFFE3E2CE→0xFFB9B6A6`, `0xFFEAE6DB→0xFFC9C2AE`, `0xFFDCE5D2→0xFF9AA08C`,
  `0xFFE7E3D7→0xFFAFA893`, `0xFFDFE6D8→0xFF7E806C`), drawn with
  `Modifier.background(Brush.verticalGradient(...))`.
- Pass `placeholder = placeholder, error = placeholder, fallback = placeholder`
  to `AsyncImage` (with the mock's fake ids the fetch fails → the blurry
  placeholder stays, which is the intended demo look).
- Cell semantics: on the clickable Box —
  `semantics { contentDescription = if (photo.isVideo) "Video, $dateLabel" else "Photo, $dateLabel"; role = Role.Button }`
  where `dateLabel` = "MMM d, yyyy" of userDate (UTC). Month header gets
  `semantics { heading() }`. Video badge Icon keeps `contentDescription = null`
  (the cell label already says Video).

### Step 6: Pagination + pull-to-refresh (AUI-08/PERF-11, AUI-09)

- Trigger with prefetch margin:

```kotlin
val shouldLoadMore by remember(model) { derivedStateOf {
    val info = gridState.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
    last >= info.totalItemsCount - model.columns * 4
} }
LaunchedEffect(shouldLoadMore, state.endReached, state.isPaginating) {
    if (shouldLoadMore && !state.endReached && !state.isPaginating) onLoadMore()
}
```

- Footer item (full span, 48dp): small `CircularProgressIndicator` when
  `state.isPaginating`, `testTag("timeline-footer-loading")`.
- Pull-to-refresh: wrap grid content in material3 `PullToRefreshBox(
  isRefreshing = state.isLoading && state.sections.isNotEmpty(),
  onRefresh = onRefresh)`.

### Step 7: MainActivity wiring (AUI-01 events, AUI-04 seam)

- Collect events → snackbar: inside `setContent`, `LaunchedEffect(vm) {
  vm.events.collect { event -> when (event) { is TimelineEvent.Error ->
  snackbarHostState.showSnackbar(event.message) } } }` — hoist a
  `SnackbarHostState` and pass it into the screen's Scaffold (simplest: move
  the stateful overload's Scaffold ownership so the Activity supplies only
  callbacks; keep the stateful overload thin).
- `onRefresh = vm::refresh`, `onLoadMore = vm::loadMore`, `onRetry = vm::refresh`,
  `onBackupClick = { snackbar "Backup arrives with login — next batch." }`,
  `onPhotoClick = { /* viewer: plan 004 */ }`.

### Step 8: Coil hardening (PERF-07)

In `buildHomebaseImageLoader`, mirror chat-kmp:

```kotlin
.memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
.diskCache(null)          // decrypted bytes must NEVER hit a plaintext disk cache
.crossfade(true)
.build()
.also { SingletonImageLoader.setSafe { _ -> it } }
```

Imports: `coil3.SingletonImageLoader`, `coil3.memory.MemoryCache`,
`coil3.disk` NOT needed. Keep the existing components block unchanged.

### Step 9: Compose UI tests

Update `TimelineScreenTest.kt` (stateless overload, no imageLoader — existing
pattern). Keep existing header/cell assertions compiling; add:
- empty state: state with no sections, `isLoading=false` → `timeline-empty` exists.
- skeleton: `isLoading=true`, no sections → `timeline-skeleton` exists.
- error state: `error != null`, no sections → `timeline-error` + "Try again".
- day sub-header: seeded state (two items same day) → `timeline-day-header` tag exists.
- loadMore: small viewport state with > 1 screen of cells; scroll to end →
  `onLoadMore` callback invoked (assert via a `var called = false` lambda).
- overlay-at-top: at rest, exactly ONE node with tag `timeline-month-overlay`
  does NOT exist (the overlay is hidden while the real header is topmost) —
  give the overlay its own tag distinct from in-grid headers.

## Test plan

See Step 9 — model after the existing `TimelineScreenTest.kt` style
(createComposeRule + PhotosTheme wrapper + testTag lookups). Verifier:
`./gradlew :androidApp:connectedDebugAndroidTest` on Pixel_9.

## Done criteria

- [ ] All four states render (skeleton / empty / error / grid) with their testTags.
- [ ] Top bar + FAB present; grid top-inset correct; themes.xml statusBarColor gone.
- [ ] Overlay header hidden when a real month header is the first visible item.
- [ ] Day sub-headers render between month header and cells.
- [ ] Cells show placeholder/gradient (never a bare flat square), have semantics labels.
- [ ] loadMore fires near end; isPaginating footer shows; PullToRefreshBox wired.
- [ ] Coil: memory cache 25%, diskCache(null), singleton installed.
- [ ] `TimelineScreenTest` compiles with new + existing assertions.
- [ ] No files outside scope modified.

## STOP conditions

- Any "Current state" excerpt mismatches the live file.
- `PullToRefreshBox` or `TopAppBar` API not found in the bundled material3
  (would mean the BOM assumption is wrong) — report, do not add dependencies.
- A needed material icon doesn't exist in icons-core and the listed fallbacks
  also fail — report which.
- You want to edit shared/ or theme files.

## Maintenance notes

- Plan 004 (viewer) replaces the `onPhotoClick` no-op and should reuse the
  memory-cached grid thumbnail as the viewer placeholder (Coil keyer is
  size-independent — see chat-kmp `HomebaseImage.kt` `placeholderMemoryCacheKey`).
- Pinch-to-zoom (Plan 005) will hoist `columns` into mutable state — the
  `TimelineRenderModel` already isolates index math, keep it that way.
- Reviewer scrutiny: the sticky-overlay hide rule (off-by-one on header index)
  and inset handling on a notched device.
