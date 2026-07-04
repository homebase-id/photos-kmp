# Homebase Photos — Design System

**Status:** Foundation (groundwork). Wired into real screens in later tasks.
**Theme name:** **Conservatory** — earthy green pastel.
**Platforms:** SwiftUI (iOS) + Jetpack Compose (Android), native on both, identical palette in intent (same hex).

---

## 1. Design language (the 30-second version)

Photos are the only saturated thing on screen. Everything else — the chrome, the grid gaps, the
headers, the controls — is a calm, warm, desaturated *conservatory*: muted sage and moss greens over
a warm linen neutral in light mode, and a deep charcoal with a faint green undertone in dark mode.
Nothing competes with the photo. The accent green appears only where the user acts (FAB, selection,
primary button, progress, links). The grid reads as a woven mat of images on a soft warm ground, not
a stark white spreadsheet. Fullscreen is reverent: the world dims to a warm near-black scrim and the
photo fills the frame edge to edge.

This is **not** Material's saturated `Purple40`/`Teal` defaults, and **not** the
Signal-blue (`#2C58C3`) palette of the chat-kmp source we re-skinned. We kept chat-kmp's *structure*
(Material 3 token system, surface-elevation ladder, extended-color seam) and replaced every color.

---

## 2. Color palette

Two principles drive the values:

1. **Chrome recedes.** Backgrounds and surfaces are low-chroma warm neutrals. The eye never lands on
   them — it lands on photos.
2. **Green means "you can act here."** The accent is a single muted moss; it is the only color with
   real chroma in the chrome, and it is rationed.

Hex values are the contract. Both platforms use these exact values.

### 2.1 Light theme (Conservatory Light)

| Role | Hex | Notes |
|---|---|---|
| **background** | `#F4F1EA` | Warm linen. The app's resting ground. Behind the grid. |
| **surface** | `#FBFAF5` | Slightly lighter than background — cards, sheets, top app bar. |
| surfaceVariant | `#E7E4D8` | Inputs, chips, inactive control fills. |
| **surface elevation ladder** | `#F1EEE6` → `#EEEAE0` → `#EAE6DB` → `#E7E3D7` → `#E3DED1` | container-low → highest. Subtle warm steps for stacked sheets/menus. |
| **gridGap** | `#E9E5DB` | The mat between thumbnails. Warmer/darker than surface so the grid reads as woven, never as gaps "missing." |
| **primary / accent** | `#5E7A52` | Muted moss. FAB, primary button, selection, links, active tab. |
| onPrimary | `#FFFFFF` | Text/icon on the moss accent. |
| primaryContainer | `#D5E0C7` | Pastel sage. Tonal button bg, selected-tab pill, info banner. |
| onPrimaryContainer | `#1B2815` | Deep forest. Text on the pastel sage container. |
| secondary | `#7A7C5E` | Muted olive. Secondary chips, metadata accents. |
| onSecondary | `#FFFFFF` | |
| secondaryContainer | `#E3E2CE` | Pale olive container. |
| onSecondaryContainer | `#24251A` | |
| **onSurface** | `#23271F` | Primary text/icon. Warm near-black with green cast — never pure `#000`. |
| onSurfaceVariant | `#5A604F` | Secondary text, captions, inactive icons. Sage-gray. |
| onSurfaceVariantDim | `#9AA08C` | Tertiary / disabled / placeholder. |
| outline | `#B9B6A6` | Hairline dividers, input borders. |
| **scrim** | `#1A1C16` @ ~90% (`#E61A1C16`) | Viewer background / fullscreen scrim. Warm near-black, not blue-black. |
| **overlayChrome** | `#000000` @ 38% (`#61000000`) | Gradient over photos behind viewer top/bottom controls. |
| onOverlay | `#FFFFFF` | Icons/text drawn over a photo or scrim. |
| onOverlayDim | `#FFFFFF` @ 72% (`#B8FFFFFF`) | Secondary text over a photo. |
| **success** | `#4F8A5B` | Backup complete, "backed up" check. Slightly bluer-green than the accent so it reads as status, not action. |
| **warning** | `#C2873B` | Muted amber. "Backing up paused," low storage. |
| **destructive (error)** | `#B0413A` | Muted brick red. Delete, errors. Desaturated to fit the palette. |
| onError | `#FFFFFF` | |
| errorContainer | `#F4D9D4` | |
| onErrorContainer | `#3A0F0C` | |

### 2.2 Dark theme (Conservatory Dark)

The viewer wants near-black anyway, so dark mode is the natural home for this app. Surfaces carry a
faint green-charcoal undertone so the app never looks like flat OLED black with floating UI.

| Role | Hex | Notes |
|---|---|---|
| **background** | `#14160F` | Deep green-charcoal. Behind the grid. |
| **surface** | `#191B13` | Top app bar, sheets, cards. |
| surfaceVariant | `#2B2E22` | Inputs, chips, inactive fills. |
| **surface elevation ladder** | `#1D1F16` → `#22241A` → `#272A1E` → `#2A2D21` → `#2F3225` | container-low → highest. |
| **gridGap** | `#0E0F0A` | Darker than background — thumbnails float on near-black, photo-first. |
| **primary / accent** | `#A6C394` | Lightened moss (dark-mode contrast). FAB, primary, selection, links, active tab. |
| onPrimary | `#1B2815` | Deep forest text on the light-moss accent. |
| primaryContainer | `#3C4D30` | Deep sage. Tonal button bg, selected-tab pill. |
| onPrimaryContainer | `#D5E0C7` | Pastel sage text on the deep container. |
| secondary | `#C3C4A4` | Light olive. |
| onSecondary | `#2C2D1E` | |
| secondaryContainer | `#42432F` | |
| onSecondaryContainer | `#E0DFC9` | |
| **onSurface** | `#E5E4D6` | Primary text/icon. Warm off-white, never pure `#FFF`. |
| onSurfaceVariant | `#BCBCA6` | Secondary text, captions. |
| onSurfaceVariantDim | `#7E806C` | Tertiary / disabled / placeholder. |
| outline | `#4A4C3D` | Dividers, input borders. |
| **scrim** | `#000000` @ ~94% (`#F0000000`) | Viewer background. In dark mode the world goes nearly black. |
| **overlayChrome** | `#000000` @ 46% (`#75000000`) | Gradient behind viewer controls. |
| onOverlay | `#FFFFFF` | |
| onOverlayDim | `#FFFFFF` @ 72% (`#B8FFFFFF`) | |
| **success** | `#7CB985` | |
| **warning** | `#D6A45C` | |
| **destructive (error)** | `#E29089` | Lightened brick for dark-mode contrast. |
| onError | `#3A0F0C` | |
| errorContainer | `#5A211C` | |
| onErrorContainer | `#F4D9D4` | |

### 2.3 Usage rules

- **Accent is rationed.** At most one accent-filled element per screen region (one FAB, one primary
  button). Selection state uses the accent; everything passive uses neutrals.
- **Grid gap is its own token** (`gridGap`), distinct from `background`/`surface`. Tuning the woven
  feel is a one-value change.
- **Over-photo controls never use theme neutrals** — they use `onOverlay` / `onOverlayDim` on top of
  the `overlayChrome` gradient, so they stay legible over any photo.
- **No pure black/white** anywhere in the chrome. `onSurface` and surfaces all carry warmth.

---

## 3. Typography

Platform-native fonts, used straight: **SF Pro** (iOS) / **Roboto** (Android). No custom display
face — the photos are the personality; the type stays quiet and legible. Restraint is the move:
weight and spacing carry hierarchy, not a decorative family.

| Role | Size / Line | Weight | Spacing | Used for |
|---|---|---|---|---|
| **display** | 34 / 41 | Regular | -0.4 | Login wordmark, empty-state headline. Rare. |
| **titleLarge** | 22 / 28 | Regular | 0 | Top app bar title ("Photos", album name). |
| **monthHeader** | 20 / 26 | **Semibold** | -0.2 | Sticky timeline month/section header ("June 2026"). The one place type carries structure. |
| dateSubhead | 15 / 20 | Medium | 0 | Day group sub-header inside a month ("Sat, Jun 14"). |
| **body** | 16 / 22 | Regular | 0 | Default text, list rows, sheet body. |
| bodyMedium | 14 / 20 | Regular | 0.1 | Secondary text, settings descriptions. |
| label | 14 / 20 | **Medium** | 0.1 | Buttons, tabs, chips. |
| **caption** | 12 / 16 | Regular | 0.2 | Photo metadata, EXIF, timestamps, counts. |
| captionOverlay | 13 / 18 | Medium | 0.1 | Text drawn over a photo/scrim (video duration, date in viewer). |

iOS maps these to Dynamic Type where practical (e.g. `body` ≈ `.body`, `monthHeader` ≈
`.title3.weight(.semibold)`); Android uses the Material 3 `Typography` slots noted in `Theme.kt`.
`monthHeader` is intentionally heavier than chat-kmp's all-Regular scale — it's the structural anchor
of the timeline.

---

## 4. Spacing, radii, elevation

### 4.1 Spacing scale (4dp base)
`2, 4, 8, 12, 16, 20, 24, 32, 40, 48` (dp / pt). Screen edge padding for non-grid content = **16**.
The timeline grid is intentionally **edge-to-edge** (0 outer padding) — only the gap between cells.

### 4.2 Corner radii
| Token | Value | Use |
|---|---|---|
| radiusNone | 0 | Grid thumbnails (edge-to-edge density; Google-Photos-class). |
| radiusSm | 8 | Chips, small buttons, metadata pills. |
| radiusMd | 12 | Cards, inputs, album cover tiles. |
| radiusLg | 18 | Bottom sheets, dialogs, the backup picker sheet. |
| radiusXl | 28 | FAB, fully-rounded "Backup" pill. |
| radiusFull | 50% | Avatars, selection check circles. |

### 4.3 Elevation / shadow
Chrome is mostly flat; elevation is used sparingly so it reads premium, not Material-busy.
- **Level 0** — grid, timeline background. No shadow; separation comes from `gridGap`.
- **Level 1** — top app bar on scroll: no drop shadow, a 1px `outline` hairline + surface tint only.
- **Level 2** — FAB / floating "Backup" pill: soft shadow, y-offset 2, blur 8, ~18% black.
- **Level 3** — bottom sheets / dialogs: y-offset 8, blur 24, ~24% black; corner `radiusLg`.
Dark mode leans on the **surface-elevation ladder** (§2) for separation rather than heavy shadows.

### 4.4 Grid metrics (the core of the app)
Target a **dense, even, edge-to-edge** timeline. Cells are square-cropped thumbnails (the
`225×300` grid thumbnail center-cropped to a square; aspect shown in the viewer, not the grid).

| Width breakpoint | Columns | Notes |
|---|---|---|
| < 360 dp (small phone) | 3 | |
| 360–599 dp (phone) | **4** | Default phone density. |
| 600–839 dp (small tablet / landscape) | 6 | |
| 840–1199 dp (tablet) | 8 | |
| ≥ 1200 dp (large / desktop window) | 10 | |

- **Gap:** `1.5dp` between cells, colored `gridGap`. Hairline — dense like Google Photos, but the
  warm gap keeps it from looking like a contact sheet.
- **Cell size** is derived: `(screenWidth - gap*(cols-1)) / cols`, always square. No fixed thumbnail
  px target — columns drive size so the grid is always edge-to-edge with no leftover margin.
- **Sticky month header** spans full width, `surface` background at ~92% opacity (so photos blur
  faintly under it on scroll), `monthHeader` type, 16dp horizontal / 12dp vertical padding.
- **Pinch-to-zoom the grid** steps the column count (e.g. 4 ⇄ 3 ⇄ 2 and 4 ⇄ 6) — Google-Photos
  behavior — animating cell size between breakpoints.

---

## 5. Component specs (MVP screens)

Each spec lists **layout + states + the Google-Photos-class interaction**. Colors refer to the
tokens above; both platforms render natively from the same tokens.

### 5.1 Login

**Job:** get the user authenticated via YouAuth with the calmest possible first impression.

- **Layout:** full-bleed `background`. Centered column: a small moss wordmark/leaf glyph, the
  `display` headline "Homebase Photos", one line of `bodyMedium` `onSurfaceVariant` subtext
  ("Your photos, your server."), then a single full-width **primary** pill button (`radiusXl`,
  `primary` fill, `onPrimary` label) "Sign in with Homebase". A small `caption` legal/help line at
  the bottom.
- **States:**
  - *Idle* — button enabled.
  - *Authenticating* — button shows an inline circular spinner (`onPrimary`), label "Connecting…",
    button disabled. The YouAuth web flow opens in a system browser/`ASWebAuthenticationSession`.
  - *Error* — a `destructive`-tinted inline banner below the button ("Couldn't sign in. Try again."),
    button returns to Idle. Errors don't apologize; they say what to do.
- **Interaction:** no carousel, no marketing. One job, one button. Respect reduced motion (the
  wordmark fades in only; no parallax).

### 5.2 Timeline grid (home)

**Job:** the dense, buttery, scroll-anywhere wall of your photos, grouped by time.

- **Layout:** edge-to-edge `LazyVerticalGrid` (Android) / `LazyVGrid` pinned-header sections (iOS)
  over `background`. Sections = months; each month is a **sticky** `monthHeader`, then the day
  groups, then the cells (§4.4). Top app bar: `titleLarge` "Photos" left, an avatar (account) right,
  flat on `surface`; on scroll it gains the §4.3 Level-1 hairline. A floating **"Backup" pill / FAB**
  (`primary`, `radiusXl`, Level-2 shadow) bottom-right.
- **Cell states:**
  - *Loading* — the inline `previewThumbnail` blur placeholder (from the file) on a `gridGap` fill;
    no spinner per cell (would shimmer the whole wall). Coil/AsyncImage crossfades the `225×300`
    thumbnail in over the blur.
  - *Loaded* — square-cropped thumbnail, `radiusNone`.
  - *Video* — a small `captionOverlay` duration badge bottom-right over a faint bottom gradient.
  - *Selected* (multi-select) — cell insets ~8%, gains an accent ring; a filled `primary` check
    circle top-left (`onPrimary` tick); a translucent `primary` @ ~24% wash.
- **Empty state** — centered `display`/`bodyMedium`: "No photos yet" + "Back up your camera roll to
  see it here." with the primary backup button. An invitation to act, never a blank wall.
- **Interactions (Google-Photos-class):**
  - **Buttery scroll** with aggressive Coil prefetch ahead of the viewport (the perf budget per the
    spec lives in the image pipeline, not the layout).
  - **Pinch-to-zoom** changes column count between breakpoints (§4.4), animated.
  - **Sticky headers** that swap as you scroll; long-list **fast-scroll / month scrubber** is a later
    batch but the header is the anchor.
  - **Long-press** enters multi-select; drag across cells range-selects; the app bar morphs into a
    selection action bar (count + share/delete/add-to-album).
  - **Tap a cell** → shared-element / matched-geometry transition into the viewer (the tapped
    thumbnail expands into the full image).

### 5.3 Fullscreen viewer

**Job:** reverent, immersive, one photo at a time, with the chrome gone until you ask for it.

- **Layout:** full-screen `scrim` background. A horizontal **pager** over the same paged list; each
  page shows the `900×1200` preview (then the original) fit to the frame, centered. Chrome = a top
  bar (back, date as `captionOverlay`, overflow) and a bottom action row (share, favorite, info,
  delete) — both drawn on the `overlayChrome` gradient, using `onOverlay`.
- **States:**
  - *Chrome visible* — top/bottom gradients + controls shown.
  - *Immersive* — controls and gradients fade out (default after ~3s, or on first pan/zoom); only the
    photo on the scrim remains.
  - *Zoomed* — pinch-zoom active; pager swipe is locked until zoomed back to fit; double-tap toggles
    fit ⇄ 2× at the tap point.
  - *Video* — poster frame, centered play control (`onOverlay`); on play, native AVPlayer/ExoPlayer,
    scrubber on the bottom gradient.
  - *Loading hi-res* — the `900×1200` shows immediately; the original streams in and crossfades; a
    thin `onOverlayDim` progress hairline at the very top if it's slow.
- **Interactions (Google-Photos-class):**
  - **Swipe horizontally** to page between photos (paged, prefetched neighbors).
  - **Pinch / double-tap zoom**, pan when zoomed.
  - **Swipe down to dismiss** — the photo follows the finger and the scrim fades to the grid via the
    shared-element transition (interactive, interruptible).
  - **Single tap** toggles chrome. Respect reduced motion (crossfade instead of geometry zoom).
  - **Swipe up** reveals an info panel (date, location map, camera/EXIF from `content`) over a
    `surface` sheet.

### 5.4 Albums grid

**Job:** browse named collections; clearly a different mode from the timeline.

- **Layout:** a roomier grid (2 cols phone / 3 tablet) of **album cover cards** (`radiusMd`,
  Level-1) over `background`, with breathing room (16dp outer padding, 12dp gap) — deliberately
  *not* the dense edge-to-edge timeline, so the two modes feel distinct. Each card: square cover
  thumbnail, then `body` album name + `caption` count below on `surface`. A "+" (new album) entry as
  the first tile or in the app bar.
- **States:**
  - *Cover loading* — `previewThumbnail` blur of the cover on `gridGap`.
  - *Empty album* — placeholder cover = `surfaceVariant` with a small `onSurfaceVariantDim` photo
    glyph.
  - *No albums* — centered empty state + "Create album".
- **Open album** → a timeline-style edge-to-edge grid scoped to that album (`queryBatch` by tag),
  same cell/viewer behavior as §5.2/§5.3, with the album name as `titleLarge` in the app bar.
- **Interaction:** tap a cover → matched-geometry expand into the album grid; long-press a cover →
  rename/cover/delete sheet.

### 5.5 Backup status / picker

**Job:** make "is my stuff safe?" obvious, and make picking what to back up effortless.

- **Status surface** (top of timeline / a dedicated row): a compact card on `surface`.
  - *Idle / all backed up* — `success` check + "Backed up" + `caption` "1,204 photos · up to today".
  - *Backing up* — a determinate progress bar (`primary` track on `surfaceVariant`), "Backing up
    24 of 60", a small thumbnail of the current item.
  - *Paused / no network* — `warning` dot + "Waiting for Wi-Fi" + a "Back up now" text button.
  - *Error* — `destructive` dot + "5 items failed" + "Retry".
- **Picker sheet** (manual backup, Batch-1): a bottom sheet (`radiusLg`, Level-3) that hosts the
  **native** picker (PHPicker / Android Photo Picker) for selection, then shows a confirm row:
  selected count, est. size, and a full-width `primary` "Back up N items" button. A secondary
  `label`/text "Choose quality" row (Original vs. saver — Original default per spec).
- **States:** *empty selection* → primary button disabled, label "Select photos to back up".
  *Uploading* → sheet can dismiss; progress continues in the status surface (uploads run through the
  existing Outbox).
- **Interaction:** selection is the native OS picker (trusted, familiar). Our chrome is only the
  confirm + the live status. Voice is plain and active: the button says "Back up 12 items," and the
  resulting status says "Backed up."

---

## 6. Cross-platform parity

- Palette hex values in §2 are **identical** across `androidApp/.../ui/theme/Color.kt` and
  `iosApp/Theme/Theme.swift`. Changing a color is a two-file edit with the same value.
- Android exposes Material 3 `ColorScheme` (light+dark) **plus** a `PhotosExtendedColors` seam for
  tokens M3 doesn't have (`gridGap`, `scrim`, `overlayChrome`, `onOverlay*`, `success`, `warning`,
  `onSurfaceVariantDim`, the surface ladder). iOS exposes the same set as `Color` accessors on
  `PhotosColor`, resolving light/dark via the system color scheme.
- Type roles in §3 map to M3 `Typography` slots (Android) and Dynamic Type / explicit `Font`
  (iOS); names match so screen code reads the same on both sides.

---

## 7. What we kept vs. changed from chat-kmp

| Kept (structure) | Changed (this app) |
|---|---|
| M3 token system + light/dark `ColorScheme` | Every color: Signal-blue `#2C58C3` → moss `#5E7A52`; warm linen/charcoal grounds. |
| Surface-elevation ladder (`Surface1..5`) | Re-tinted warm/green; renamed to a `gridGap`+ladder model. |
| Extended-colors composition-local seam | New photo-specific tokens: `gridGap`, `scrim`, `overlayChrome`, `onOverlay*`. |
| Platform-native fonts, Regular-weight scale | Added a **Semibold `monthHeader`** as the timeline's structural anchor; trimmed the huge chat type set to the roles Photos needs. |
| Dimens-as-object pattern | Replaced chat dimens with grid-first metrics (columns-per-breakpoint, hairline gap, edge-to-edge). |
