# UI Redesign — Batch B: Full-screen Viewer

**Depends on:** A (nav/router). **Schema gate:** no. **Delegation:** shared-headless → Android + iOS → verifier.

## Goal
Turn the bare MVP viewer (pager + progressive load + tap-to-toggle chrome; video = static glyph, no zoom, no actions)
into a Google-Photos-class viewer: **action bar, info panel, pinch-zoom/pan, and real video playback.**

Today the viewer is a NATIVE pager over `TimelineUiState.pagedItems` / `AlbumDetailUiState.photos` with no ViewModel.
Android `ui/viewer/ViewerScreen.kt` (ids `viewer-root`, `viewer-page`, `viewer-back`); iOS `viewer/ViewerView.swift`
(ids `viewer-root`, `viewer-close`), presented via the shell `Router` (`router.viewer`).

## Sub-features & cross-batch sequencing
- **B1 Action bar + info** — bottom chrome bar + swipe-up info sheet. Ships **share · delete · info** now. **favorite**
  slot is wired but backed by Batch **D**; **add-to-album** slot backed by Batch **C** — show those two buttons only once
  D/C land (feature-flag off until then, or omit and add in C/D). Don't fake them.
- **B2 Zoom/pan** — pinch-zoom, double-tap-to-zoom, pan when zoomed. Pure native, no headless.
- **B3 Video playback** — real playback (riskiest, T17). Copy `chat-kmp`'s proven encrypted-media path.

## Headless contract (build & FREEZE first)
Add a small `viewer/ViewerViewModel` (iOS factory in `PhotosModule.kt`) constructed with the flat photo list + start
index, OR extend the host VMs. It exposes:
- `state: StateFlow<ViewerUiState>` — `items: List<PhotoItem>`, `index: Int`, `current: PhotoItem`, `isDeleting: Boolean`.
- intents: `setIndex(i)`, `deleteCurrent()` (→ `PhotosRepository.deletePhotos([current.fileId])`, then drop from
  `items`, pop if empty), `toggleFavoriteCurrent()` (**stub → wired in D**).
- events `SharedFlow<ViewerEvent>`: `Deleted`, `Error`, `Share(bytes/uri)`.
- `PhotosRepository.loadOriginalBytes(item): ByteArray` (or a streamed handle) — **new**, decrypts the full-res payload
  (per `per-payload-iv`: payload has its own IV). Backs **share** and **video**. Reuse the encrypted fetch path Coil
  already uses (`HomebaseImageLoader` fetcher) — don't reinvent decryption.
- **Video:** a decrypt-to-temp-file or streamed source the platform player can consume. Confirm `DriveUploadProvider`/
  download path handles large payloads; may need chunked/streamed decrypt (mirror chat-kmp).
- **Info panel** reads existing `PhotoItem` fields (userDate, pixelWidth/Height, isVideo, payloadContentType,
  lastModified). **Gap:** no filename/byte-size on `PhotoItem` — add if the info panel needs them (else omit).

**TDD (shared):** `deleteCurrent` list-mutation + empty-pop; index clamping on delete; original-bytes decrypt round-trips
(reuse existing decrypt test fixtures).

## Android (Compose, Material 3 Expressive)
- Bottom action bar over the dark scrim: Share, Delete, Info (favorite/add-to-album appear per D/C). Auto-hide with the
  top chrome (existing 3s timer). Delete → confirm dialog (reuse `DeleteConfirmDialog` pattern).
- Info: Material bottom sheet (`ModalBottomSheet`) with date/time, dimensions, type, size. Swipe-up or Info button opens it.
- Zoom: `Modifier.graphicsLayer` + transformable/pointerInput (pinch + double-tap + pan). Disable pager swipe while zoomed.
- Video: ExoPlayer/`Media3` `PlayerView` fed by the decrypted source; play/pause/scrubber in the chrome.
- New ids: `viewer-actionbar`, `viewer-share`, `viewer-delete`, `viewer-info`, `viewer-info-sheet`, `viewer-video-surface`.

## iOS (SwiftUI, iOS 26 / Liquid Glass)
- Bottom action bar as a Liquid-Glass toolbar over the scrim: `square.and.arrow.up` (share via `ShareLink`/
  `UIActivityViewController`), `trash`, `info.circle`. favorite/add-to-album per D/C.
- Info: `.sheet` with a `List`/`Form` of the same fields.
- Zoom: `MagnifyGesture` + `DragGesture` on the page image; double-tap zoom; gate the `TabView` page swipe while zoomed.
- Video: `AVPlayer` + `VideoPlayer` fed by the decrypted temp file (or `AVAssetResourceLoaderDelegate` for streaming).
- New ids: `viewer-actionbar`, `viewer-share`, `viewer-delete`, `viewer-info`, `viewer-info-sheet`, `viewer-video`.

## Tests
- Shared unit (above). One UI-flow per platform: open viewer → info sheet shows → delete removes current + returns.
  Video/zoom are gesture/media — smoke via Argent, not asserted in UI tests.

## Verify
Compile all modules; run shared unit + UI-flow. Argent: on the Redmi open a photo → info → delete → back; open a video →
plays; pinch-zoom works. iOS same on sim.

## Risks / deferrals
- **Encrypted video streaming** is the top risk — copy chat-kmp's path; if streaming is hard, ship decrypt-to-temp first.
- Large-video memory: prefer streamed decrypt over full `ByteArray`.
- favorite/add-to-album are deliberately deferred to D/C — don't ship dead buttons.
