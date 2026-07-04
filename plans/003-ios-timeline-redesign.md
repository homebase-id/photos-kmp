# Plan 003: iOS timeline redesign — VM lifecycle, chrome, states, stable identity, fast bridge

> **Executor instructions**: Follow this plan step by step. You are a WRITER in a
> code-first / batched-verify workflow: **do NOT run xcodebuild, xcodegen, or any
> build command** — a single verifier agent builds everything after all writers
> finish. Self-review each edit instead. Touch only the files listed as in scope.
> If any STOP condition occurs, stop and report — do not improvise. Do NOT
> commit. Do NOT update `plans/README.md`.
>
> **Drift check (run first)**: The source tree is uncommitted; compare the
> "Current state" excerpts below against the live files. On a mismatch, STOP.
>
> **Contract dependency**: This plan codes against Plan 001's API, which a
> sibling writer is producing IN PARALLEL. Do not read half-written shared
> files — code against these signatures (SKIE-exposed to Swift):
> - `TimelineUiState.isPaginating: Bool` (new)
> - `vm.refreshAndWait()` → Swift `async` function (new)
> - `PhotosModuleIosKt.loadThumbnailData(item:maxDim:)` → `async` returning
>   `Data?`/`NSData?` (new, single-memcpy bridge)
> - Mock items now carry `previewPlaceholder` (base64 webp, 20×27).

## Status

- **Priority**: P1
- **Effort**: L
- **Risk**: MED (SwiftUI lifecycle + SKIE surface; XCUITest gates it)
- **Depends on**: plans/001-shared-timeline-contract-prep.md
- **Category**: ui-ux + perf
- **Planned at**: commit `86e57a2`, 2026-07-04 (tree uncommitted — excerpts are ground truth)

## Why this matters

Audit findings IUI-01…IUI-13, PERF-01, PERF-03, PERF-04, PERF-05: the iOS
timeline recreates its ViewModel on every struct re-init (Koin `factory` behind
a plain `let`), gives pull-to-refresh zero feedback, drops all errors, keys
cells by array index (wrong-image flashes on pagination), wraps every cell in a
GeometryReader, re-decodes the base64 placeholder on every body evaluation,
copies thumbnails one byte per Kotlin-interop call, paginates only from the
very last cell, has no top bar / safe-area handling, and is unusable with
VoiceOver. This plan rebuilds the screen to the design-system §5.2 spec.

## Current state

- `iosApp/iosApp/timeline/TimelineView.swift` (206 lines) — key excerpts:

```swift
// :15 — new VM per struct init (Koin factory!)
private let vm = PhotosModuleKt.timelineViewModel()
// :19
@State private var uiState: TimelineUiState?
// :30 — sole state subscription
.task { for await s in vm.state { uiState = s } }
// :78/:81 — index identity
ForEach(sections.indices, id: \.self) { sIdx in ... ForEach(section.items.indices, id: \.self) { iIdx in
// :84 — pagination only on the exact last cell
.onAppear { if sIdx == sections.count - 1, iIdx == section.items.count - 1, !(uiState?.endReached ?? false) { vm.loadMore() } }
// :99
.refreshable { vm.refresh() }   // fire-and-forget → spinner snaps closed
// :134 — GeometryReader per cell; :139 — placeholder decoded in body
GeometryReader { geo in ... Self.placeholderImage(item.previewPlaceholder) ...
```

- `iosApp/iosApp/timeline/ThumbnailLoader.swift` — actor + NSCache(512) +
  in-flight map (KEEP all of that); `:56` calls
  `PhotosModuleKt.loadThumbnailBytes(item:maxDim:)` then `bytes.toData()`;
  `:64-77` the per-byte `KotlinByteArray.toData()` extension (DELETE).
- `iosApp/iosApp/ContentView.swift` — `var body: some View { TimelineView() }`.
- `iosApp/Theme/Theme.swift` — tokens `PhotosColor.*(scheme)` + dynamic
  `PhotosColor.background` etc., `PhotosFont.*`, `PhotosMetrics.*`
  (gridGapWidth 1.5, timelineColumns(forWidth:)). Line 9 has a STALE comment
  "no project membership yet" — the file IS in the target (pbxproj: "Theme.swift
  in Sources").
- `iosApp/iosAppUITests/TimelineGridUITest.swift` — queries
  `app.otherElements["timeline-root"]`; currently fails: the id sits on a
  non-drawing GeometryReader with `.accessibilityElement(children: .contain)`
  and SwiftUI collapses it.
- Project: `xcodegen` (`iosApp/project.yml` globs the `iosApp` dir — new Swift
  files are picked up when the verifier regenerates). SKIE 0.10.12 bridges
  `StateFlow` → `AsyncSequence`, `suspend` → `async/await`.

## Commands you will need

NONE (writer). Verifier runs: `cd iosApp && xcodegen generate` then
`xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator
-destination 'platform=iOS Simulator,name=iPhone 17' build` (+ UI tests).

## Scope

**In scope**:
- `iosApp/iosApp/timeline/TimelineView.swift` (rewrite)
- `iosApp/iosApp/timeline/TimelineModel.swift` (create)
- `iosApp/iosApp/timeline/ThumbnailLoader.swift` (bridge swap + delete toData())
- `iosApp/iosApp/ContentView.swift` (only if needed for NavigationStack hosting)
- `iosApp/Theme/Theme.swift` (ONLY the stale comment line)
- `iosApp/iosAppUITests/TimelineGridUITest.swift` (update assertions)

**Out of scope** (do NOT touch):
- Anything under `shared/` (Plan 001's writer owns it) or `androidApp/`.
- `iosApp/project.yml`, the `.xcodeproj` (generated), `iOSApp.swift`.
- No fullscreen viewer (Plan 004): cells get a tap handler that is a no-op
  closure parameter for now.
- Theme token values.

## Git workflow

None. Working tree only, no commits.

## Steps

### Step 1: `TimelineModel` — one VM per view lifetime (IUI-01)

Create `TimelineModel.swift`:

```swift
import SwiftUI
import Shared

/// Owns the shared TimelineViewModel across SwiftUI struct re-inits.
/// @StateObject guarantees ONE instance per view lifetime; the Koin factory
/// would otherwise mint a new VM (and a new first-page load) on every re-init.
@MainActor
final class TimelineModel: ObservableObject {
    let vm = PhotosModuleKt.timelineViewModel()
    @Published private(set) var uiState: TimelineUiState?

    private var observeTask: Task<Void, Never>?

    func start() {
        guard observeTask == nil else { return }
        observeTask = Task { [weak self] in
            guard let self else { return }
            for await s in self.vm.state { self.uiState = s }
        }
    }

    func collectErrors(_ onError: @escaping (String) -> Void) -> Task<Void, Never> {
        Task { [weak self] in
            guard let self else { return }
            for await e in self.vm.events {
                if let err = e as? TimelineEvent.Error { onError(err.message) }
            }
        }
    }

    deinit { observeTask?.cancel() }
}
```

In `TimelineView`: `@StateObject private var model = TimelineModel()`, call
`model.start()` from `.task`, read `model.uiState`. (SKIE exposes `events` as an
AsyncSequence like `state`; the sealed `TimelineEvent.Error` arrives as a class
with `.message`. If the SKIE name differs — e.g. `TimelineEventError` — adapt
the cast only.)

### Step 2: Chrome + safe area (IUI-06, IUI-02 partial, IUI-08)

- Root becomes `NavigationStack { content }` with `.navigationTitle("Photos")`
  + `.navigationBarTitleDisplayMode(.large)`; toolbar trailing item: 28pt
  `Image(systemName: "person.crop.circle")` tinted
  `PhotosColor.onSurfaceVariant(scheme)`, `.accessibilityLabel("Account")`
  (action: no-op for now). Set `.toolbarBackground(PhotosColor.surface(scheme), for: .navigationBar)`.
- Screen background `PhotosColor.background(scheme)` everywhere (including
  behind the large title).
- Sticky `MonthHeader` background: `.background(.ultraThinMaterial)` overlaid
  with `PhotosColor.surface(scheme).opacity(0.6)` tint (real frosted blur —
  design §4.4 — instead of the flat 0.92 fill).
- Root a11y: put `.accessibilityIdentifier("timeline-root")` +
  `.accessibilityElement(children: .contain)` on a CONCRETE drawn container —
  a root `ZStack` that includes the background color — NOT on a GeometryReader
  (IUI-12; this is what the failing XCUITest needs).

### Step 3: States (IUI-04, IUI-07)

Branch on `model.uiState`:
- `nil` or (`isLoading` && sections empty) → skeleton: the same `LazyVGrid`
  filled with `columns * 12` squares of `PhotosColor.gridGap(scheme)`,
  `.accessibilityIdentifier("timeline-skeleton")`. No ProgressView.
- error non-nil && sections empty → error state (distinct from empty! today a
  failed first load shows "No photos yet"): `PhotosFont.display` "Couldn't load
  photos", `bodyMedium` message, "Try again" button (primary fill, radiusXl)
  calling `model.vm.refresh()`. `.accessibilityIdentifier("timeline-error")`.
- sections empty → existing empty state (keep, it matches §5.2), id "timeline-empty".
- else → grid.
- Transient errors while content exists: collect via `model.collectErrors` in a
  `.task`; show a bottom capsule toast (surface3 bg, onSurface text, auto-hide
  ~4s via `Task.sleep`). `.accessibilityIdentifier("timeline-toast")`.

### Step 4: Grid — stable identity + day sub-headers + prefetch (IUI-05/PERF-03, AUI-10 parity, PERF-05)

- `ForEach(sections, id: \.title)` and inside each day group
  `ForEach(day.items, id: \.fileId.description)` — never indices. (Kotlin
  `Uuid` bridges as an ObjC class; `.description` is a stable string.)
- Day groups: derive per section in the view model layer of the view (pure
  Swift helper, `Calendar(identifier: .gregorian)` with `TimeZone(identifier:
  "UTC")!` — UTC to match shared month bucketing): `[(dayTitle, [PhotoItem])]`,
  title via `DateFormatter` template "EEE, MMM d". Render as a plain full-width
  `Text(PhotosFont.dateSubhead, PhotosColor.onSurfaceVariant)` row, padding
  16 h / 8 top / 4 bottom, `.accessibilityIdentifier("timeline-day-header")`.
  Compute day groups ONCE per sections change — hold them in the model:
  `@Published var daySections: [DaySection]` recomputed in `start()`'s loop
  when `uiState` updates (avoid re-grouping in `body`).
- Prefetch margin: precompute in the model a `Set<String>` of the fileId
  descriptions of the LAST `columns * 4` items of `pagedItems`; in the cell's
  `.onAppear`, if the set contains the item id and `!endReached && !isPaginating`,
  call `vm.loadMore()`. Footer: when `isPaginating`, a full-width 48pt row with
  a small `ProgressView` at the grid bottom.

### Step 5: Cell rebuild (IUI-09/PERF-04, IUI-11)

Replace the GeometryReader cell:

```swift
struct PhotoCell: View {
    let item: PhotoItem
    ...
    var body: some View {
        ZStack {
            fallbackGradient            // deterministic 2-stop earthy LinearGradient by fileId hash
            if let ph = placeholder { Image(uiImage: ph).resizable().scaledToFill().blur(radius: 6) }
            if let image { Image(uiImage: image).resizable().scaledToFill().transition(.opacity) }
            if item.isVideo { VideoBadge() }
        }
        .aspectRatio(1, contentMode: .fill)
        .clipped()
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(item.isVideo ? "Video, \(dateLabel)" : "Photo, \(dateLabel)")
        .accessibilityIdentifier("photo-cell")
        .task(id: item.fileId.description) { ... load via ThumbnailLoader ... }
    }
}
```

- NO GeometryReader. `aspectRatio(1, .fill) + .clipped()` inside the grid
  column does the square sizing.
- Placeholder decode caching: a `static let placeholderCache = NSCache<NSString, UIImage>()`
  keyed by fileId; decode base64 → UIImage only on miss, inside the `.task`
  (off the render path), storing into `@State private var placeholder: UIImage?`.
- `fallbackGradient`: pick from 6 hard-coded earthy pairs (same values as
  Android plan 002 Step 5, e.g. `#D5E0C7→#8FA382`, `#E3E2CE→#B9B6A6`,
  `#EAE6DB→#C9C2AE`, `#DCE5D2→#9AA08C`, `#E7E3D7→#AFA893`, `#DFE6D8→#7E806C`)
  by `abs(item.fileId.description.hashValue) % 6`. Use `Color(hex:)` from Theme.swift.
- `dateLabel`: "MMM d, yyyy" (UTC) from `item.userDate`.
- Keep the 0.2s ease-in crossfade on image arrival.

### Step 6: Fast bridge + refresh (PERF-01, IUI-03)

- `ThumbnailLoader.loadDecoded` becomes:

```swift
guard let data = try? await PhotosModuleIosKt.loadThumbnailData(item: item, maxDim: Int32(maxDim)) as Data?
else { return nil }
guard !data.isEmpty, let image = UIImage(data: data) else { return nil }
return image
```

  DELETE the whole `extension KotlinByteArray { func toData() ... }`. (If SKIE
  surfaces the function under a different container name than
  `PhotosModuleIosKt`, search the generated `Shared` interface for
  `loadThumbnailData` and use that — the Kotlin file is
  `shared/src/nativeMain/.../PhotosModuleIos.kt`.)
- `.refreshable { try? await model.vm.refreshAndWait() }` — the spinner now
  holds until sync + reload complete.

### Step 7: Theme comment + UI test

- Delete the stale "no project membership yet" sentence in `Theme.swift:9`.
- `TimelineGridUITest.swift`: keep querying `timeline-root` (it should now
  resolve — Step 2 moved it onto a drawn ZStack); add existence checks for
  `timeline-skeleton` OR `timeline-grid` after launch, and at least one
  `timeline-month-header` and one `timeline-day-header` once cells appear
  (mock data renders placeholders now, so the grid is populated offline).

## Test plan

- XCUITest updates in Step 7 (verifier runs them on iPhone 17 / iOS 26.5 sim).
- All logic added to Swift stays view-local; shared logic tests live in Plan 001.

## Done criteria

- [ ] ONE VM instance across re-renders (`@StateObject` model owns it).
- [ ] NavigationStack top bar; no content under the status bar; frosted sticky headers.
- [ ] Skeleton / empty / ERROR states distinct; transient error toast wired to events.
- [ ] `ForEach` identity by section title + fileId, never indices.
- [ ] Cells: no GeometryReader, cached placeholder decode, gradient fallback, VoiceOver labels.
- [ ] `ThumbnailLoader` uses `loadThumbnailData` (NSData single copy); per-byte extension deleted.
- [ ] `.refreshable` awaits `refreshAndWait()`.
- [ ] Prefetch-margin pagination + isPaginating footer.
- [ ] Stale Theme.swift comment removed; XCUITest updated.
- [ ] No files outside scope modified.

## STOP conditions

- Any "Current state" excerpt mismatches the live file.
- SKIE does not expose `refreshAndWait`/`loadThumbnailData`/`events` in any
  discoverable form (search the Shared module interface first) — report the
  exact missing symbol; do not reimplement the bridge in Swift.
- You want to touch project.yml, shared/, or add a package dependency.

## Maintenance notes

- Plan 004's viewer should reuse `ThumbnailLoader` with `maxDim: 1200` — the
  cache key already distinguishes sizes.
- The model's day-group recompute is O(pagedItems) per emission; if profiling
  ever flags it, move day grouping into the shared VM (contract change).
- Reviewer scrutiny: SKIE symbol names (PhotosModuleIosKt / TimelineEvent.Error
  casts) and that `deinit` cancels the observe task (no leaked AsyncSequence).
