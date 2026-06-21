# Homebase Photos — Design Spec

**Date:** 2026-06-21
**Status:** Design approved, pending spec review
**Repo:** new standalone `homebase-photos` (sibling to `chat-kmp` in the GitHub folder)

> This spec lives in `chat-kmp` for now because that's where it was authored. On Batch 0 it
> moves to the new `homebase-photos` repo's `docs/`.

## 1. Goal

A native Photos app (Google-Photos-class) built on the Homebase/Odin protocol. Reuses
`chat-kmp`'s `homebase-api` protocol layer (copied + adapted), with **fully native UI**:
SwiftUI on iOS, Jetpack Compose on Android. Ships as an MVP first, then grows in batches.

## 2. Key decisions (locked)

| Decision | Choice | Why |
|---|---|---|
| UI architecture | **True native** — SwiftUI + Jetpack Compose over a headless shared Kotlin layer | Native scroll/memory for the photo grid; the "fully native" requirement |
| Protocol layer | **Copy + adapt** `homebase-api` into the new repo | Independent release cadence; no chat-build coupling. `homebase-api` has zero deps on chat/UI modules, so the copy is clean (drop one chat table) |
| Shared/native boundary | `shared` owns data + domain + **presentation state** (`StateFlow<UiState>`). Native apps are thin View layers | Keeps "true native" from costing 2× — only Views are duplicated, never logic |
| iOS interop | **SKIE** (Touchlab Gradle plugin) | `Flow`→`AsyncSequence`, `suspend`→`async`, sealed→enum. NOT DI |
| DI | **Koin** (as in chat-kmp) | Koin runs inside the framework; iOS calls exposed factories, Android injects normally |
| Android grid | **Jetpack Compose** `LazyVerticalGrid` | Perf gap closed for this case; Material 3 + shared-element transitions; pure Kotlin over the shared StateFlow |
| iOS grid | **SwiftUI** `LazyVGrid` | Symmetric. Drop to `UICollectionView` only if Instruments proves it |
| Perf strategy | Spend the budget on the **image pipeline** (Coil 3), not the layout framework | That's where Google Photos actually wins |
| Format | **Match the existing Odin Photos drive format** (see §4) for fresh files | Proven, compatible; lazier than inventing |
| Legacy files | **Ignored** — we write fresh files, do not migrate/read old ones | Per owner |

### Escape hatch (perf insurance)
If on-device profiling shows the declarative grid can't hold frame budget on a low-end device,
drop **only the grid** to `RecyclerView` (Android, via `AndroidView`) / `UICollectionView` (iOS,
via `UIViewRepresentable`), keeping everything else declarative. Build this **only when a profiler
says so**, never on spec.

### Drift mitigation (copied protocol layer)
The copied `homebase-api` will drift from upstream. Record the **upstream commit hash** in the
copied module's README so re-syncing security/sync fixes (the `improve` backlog flags weak crypto
RNG + keys-in-logs in this exact layer) is a mechanical diff, not archaeology.

## 3. Project structure

```
homebase-photos/                 ← new standalone repo
├── shared/                      ← KMP: android + iosArm64 + iosSimulatorArm64  →  AAR + xcframework
│   ├── (copied homebase-api: drives, sync, crypto, youauth — minus ChatReadCount.sq)
│   ├── (copied image compression + FFmpeg integration, incl. the iOS serial-queue fix)
│   └── photos/                  ← NEW (see §6)
├── androidApp/                  ← pure Jetpack Compose, depends on :shared
└── iosApp/                      ← pure SwiftUI, links shared.xcframework (SKIE-enhanced)
```

Start with **one** `shared` module (no `-common`/`-core`/`-auth` split) — split only when a seam
forces it.

`shared` ViewModels use `androidx.lifecycle` `ViewModel` (KMP-capable, Compose-free): Android binds
automatically; iOS holds the instance and calls `clear()` on view disappear.

## 4. Data model — match the Odin Photos drive format

**Drive:** `driveType = 2af68fe72fb84896f39f97c59d60813a`, `driveAlias = 6483b7b1f71bd43eb6896c86148668cc`.

Photos app registers as a distinct Homebase app ("Homebase Photos") requesting read/write to this
drive (mirror chat-kmp's YouAuth app-registration request, new GUIDs). Same identity as chat; data
isolated by drive.

### Photo file (canonical, from a real drive file)
| Field | Value |
|---|---|
| `fileType` / `dataType` | `0` / `0` |
| `uniqueId` | UUID — our dedup key (derive deterministically from content hash or device asset id) |
| `userDate` | EXIF capture millis (e.g. `1720359710000`) — timeline sorts by this, NOT upload date |
| `content` | `{ camera{make,model}, captureDetails{exposureTime,fNumber,iso,focalLength,geolocation{latitude,longitude,altitude}}, originalFileName }` |
| `previewThumbnail` | tiny inline webp tagged with **original** pixel dims → instant blur placeholder |
| payload key | `dflt_key` (8 chars, satisfies `^[a-z0-9_]{8,10}$`) — encrypted original bytes, `application/octet-stream` |
| payload `thumbnails` | webp at **15×20** (micro), **225×300** (grid), **900×1200** (fullscreen preview) |
| ACL | `requiredSecurityGroup: owner` |

Original uploaded **byte-for-byte** (Google Photos "Original quality"); reuse chat-kmp's
byte-for-byte upload path. Thumbnails generated locally (copy `ImageUtils`).

### Album / collection file
`fileType = 900`, identified by a **tag** = the album id. Header content holds `{ name, coverFileId }`.
Photo→album membership = the photo carries the album-id in its `tags` (a photo in no album has
`tags: []`). Open an album = `queryBatch(fileType=0, tag=albumId, sort=userDate desc)`.
**Batch-0 verify:** confirm `queryBatch` tag-filtering is wired before relying on it.

### Library-metadata file — DEFERRED
The `fileType 900` file with `content = { yearsWithMonths[], totalNumberOfPhotos, lastCursor }` is
the fast month-scrubber index. MVP computes month headers from the local `DriveMainIndex` directly;
we maintain this aggregate only when the scrubber (Batch 3) needs it.

### Video — extend the same shape
Not in the reference files. Same file shape: `fileType 0`, `dflt_key` = (possibly transcoded) video
bytes, poster frame as the thumbnail. **Pin the photo-vs-video marker in Batch 0** (likely a
`content` flag or contentType check — confirm against `DotYouCore`/`homebase-web` photo source).

## 5. Data flow

**Backup (write):** native picker (PHPicker / Android Photo Picker) → read bytes + EXIF capture date
→ build the photo file (§4) → encrypt → enqueue in existing `Outbox` → `DriveOutboxUploader` uploads
with retry → record `deviceAssetId → fileId` in `KeyValue` (dedup, never double-upload).

**Timeline (read):** `DriveSync` pulls the Photos drive into local `DriveMainIndex` (SQLDelight) →
grid reads from the **local index** (fast, offline), `userDate desc`, paginated → `TimelineViewModel`
exposes `StateFlow<TimelineUiState>` → native grid observes → thumbnails fetched lazily via **Coil 3
+ the encrypted fetcher/decoder copied from chat-kmp**, mapping grid→`225×300`, fullscreen→`900×1200`
→original, placeholder→inline `previewThumbnail`. Month/date headers grouped from `userDate`.

**Viewer:** native swipe-pager over the same paged list; native pinch-zoom.

**Video playback:** native — ExoPlayer/media3 (Android), AVPlayer (iOS). Payloads are encrypted at
rest → decrypt-then-play; copy chat-kmp's encrypted-media playback path.

## 6. `shared/photos/` classes
`PhotoConfig` (drive GUIDs, fileTypes, payload key, thumb sizes) · `PhotoItem` (domain) ·
`PhotosRepository` (query/list/dedup over `homebase-api`) · `BackupService` (pick→encode→outbox) ·
`AlbumRepository` · ViewModels (each `StateFlow<UiState>` + one-time events on `SharedFlow`):
`LoginViewModel`, `TimelineViewModel`, `ViewerViewModel`, `AlbumsViewModel`, `BackupViewModel`.

## 7. Batches

### Batch 0 — skeleton (own batch, non-negotiable)
1. New `homebase-photos` repo; trimmed Gradle + version catalog.
2. `shared` = copied `homebase-api`; drop `ChatReadCount.sq`; strip `fileType 7878/8888` guards; add
   `PhotoConfig`. Compiles → AAR + xcframework.
3. SKIE wired; prove a `StateFlow` + `suspend` fn consume cleanly from a SwiftUI test view.
4. Koin boots in `shared`; Android(Compose) and iOS(SwiftUI) each render one shared `StateFlow`.
5. Image-pipeline harness: copy encrypted Coil fetcher; throwaway screen decoding N encrypted
   thumbnails to validate decode + cache + prefetch.
6. Bare auth: `YouAuthFlowManager` login proven both sides.
7. Verify `queryBatch` tag-filtering; pin the video marker.

### Batch 1 — MVP (in order)
Login UI → timeline grid (Coil tuned; **profiling gate on a low-end device before the batch closes**)
→ fullscreen viewer → manual backup (select & upload) → cross-device restore verified → albums →
**video (last — riskiest slice)**.

### Batch 2 — Auto-backup
Background camera-roll backup (WorkManager on Android; BGTask + PHPhotoLibrary observers on iOS);
backup-status UI; "free up space."

### Batch 3 — Browse & find
Search, month scrubber (introduce the library-metadata aggregate file here), favorites, archive,
trash with restore, multi-select bulk ops.

### Batch 4 — Share & polish
Share albums/photos via Homebase transit, shared libraries, edit (crop/rotate — copy
`image-editor`), faces/places (ML) only if wanted.

## 8. Testing
JVM unit tests in `shared` (reuse chat-kmp patterns) for non-trivial logic — dedup mapping,
album-query building, EXIF date parsing, `PhotoConfig` mapping — each with one runnable self-check.
Grid perf validated on-device via Argent.

## 9. Top risks
1. **SKIE + dual-consumption of the copied layer** → why Batch 0 exists, empty-first.
2. **Encrypted video streaming/playback** → Batch 1 last; copy chat's proven path.
3. **`queryBatch` tag-filtering for albums** → verify in Batch 0.
4. **Grid frame budget on low-end** → profiling gate; declarative-first, imperative grid only if measured.
5. **Large-video upload** (chunking) → confirm `DriveUploadProvider` handles big payloads; may need streaming.
