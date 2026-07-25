# UI/UX Redesign — Plan Index (2026-07-25)

Master index for the Google-Photos-class redesign. Each batch below is a **self-contained session**: open its
plan file, run the pipeline, commit, refresh HANDOFF. The master narrative + decisions live in memory
(`ui-redesign-master-plan`) and `HANDOFF.md`.

## Locked decisions
- **Scope:** Google-Photos *core*, **no ML** (no faces/places/map).
- **Nav IA (shipped in Batch A):** Photos · Collections · Create + Search.
- **Fidelity:** fully platform-divergent chrome. **Android** = Material 3 Expressive + Material You. **iOS** = iOS 26 HIG
  / **Liquid Glass** — deployment target is **iOS 26** (18.2 dropped; iOS-26 APIs incl. `BGContinuedProcessingTask` are
  fair game). Grid/viewer *layout* stays GP-shaped on both.
- **Headless is source of truth:** build & FREEZE new `shared` `StateFlow<UiState>` before the native UIs render it.
  Match `chat-kmp` for every protocol format (`chat-kmp-source-of-truth`).
- **Schema gate:** any NEW on-drive format (album writes, favorite/archive/trash flags) needs owner sign-off before
  upload code (`discuss-schema-before-upload`). Batches **C** and **D** are gated.

## Delegation pipeline (every batch)
1. Read this file + the batch file. Confirm the headless contract; if schema-gated, get owner sign-off FIRST.
2. **Shared-headless agent** — new UiState/intents/events/repo methods + **TDD unit tests** (`shared` commonTest/jvmTest).
   Code-only. FREEZE the contract.
3. **Android agent + iOS agent, in parallel** — render against the frozen contract in each OS's idiom; preserve existing
   a11y ids, add new ones; one UI-flow test per screen. Code-only.
4. **Verifier agent** — one pass: `:shared:compileAndroidMain` + `:shared:compileKotlinIosSimulatorArm64` + Android
   `assembleDebug` + `compileDebugAndroidTestKotlin` + iOS `xcodegen` + `xcodebuild build`/`build-for-testing`. Minimal
   build-unblocking fixes only.
5. **Design smoke (Argent):** Android on the Redmi (serial `6057f11e`, real photos), iOS on an iPhone 17 / iOS 26 sim
   (login-gated screens: iOS bypass is the `-uiTestTimeline` launch arg). Side-by-side vs the GP reference.
6. Commit; refresh `HANDOFF.md` (`handoff-update-on-finish`).

## Shared surface today (what you extend)
- `timeline/TimelineViewModel` → `TimelineUiState` (sections, pagedItems, selectedIds, …); intents refresh/loadMore/
  toggleSelection/clearSelection/deleteSelected; events `TimelineEvent(Error|Deleted)`. Repo `PhotosRepository`:
  observePhotos/loadPage/sync/deletePhotos/loadThumbnailBytes.
- `albums/AlbumsViewModel` → `AlbumsUiState(albums)`; `albums/AlbumDetailViewModel` → `AlbumDetailUiState(title,sections,
  photos)`. Repo `AlbumsRepository`: loadAlbums/loadAlbumPhotos — **READ-ONLY**.
- `backup/BackupViewModel`, `auth/LoginViewModel`. iOS resolves each via `photos/PhotosModule.kt` factories; Android via Koin.
- `domain/PhotoItem` (fileId, uniqueId, userDate, isVideo, pixelWidth/Height, previewPlaceholder, driveId, payloadKey,
  payloadContentType, keyHeader, isEncrypted, lastModified, thumbSizes — **no filename/size fields yet**).
  `domain/AlbumItem` (fileId, albumId, name, coverFileId).

## Batch order (dependencies)
| Batch | File | Depends on | Schema gate |
|---|---|---|---|
| **A** Foundation | *(done — committed `fa16d47`)* | — | no |
| **B** Viewer | `2026-07-25-ui-batch-b-viewer.md` | A | no (share/video add repo reads) |
| **C** Collections & albums | `2026-07-25-ui-batch-c-collections.md` | A | **YES** (album writes) |
| **D** Favorites/Archive/Trash | `2026-07-25-ui-batch-d-library-states.md` | A, C | **YES** (flags on files) |
| **E** Search | `2026-07-25-ui-batch-e-search.md` | A | no |
| **F** Memories | `2026-07-25-ui-batch-f-memories.md` | A, B | no |
| **G** Settings & backup | `2026-07-25-ui-batch-g-settings-backup.md` | A | no |

Recommended sequence: **B → C → D → E → F → G**. C before D (favorite/add-to-album buttons in B's action bar light up as
C and D land). B before F (Memories reuses the viewer).

## Cross-batch a11y id policy
Preserve every existing id (Android: `timeline-cell`, `viewer-root`, `viewer-back`, `selection-topbar`, `tab-*`,
`bottom-nav`, `search-button`, …; iOS: `timeline-grid`, `photo-cell`, `viewer-close`, `selection-topbar`, `tab-*`,
`bottom-nav`, …). Each batch file lists the NEW ids it introduces. If a rename is unavoidable, update the UI test in the
same change.
