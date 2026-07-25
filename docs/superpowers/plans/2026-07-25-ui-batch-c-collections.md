# UI Redesign — Batch C: Collections & Album Management

**Depends on:** A. **Schema gate:** **YES — album writes need owner sign-off before upload.** Delegation: shared-headless
→ Android + iOS → verifier.

## Goal
Make Collections a real hub and give albums full **create / rename / delete / add-photo / remove-photo / set-cover**.
Today `AlbumsRepository` is **read-only** (`loadAlbums`, `loadAlbumPhotos`); Album detail has no selection.

## Schema sign-off FIRST
Album file already exists as `fileType 900` + a membership tag (read path shipped; data-model §4). Before writing:
confirm with the owner + `chat-kmp` (`chat-kmp-source-of-truth`) the exact **create** payload and the **membership**
mechanism (tag on the photo file vs. album-side list). Match the official Odin Photos album format. Record the pin.
No upload code until signed off (`discuss-schema-before-upload`).

## Headless contract (build & FREEZE first)
Extend `AlbumsRepository` (+ impl) with mutations, mirroring how `PhotosRepository.deletePhotos` uses `DriveFileProvider`:
- `createAlbum(name): AlbumItem`
- `renameAlbum(album, name)`
- `deleteAlbum(album)` (removes the album file; does NOT delete photos)
- `addPhotos(albumId, fileIds)` / `removePhotos(albumId, fileIds)` (membership tag writes)
- `setCover(album, fileId)`
`AlbumsViewModel`: intents `createAlbum(name)`, `rename`, `delete`, `setCover`; refresh after each; error → state/event.
`AlbumDetailViewModel`: add **selection** parity with Timeline — `selectedIds: Set`, `inSelectionMode`, `toggleSelection`,
`clearSelection`, `removeSelected()` (→ `removePhotos`). Add-to-album target for Timeline/Viewer selection: a
`addToAlbum(albumId, fileIds)` intent usable from a picker.
**TDD (shared):** create→tag mapping, add/remove membership round-trip, cover update, delete-album leaves photos intact,
not-found tolerance (mirror `deletePhotos`).

## Android (Compose, Material 3 Expressive)
- **C1 Collections hub:** albums grid (existing `AlbumCard`) + a top **library section** of rows: Favorites, Archive,
  Trash, Utilities (rows navigate to Batch-D screens; show them disabled/"soon" until D lands). Overflow/`+` to create.
- **C2 Album detail:** long-press → selection mode (reuse `SelectionTopBar`); overflow menu: Rename (dialog), Delete
  (confirm), Set as cover (from selection). Remove-from-album from the selection bar.
- **C3 Create:** wire the Batch-A **Create** sheet's "New album" to a real create dialog (name → `createAlbum` → open the
  new album). **Add-to-album:** from Timeline/Viewer selection, an "Add to album" action opens an album-picker sheet
  (existing albums + "New album").
- New ids: `collections-library-row-{favorites,archive,trash,utilities}`, `album-menu`, `album-rename`, `album-delete`,
  `album-setcover`, `album-remove`, `create-album-dialog`, `addto-album-sheet`.

## iOS (SwiftUI, iOS 26 / Liquid Glass)
- **C1:** Collections list = album grid + a `Section` of library rows (Favorites/Archive/Trash/Utilities) as
  `NavigationLink`s. Toolbar `+` / `Menu` to create.
- **C2:** selection via `EditButton`/long-press; toolbar `Menu` (Rename via `.alert` text field, Delete confirm, Set
  cover); remove-from-album from the selection bar.
- **C3:** Create tab / `+` → create sheet (name field). Add-to-album picker `.sheet` from selection.
- New ids mirror Android (`collections-library-row-*`, `album-menu`, `album-rename`, `album-delete`, `album-setcover`,
  `album-remove`, `create-album-dialog`, `addto-album-sheet`).

## Tests
Shared unit (above). UI-flow per platform: create album → appears in grid; open album → select → remove → count drops;
rename reflects in title.

## Verify
Compile all; shared unit + UI-flow green. Argent on the Redmi (real albums account): create, add from timeline selection,
remove, rename, delete. iOS same on sim.

## Risks / deferrals
- Membership mechanism must exactly match Odin/chat-kmp or albums won't sync cross-device — verify in Batch-0 style before
  writing. Utilities row is a placeholder hub (its contents come later). Collage/animation/cinematic Create = out of scope.
