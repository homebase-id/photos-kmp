# Batch B (Viewer) — FROZEN contracts

Writers: implement EXACTLY these names/signatures. If reality forces a deviation, note it in your
final report — do not silently rename. Parallel writers own disjoint trees: shared writer owns
`shared/`, Android writer owns `androidApp/`, iOS writer owns `iosApp/`. Nobody runs gradle or
xcodebuild — one verifier builds everything afterwards.

## Shared (commonMain)

New `shared/src/commonMain/kotlin/id/homebase/photos/viewer/ViewerViewModel.kt`:

```kotlin
package id.homebase.photos.viewer

data class ViewerUiState(
    val items: List<PhotoItem> = emptyList(),
    val index: Int = 0,
    val isDeleting: Boolean = false,
    val deletedAny: Boolean = false,   // any delete happened this viewer session → hosts refresh on close
) {
    val current: PhotoItem? get() = items.getOrNull(index)
}

sealed interface ViewerEvent {
    data class Error(val message: String) : ViewerEvent
    data object Closed : ViewerEvent   // last item deleted → platform dismisses the viewer
}

class ViewerViewModel(
    items: List<PhotoItem>,
    initialIndex: Int,
    private val repository: PhotosRepository,
) : ViewModel() {
    val state: StateFlow<ViewerUiState>
    val events: SharedFlow<ViewerEvent>          // extraBufferCapacity = 8, tryEmit
    fun setIndex(i: Int)                         // clamps into items.indices; no-op when empty
    fun deleteCurrent()                          // Android fire-and-forget
    suspend fun deleteCurrentAndWait()           // iOS awaits
}
```

Delete semantics (mirror TimelineViewModel's delete): guard `isDeleting`; call
`repository.deletePhotos(listOf(current.fileId))`; on success remove current from `items`, clamp
`index` to the new `lastIndex`, set `deletedAny = true`; if the list became empty emit `Closed`;
on failure/exception emit `Error("Couldn't delete")`.

`PhotosRepository` additions (+ impl in `PhotosRepositoryImpl`, + `MockPhotosRepository`):

```kotlin
suspend fun loadOriginalBytes(item: PhotoItem): ByteArray?   // full-res decrypted payload (stills share/save)
suspend fun prepareVideo(item: PhotoItem): VideoHandle?      // decrypt-to-temp-file; null = can't play
suspend fun disposeVideo(handle: VideoHandle)                // delete the temp file

data class VideoHandle(val filePath: String, val mimeType: String)   // same file, viewer/VideoHandle.kt
```

Impl notes:
- `loadOriginalBytes` → `imageLoader.loadFullPayload(...)` with `loadFullPayload = true`
  `HomebaseImageData` (reuse the private construction in `loadThumbnailBytes`; 48 MiB memo cache
  already coalesces callers). Returns `CachedImage.bytes`.
- `prepareVideo` → `lookupFile(item.fileId)`, per-payload keyHeader via `perPayloadKeyHeader`,
  then `driveFileProvider.streamPayloadDecryptedToPath(driveId, fileId, item.payloadKey, kh,
  outputPath, fileOps)` to `${fileOps.getCacheDirectory()}/viewer_${fileId}.mp4` (extension from
  `payloadContentType`, default mp4). If the file's video descriptor says segmented/HLS
  (`VideoContentResolver.resolveVideoMetadata` on a `VideoPlayerData` built from the header):
  return null. `// ponytail: HLS playback deferred — decrypt-to-temp mp4 only; chat-kmp loopback path if needed`
- Mock: `loadOriginalBytes` = deterministic small bytes, `prepareVideo` = null.

`PhotosModule.kt` (top-level, shared writer owns):

```kotlin
fun viewerViewModel(items: List<PhotoItem>, initialIndex: Int): ViewerViewModel
suspend fun loadOriginalBytes(item: PhotoItem): ByteArray?
suspend fun prepareVideo(item: PhotoItem): VideoHandle?
suspend fun disposeVideo(handle: VideoHandle)
```

`PhotosModuleIos.kt` (nativeMain, NSData single-memcpy like `loadThumbnailData`):

```kotlin
suspend fun loadOriginalData(item: PhotoItem): NSData?
```

Tests (commonTest `id/homebase/photos/viewer/ViewerViewModelTest.kt`, kotlin.test + runTest, event
collection via test-scope `launch` + `advanceUntilIdle()` BEFORE acting, cancel at end — see
`TimelineDeleteTest`): index clamping (setIndex out of range, negative); delete removes current +
clamps index when deleting the last element; delete of the only item empties list + emits
`Closed`; failed delete emits `Error` and mutates nothing; `deletedAny` flips once. Use
`MockPhotosRepository` or a tiny local fake for the failure case. If wiring
`streamPayloadDecryptedToPath` under ktor-client-mock is cheap, add a `prepareVideo` round-trip
reusing `PerPayloadKeyHeaderTest` fixtures; otherwise test only the HLS-null branch with a fake.

## Android (Compose, Material 3 Expressive) — consumes the above

- `ViewerScreen` becomes VM-driven: `viewModel(initializer = { ViewerViewModel(items, initialIndex, koin.get()) })`;
  pager index ↔ `setIndex`. On dismiss, if `state.deletedAny`, the host grid must refresh
  (extend the AppShell `viewerBridge` with an `onClosed(deletedAny: Boolean)` callback; Timeline/
  AlbumDetail pass their VM's `refresh()`).
- Bottom action bar over the scrim, auto-hides with existing chrome timer: Share · Delete · Info.
  NO favorite / add-to-album buttons (Batches D/C).
- Delete → reuse `DeleteConfirmDialog` — MOVE it from `TimelineScreen.kt` to
  `ui/components/DeleteConfirmDialog.kt` (internal → public) and reuse from both call sites (DRY,
  owner directive).
- Info: `ModalBottomSheet` — date/time (userDate), dimensions, type (payloadContentType), video?,
  lastModified. No filename/byte-size (not on PhotoItem — deliberate omission).
- Zoom: reusable `ui/components/Zoomable.kt` `Modifier.zoomable(...)` (pinch 1x..~5x, double-tap
  toggle, pan when zoomed); pager `userScrollEnabled = !zoomed`.
- Video: `ui/viewer/VideoPlayerPage.kt` — media3 ExoPlayer + `PlayerView`/Compose `PlayerSurface`,
  fed by `prepareVideo` file path; play/pause + position via chrome; release + `disposeVideo` on
  page change/dispose. Add catalog deps `androidx-media3-exoplayer` + `androidx-media3-ui` to
  `androidApp/build.gradle.kts` (already in libs.versions.toml; skip hls artifact).
- Share: write bytes (still: `loadOriginalBytes`; video: the prepared temp file) into
  `cacheDir/share/`, expose via `FileProvider` (new manifest provider + `res/xml/file_paths.xml`),
  `ACTION_SEND` with the content URI + mime.
- testTags: keep `viewer-root`/`viewer-page`/`viewer-back`; add `viewer-actionbar`,
  `viewer-share`, `viewer-delete`, `viewer-info`, `viewer-info-sheet`, `viewer-video-surface`.
- androidTest: open viewer → action bar visible → info sheet shows → delete (confirm via
  `delete-confirm`) removes current. Follow `AppShellNavTest` harness.

## iOS (SwiftUI, iOS 26 — Liquid Glass allowed, deploy target 26.0) — consumes the above

- New `viewer/ViewerModel.swift`: `@MainActor final class ViewerModel: ObservableObject` wrapping
  `PhotosModuleKt.viewerViewModel(items:initialIndex:)`; SKIE observation exactly like
  `TimelineModel` (capture `vm.state`/`vm.events` outside the Task, `[weak self]`, cancel in
  deinit). `ViewerEventClosed` → `router.closeViewer()`.
- `ViewerView` rework: keep pager/chrome/drag-dismiss; bottom action bar (glass material) with
  `square.and.arrow.up` / `trash` / `info.circle` — NO favorite/add-to-album. Delete →
  confirmation alert (same copy as timeline) → `await model.deleteCurrent()`.
- Info: `.sheet` with a `List`: date/time, dimensions, type, video?, lastModified.
- Zoom: `MagnifyGesture` + `DragGesture` + double-tap toggle on the still page; while zoomed,
  disable `TabView` paging and the swipe-down dismiss.
- Video: `AVPlayer` + `VideoPlayer` from `try await PhotosModuleKt.prepareVideo(item:)` path
  (`URL(fileURLWithPath:)`); pause/dispose + `PhotosModuleKt.disposeVideo(handle:)` on page
  change/dismiss. Show spinner while preparing; `prepareVideo == nil` → small "Can't play this
  video" state.
- Share: stills `PhotosModuleIosKt.loadOriginalData(item:)` → `UIActivityViewController` (or
  `ShareLink` with the data); video → prepared temp-file URL.
- On viewer close with `deletedAny`: `NotificationCenter` post (`Notification.Name("hbPhotosChanged")`,
  define once in a shared Swift file, e.g. components/) — `TimelineModel` + `AlbumDetailModel`
  observe → `vm.refresh()`.
- a11y ids: keep `viewer-root`/`viewer-close`; add `viewer-actionbar`, `viewer-share`,
  `viewer-delete`, `viewer-info`, `viewer-info-sheet`, `viewer-video`.
- Update `ViewerUITest.swift`: action bar present, info sheet opens, delete flow (skip when grid
  empty, as today).
- Reuse/extend `iosApp/iosApp/components/` before writing anything new (DRY, owner directive).
