# Plan 004 (rev 2): Fullscreen viewer — progressive HomebaseImage loading on both platforms

> **Executor instructions**: WRITER mode — no builds (single verifier afterward);
> self-review. In-scope files only. STOP conditions binding. No commits.
> The tree is uncommitted; verify excerpts against live code, STOP on real mismatch.
> Rev 2 (2026-07-04): rewritten post-auth/real-photos — the ORIGINAL rev's
> "Current state" is obsolete; this rev is the truth.

## Status

- **Priority**: P1 (owner-escalated: "I don't see high-res photos")
- **Effort**: L
- **Risk**: MED
- **Depends on**: 001–003, 006–009 all DONE (real photos render in the grid on both devices)
- **Category**: feature (spec §5.3 viewer, MVP scope)
- **Planned at**: commit `86e57a2`, 2026-07-04

## Why this matters

The grid correctly shows the 225×300 thumbnail class, but tapping a photo does
nothing — there is no way to see a photo at high resolution. chat-kmp's quality
bar here is its `HomebaseImage` progressive pattern: frame-0 paints the ALREADY
CACHED grid thumbnail (size-independent cache key), then the sharp 900×1200
preview crossfades in. MVP scope: pager viewer + progressive load + chrome
toggle + dismiss. NO pinch-zoom, NO share/delete/info bar, NO original-payload
streaming (900×1200 is the hi-res class for phone screens; original + zoom is a
recorded follow-up), NO shared-element transition.

## Current state (verified; re-read each before editing)

- Shared (do NOT modify shared/ in this plan): `TimelineUiState.pagedItems:
  List<PhotoItem>` is the flat pager list. `PhotoItem` now carries fileId,
  userDate, isVideo, previewPlaceholder, driveId, payloadKey, keyHeader?,
  isEncrypted, payloadContentType, lastModified, thumbSizes (plan 009).
  Server thumb classes for photos: ~20px inline, 225×300, 900×1200.
- Android:
  - `MainActivity.kt` — root gate `when(authState)`; Authenticated branch renders
    `TimelineScreen(state=..., onPhotoClick = { /* viewer: plan 004 */ }, ...)`
    with a remembered `vm`, `imageLoader`, `snackbarHostState`.
  - `CoilSetup.kt` — `buildHomebaseImageLoader` (Keyer+Fetcher, memoryCache 25%,
    diskCache(null), singleton-installed) and `homebaseImageData(photo, requestedSize)`
    building the full-fidelity `HomebaseImageData` (post-009).
  - `TimelineScreen.kt` — grid requests `GRID_THUMB_SIZE = ImageSize(225, 300)`.
  - The copied `HomebaseImageKeyer` (shared `id.homebase.core.image`) came from
    chat-kmp where thumbnail cache keys are SIZE-INDEPENDENT (a grid thumb can
    seed a viewer request via `placeholderMemoryCacheKey`). READ the keyer to
    confirm and to learn the exact key derivation; chat-kmp exemplar:
    `chat-kmp/homebase-common/src/commonMain/kotlin/id/homebase/core/image/HomebaseImage.kt`
    (`.placeholderMemoryCacheKey(HomebaseImageKeyer.thumbnailCacheKey(data))`).
- iOS:
  - `RootView.swift` — gate; timeline case renders `TimelineView()`.
  - `TimelineView.swift` — `PhotoCell(item:, onTap:)` exists with a no-op tap
    parameter wired via `.onTapGesture`; `TimelineModel` owns the VM
    (`model.uiState?.pagedItems`).
  - `ThumbnailLoader.swift` — actor, NSCache keyed `"\(fileId)-\(maxDim)"`,
    `image(for:maxDim:)`; grid uses maxDim 300. A viewer request at maxDim 1200
    is a separate cache entry; the 300 entry is ALREADY CACHED for visible cells
    → frame-0 for the viewer.
  - Theme tokens: `PhotosColor.scrim/overlayChrome/onOverlay/onOverlayDim`,
    `PhotosFont.captionOverlay`, `PhotosMetrics.*`.
- Design: `docs/design/design-system.md` §5.3 (scrim, pager, chrome gradients,
  immersive default-after-3s, video poster + play glyph).

## Scope

**In scope**:
- Android: `androidApp/src/main/kotlin/id/homebase/photos/android/ui/viewer/ViewerScreen.kt` (create),
  `MainActivity.kt` (hold viewer index state + BackHandler wiring),
  `TimelineScreen.kt` ONLY if the onPhotoClick plumbing needs a signature touch (it shouldn't),
  `androidApp/src/androidTest/kotlin/id/homebase/photos/android/ui/viewer/ViewerScreenTest.kt` (create).
- iOS: `iosApp/iosApp/viewer/ViewerView.swift` (create),
  `iosApp/iosApp/timeline/TimelineView.swift` (wire onTap → fullScreenCover),
  `iosApp/iosApp/timeline/TimelineModel.swift` (selected-photo state if needed),
  `iosApp/iosAppUITests/ViewerUITest.swift` (create).

**Out of scope**: shared/** (everything needed exists), CoilSetup.kt internals
(you may CALL `homebaseImageData`), theme files, zoom/share/delete/info,
original-payload loading, shared-element transitions, project.yml.

## Steps — Android

### A1: `ViewerScreen.kt`

```kotlin
@Composable
fun ViewerScreen(
    items: List<PhotoItem>,
    initialIndex: Int,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- Full-screen `Box` over `PhotosTheme.extended`-scrim color (design §5.3;
  the scrim token — near-black warm), `testTag("viewer-root")`.
- `HorizontalPager(state = rememberPagerState(initialPage = initialIndex) { items.size },
  beyondViewportPageCount = 1)` (androidx.compose.foundation.pager — already in
  the foundation artifact; verify import compiles, STOP if the pager API is absent).
- Each page — the PROGRESSIVE core:

```kotlin
val gridThumb = homebaseImageData(photo, ImageSize(225, 300))   // same params the grid used
val hiRes    = homebaseImageData(photo, ImageSize(900, 1200))
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data(hiRes)
        .placeholderMemoryCacheKey(/* the keyer's key for gridThumb — read HomebaseImageKeyer;
            if it exposes a helper (e.g. thumbnailCacheKey / key(data)) use it;
            else construct MemoryCache.Key(keyString) from the same derivation the Keyer uses */)
        .crossfade(200)
        .build(),
    imageLoader = imageLoader,
    contentDescription = "Photo",   // a11y: date label like the grid cells
    contentScale = ContentScale.Fit,
    modifier = Modifier.fillMaxSize().testTag("viewer-page"),
)
```

  Behind it, the deterministic gradient + decoded `previewPlaceholder` blur (reuse
  the grid's `decodeBlurPlaceholder`; extract to a shared file-private helper is
  fine WITHIN androidApp) so a cold page never flashes black.
- Chrome (toggle on single tap anywhere; auto-hide after 3s via
  `LaunchedEffect(chromeVisible)` + delay): top gradient (`overlayChrome` →
  transparent) with a back arrow (local ImageVector, `onOverlay` tint, 44dp
  target, `testTag("viewer-back")`) and the photo date (`CaptionOverlayTextStyle`,
  `onOverlay`), bottom gradient reserved-empty (actions come later).
- Video items: centered 48dp play triangle (reuse `PlayTriangle` — move/copy the
  vector) over the poster; tapping it snackbars via a callback OR simply ignores
  (leave a `// video playback: T17` comment) — keep it dumb.
- System back: `BackHandler(onBack = onDismiss)`.

### A2: MainActivity wiring

In the Authenticated branch: `var viewerIndex by remember { mutableStateOf<Int?>(null) }`;
`onPhotoClick = { photo -> viewerIndex = state.pagedItems.indexOfFirst { it.fileId == photo.fileId }.takeIf { it >= 0 } }`.
After `TimelineScreen(...)`, overlay: `viewerIndex?.let { idx ->
ViewerScreen(items = state.pagedItems, initialIndex = idx, imageLoader = imageLoader,
onDismiss = { viewerIndex = null }) }`. (Simple full-screen overlay composition —
no nav library; the root gate stays above everything.)

### A3: `ViewerScreenTest.kt` (stateless, mock-item list, no imageLoader network)

ViewerScreen with `imageLoader` built without network use — pass the singleton
loader; requests will fail on fake ids and fall back to placeholder — assert
structure only: viewer-root exists; initial page index respected
(`pagerState.currentPage` via assert on displayed a11y/date if feasible — else
assert viewer-root + viewer-back exist and clicking back invokes onDismiss);
chrome toggles on tap (viewer-back disappears after tap).

## Steps — iOS

### I1: `ViewerView.swift`

```swift
struct ViewerView: View {
    let items: [PhotoItem]
    let initialIndex: Int
    let onDismiss: () -> Void
```

- `TabView(selection: $index) { ForEach over items.indices-free (id: fileId.description) }`
  `.tabViewStyle(.page(indexDisplayMode: .never))` over a `PhotosColor.scrim`
  full-screen background, `.accessibilityIdentifier("viewer-root")`.
- Per-page progressive load (mirror ThumbnailLoader's two-tier cache):
  1. frame-0: `ThumbnailLoader.shared.image(for: item, maxDim: 300)` — for
     visible-cell items this is a SYNCHRONOUS-fast cache hit (await returns
     immediately from NSCache) → show at `.scaledToFit` (upscaled, soft).
  2. concurrently kick `ThumbnailLoader.shared.image(for: item, maxDim: 1200)`
     → on arrival, crossfade (`withAnimation(.easeIn(duration: 0.2))`).
  3. beneath both: gradient + blurred previewPlaceholder (reuse the cell's
     cached-decode helper pattern).
- Single tap toggles chrome (top `overlayChrome` gradient: X/dismiss button
  `.accessibilityIdentifier("viewer-close")` + date `PhotosFont.captionOverlay`
  on `onOverlay`); auto-hide after 3s (`Task.sleep`).
- Drag-down > 120pt dismisses (simple `DragGesture` offset + call onDismiss;
  no interactive scaling needed for MVP).
- Video: centered `play.fill` 48pt `onOverlay` over the poster (non-functional,
  `// video playback: T17`).

### I2: Timeline wiring

`TimelineModel`: `@Published var viewerIndex: Int?`. `PhotoCell`'s `onTap`
(currently `{}`) → set `model.viewerIndex` from `pagedItems` by fileId. In
`TimelineView`: `.fullScreenCover(isPresented: Binding(get: { model.viewerIndex != nil }, ...))
{ ViewerView(items: model.uiState?.pagedItems ?? [], initialIndex: model.viewerIndex ?? 0,
onDismiss: { model.viewerIndex = nil }) }` (or `fullScreenCover(item:)` with a
small Identifiable wrapper — executor's choice, keep it simple).

### I3: `ViewerUITest.swift`

Launch with `-uiTestTimeline` (bypasses auth; real repo → possibly empty grid in
the test env!). Guard: if no `photo-cell` exists within 10s, SKIP the test body
(XCTSkip) — the flow test is meaningful only with cells. Else: tap first
photo-cell → assert `viewer-root` exists → tap center (chrome toggle) → assert
`viewer-close` appears/disappears → swipe down → viewer-root gone.

## Verifier block (single pass after both writers)

1. `./gradlew :androidApp:assembleDebug :androidApp:compileDebugAndroidTestSources` → green
2. `cd iosApp && xcodegen generate` (new Swift files) then
   `xcodebuild ... build-for-testing` (iPhone 17 sim) → green
3. Sim smoke via argent (udid `8D8741B1-…`): app is AUTHENTICATED with real
   photos — launch, `describe`, tap a real photo-cell's coordinates, screenshot:
   EXPECT the viewer with a sharp (1200-class) image after ~2s; tap again for
   chrome; report what's on screen. Then reinstall fresh APK on Android serial
   `6057f11e` and repeat the tap test there (real photos are synced on it too).

## Done criteria

- [ ] Tap real photo → fullscreen viewer, frame-0 from the cached grid thumb (no black flash), sharp 900×1200 crossfades in on BOTH platforms.
- [ ] Swipe pages; back/swipe-down dismisses; chrome toggles + auto-hides; scrim + overlay tokens only.
- [ ] Video pages show poster + play glyph without crashing.
- [ ] Builds + test-compiles green; sim + Redmi smoke screenshots captured.
- [ ] No shared/ changes; no files outside scope.

## STOP conditions

- `HorizontalPager` or `placeholderMemoryCacheKey` APIs absent in the bundled
  artifacts (report exact availability).
- `HomebaseImageKeyer` keys turn out size-DEPENDENT (breaks thumb-seeding —
  report; fall back to previewPlaceholder blur as frame-0 and note it).
- Any need to touch shared/**.

## Maintenance notes

- Follow-ups recorded: pinch-zoom + original-payload streaming (needs a shared
  `loadOriginalBytes` surface + FullPayloadByteCache pattern from chat-kmp),
  share/delete/info action bar, shared-element transition, month scrubber.
- The viewer accepts an arbitrary item list — albums (T16) reuse it as-is.
