# UI Redesign — Batch F: Memories / For-you

**Depends on:** A, B (reuses the viewer). **Schema gate:** no. Delegation: shared-headless → Android + iOS → verifier.

## Goal
A **date-derived** Memories surface (no ML): a timeline-top carousel of memory cards ("N years ago today", "This month
in 2024") and a full-screen, auto-advancing **memory player**. All computed from existing `PhotoItem.userDate` — no new
storage.

## Headless contract (build & FREEZE first)
- `MemoriesViewModel` → `MemoriesUiState`: `memories: List<Memory>` where `Memory(title: String, subtitle: String,
  cover: PhotoItem, items: List<PhotoItem>)`. intents: `refresh()`.
- Derivation in `PhotosRepository.loadMemories(today)` (or a use-case): group photos whose `userDate` month+day matches
  the current month+day in prior years ("N years ago"), plus a "this month, past years" bucket. Cap per-memory item count.
  Query the local `OdinDatabase` — no network, no aggregate file. Pass `today` in (Kotlin `Clock`/platform now) — keep the
  function pure over an injected timestamp so it's testable.
**TDD (shared):** "3 years ago today" grouping from fixed fixtures; empty when no matching dates; per-memory cap; stable
ordering (newest year first).

## Android (Compose, Material 3 Expressive)
- Memories **carousel** pinned at the top of the Photos timeline (horizontal `LazyRow` of rounded cover cards with title
  overlay) — collapses/hides when empty. Tapping a card opens the **memory player**: full-screen, auto-advancing pages
  (reuse `ViewerScreen`'s pager + progressive load), tap to pause, swipe for prev/next, top progress segments.
- New ids: `memories-carousel`, `memory-card`, `memory-player`, `memory-player-close`.

## iOS (SwiftUI, iOS 26)
- Carousel as a top section of the Photos tab (`ScrollView(.horizontal)` of cards). Memory player = full-screen cover
  reusing `ViewerView` infra with an auto-advance timer + segmented progress bar (story style), tap-to-pause.
- New ids mirror Android (`memories-carousel`, `memory-card`, `memory-player`, `memory-player-close`).

## Tests
Shared unit (above). UI-flow per platform: with seeded dated photos, the carousel shows ≥1 memory; tapping opens the
player; close returns to Timeline.

## Verify
Compile all; shared unit + UI-flow green. Argent on the Redmi (real photos with history): carousel renders, player
auto-advances and closes. iOS same on sim.

## Risks / deferrals
- No ML "best photo" curation — cover = first/most-recent of the bucket. No music/effects. Keep the player lightweight to
  hold the grid frame budget (the carousel adds a top row to the timeline — verify scroll perf on the Redmi).
