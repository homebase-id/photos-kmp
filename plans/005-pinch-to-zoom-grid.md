# Plan 005: Pinch-to-zoom grid density (deferred)

> Deferred stub — findings AUI-11 / design §4.4. Not scheduled for the current
> wave; expand into a full plan before execution.

## Status

- **Priority**: P3
- **Effort**: M
- **Risk**: MED (gesture/animation fiddliness on both platforms)
- **Depends on**: plans/002, plans/003
- **Category**: direction
- **Planned at**: commit `86e57a2`, 2026-07-04

## Sketch

Google-Photos pinch steps the column count between §4.4 breakpoints (phone:
2⇄3⇄4⇄6), animating cell size. Both platforms hoist `columns` from the
width-only derivation into user-adjustable state seeded by `columnsFor(width)` /
`timelineColumns(forWidth:)`:

- Android: `Modifier.pointerInput` / `detectTransformGestures` accumulating a
  zoom factor; crossing ±~25% steps the column count; `animateDpAsState` on the
  cell size (or `Modifier.animateItem()` on grid items). Persist the chosen
  density in DataStore later (not in scope).
- iOS: `MagnificationGesture` on the ScrollView with the same stepping;
  `withAnimation(.spring)` around the column-count change.
- The Android `TimelineRenderModel` (plan 002) already isolates index math from
  column count — keep pinch changes out of it.

## Open questions for the full plan

- Interaction with pull-to-refresh gesture ownership on iOS.
- Whether the day-header full-span rows re-measure cleanly mid-animation.
- Persisting density choice (DataStore / UserDefaults) — separate follow-up.
