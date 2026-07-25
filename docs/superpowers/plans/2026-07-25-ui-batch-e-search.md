# UI Redesign — Batch E: Search (metadata, no ML)

**Depends on:** A. **Schema gate:** no (reads local index; the aggregate file is optional/local). Delegation:
shared-headless → Android + iOS → verifier.

## Goal
Replace the Batch-A Search placeholder ("Search is coming soon") with a real **metadata** search: by **date range,
type (photo/video), album, folder/source, and filename**. No faces/places/things (ML is out of scope).

## Headless contract (build & FREEZE first)
- `SearchViewModel` → `SearchUiState`: `query: String`, `filters` (dateRange, type, albumId, source), `results:
  List<PhotoItem>` grouped into month sections, `recent: List<String>`, `isSearching`, `error`, empty/idle flags.
  intents: `onQueryChange`, `setFilter`, `submit`, `clearRecent`.
- `PhotosRepository.search(criteria): List<PhotoItem>` — query the **local SQLDelight index** (`OdinDatabase`) already
  populated by DriveSync. Filter by `userDate` range, `isVideo`, album membership (reuse the tag query), and source.
- **Filename gap:** `PhotoItem` has no filename field today. If filename search is wanted, add filename to the projection
  (source it from the drive file metadata) — otherwise ship date/type/album/source and note filename as a follow-up.
- **Library-metadata aggregate file (spec Batch 3):** OPTIONAL for v1 — local DB queries are enough at current scale.
  Introduce the aggregate only if on-device query latency is measured to be too slow on the Redmi. If added, it's a new
  on-drive format → schema gate. Default: **skip it**, query the DB directly. // ponytail: DB query first, aggregate file only if measured slow.
**TDD (shared):** date-range filter, type filter, album filter compose correctly; empty query → idle; recent list capping.

## Android (Compose, Material 3 Expressive)
- Search screen: `SearchBar`/`DockedSearchBar` at top; **filter chips** (Date · Type · Album · Source) opening pickers;
  results as the month-sectioned grid (reuse timeline grid). Idle state = recent searches + quick chips; empty state =
  "No results". Reached from the Batch-A round Search button.
- New ids: `search-field` (exists), `search-results-grid`, `search-chip-date`, `search-chip-type`, `search-chip-album`,
  `search-recent`, `search-empty`.

## iOS (SwiftUI, iOS 26)
- `.searchable` on the Search tab's `NavigationStack`; `.searchScopes`/a filter row for Type; date + album pickers via
  `.sheet`/`Menu`. Results = the month grid. Recent searches list when idle.
- New ids mirror Android (`search-results-grid`, `search-chip-*`, `search-recent`, `search-empty`).

## Tests
Shared unit (above). UI-flow per platform: type a query / pick a date filter → results grid updates; clear → idle with
recents.

## Verify
Compile all; shared unit + UI-flow green. Argent on the Redmi (real photos): filter by date and by type=video, confirm
results; check query latency on the low-end device (the aggregate-file decision hinges on this). iOS same on sim.

## Risks / deferrals
- Filename search deferred unless the field is added. Aggregate-metadata file deferred unless DB search is measurably
  slow. Semantic/ML search explicitly out of scope.
