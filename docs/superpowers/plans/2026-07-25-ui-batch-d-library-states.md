# UI Redesign — Batch D: Favorites · Archive · Trash

**Depends on:** A, C. **Schema gate:** **YES — favorite/archive/soft-delete flags are new on-drive state; owner sign-off
before any upload.** Delegation: shared-headless → Android + iOS → verifier.

## Goal
Three library states + screens: **Favorites**, **Archive** (hidden from the main timeline), **Trash** (soft-delete with
restore + permanent delete). None exist today (no VMs, no repo methods, no flags).

## Schema sign-off FIRST (the crux of this batch)
Decide, with the owner + `chat-kmp` (`chat-kmp-source-of-truth`), how each state is represented on the Odin file:
- **Favorite / Archive:** tags on the photo file (a `favorite` tag, an `archive` tag) vs. a metadata field. Prefer tags
  if that's how Odin/GP-on-Odin models it — cheap to query via `queryBatch` tag filter (already used for albums).
- **Trash:** soft-delete. Determine whether Odin has a native archival/deleted state or whether trash = a `trash` tag +
  a purge policy. Define restore (clear flag) and permanent delete (the existing hard `deletePhotos`). Auto-purge window
  (e.g. 60 days) is a client policy note, not necessarily enforced server-side.
Record the pin. **No upload/flag-write code until signed off** (`discuss-schema-before-upload`).

## Headless contract (build & FREEZE first)
`PhotosRepository` additions (+ impl, TDD):
- `setFavorite(fileId, Boolean)`, `setArchived(fileId, Boolean)`
- `softDelete(fileIds)` / `restore(fileIds)` / `permanentDelete(fileIds)`
- queries: `observeFavorites()`, `observeArchived()`, `observeTrash()`; **and the main timeline query must now EXCLUDE
  archived + trashed** (update `observePhotos()` filter — regression-test that archived/trashed items vanish from Timeline).
ViewModels: `FavoritesViewModel`, `ArchiveViewModel`, `TrashViewModel` (each a month-sectioned grid like Timeline; reuse
`groupIntoMonthSections`). Trash VM adds `restoreSelected()` / `permanentDeleteSelected()`. Wire the **favorite** toggle
into `ViewerViewModel.toggleFavoriteCurrent()` (the B slot) and add a favorite action to Timeline selection.
**TDD (shared):** flag write→query round-trip; timeline excludes archived+trashed; restore re-includes; permanent delete
removes; month-section reuse.

## Android (Compose)
- Reuse the timeline grid + selection for all three screens. Favorites: heart badge on favorited cells. Archive: reached
  from Collections library row; its items are hidden from Photos. Trash: selection bar shows **Restore** + **Delete
  forever**; a header note "Items are deleted after N days."
- New ids: `favorites-grid`, `archive-grid`, `trash-grid`, `trash-restore`, `trash-delete-forever`, `favorite-toggle`.

## iOS (SwiftUI, iOS 26)
- Same three grid screens as `Router` destinations off Collections. Trash selection toolbar = Restore + Delete Forever.
  Favorite = `heart`/`heart.fill` toggle in the viewer + timeline selection.
- New ids mirror Android.

## Tests
Shared unit (above, the timeline-exclusion tests are critical). UI-flow per platform: favorite a photo → shows in
Favorites; archive → gone from Timeline, present in Archive; delete → in Trash → restore → back in Timeline.

## Verify
Compile all; shared unit + UI-flow green. Argent on the Redmi: favorite/unfavorite, archive round-trip (confirm it leaves
the main grid), trash → restore and trash → delete-forever. iOS same on sim. Confirm cross-device: a flag set on one
device reflects on the other after sync (owner-assisted).

## Risks / deferrals
- **Biggest risk = the schema.** Getting favorite/archive/trash representation wrong desyncs or corrupts the drive — this
  is why the gate exists. Auto-purge enforcement and shared-trash semantics are deferred.
