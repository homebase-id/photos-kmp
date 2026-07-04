# Plan 001: Shared timeline contract prep — pagination state, awaitable refresh, iOS NSData bridge, visible mock data

> **Executor instructions**: Follow this plan step by step. You are a WRITER in a
> code-first / batched-verify workflow: **do NOT run gradle, xcodebuild, or any
> build/test command** — a single verifier agent builds everything after all
> writers finish. Self-review each edit instead. Touch only the files listed as
> in scope. If any STOP condition occurs, stop and report — do not improvise.
> Do NOT commit. Do NOT update `plans/README.md` (the reviewer maintains it).
>
> **Drift check (run first)**: This repo's source tree is entirely uncommitted
> (`git diff` against a SHA is useless). Instead, open each file under "Current
> state" and confirm the excerpts match the live code. On a mismatch, STOP.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW
- **Depends on**: none (plans 002/003 depend on THIS plan's new API surface)
- **Category**: perf + tech-debt
- **Planned at**: commit `86e57a2`, 2026-07-04 (tree uncommitted — excerpts are ground truth)

## Why this matters

The native timeline redesign (plans 002/003) needs four things the shared layer
doesn't provide today: (1) a pagination flag separate from `isLoading` so
appending a page doesn't re-render the whole grid mid-scroll (PERF-02/PERF-03);
(2) an awaitable refresh so iOS `.refreshable` can hold its spinner until sync
completes (IUI-03); (3) a bulk `ByteArray → NSData` bridge so iOS stops copying
thumbnails one byte per Kotlin/ObjC interop call (~30,000 bridge round-trips per
30 KB thumbnail — PERF-01); (4) mock data that actually renders — today every
mock item has `previewPlaceholder = null` and `loadThumbnailBytes` returns
`null`, so both grids draw blank squares, which is why the first-pass UI was
rejected on sight.

## Current state

- `shared/src/commonMain/kotlin/id/homebase/photos/timeline/TimelineViewModel.kt`
  — the timeline VM. Key excerpts as of planning:

```kotlin
// TimelineViewModel.kt:23
data class TimelineUiState(
    val isLoading: Boolean = true,
    val sections: List<TimelineSection> = emptyList(), // month-grouped for sticky headers
    val pagedItems: List<PhotoItem> = emptyList(),      // flat list backing the viewer pager
    val endReached: Boolean = false,
    val error: String? = null,
)

// TimelineViewModel.kt:54 — refresh is fire-and-forget
fun refresh() {
    viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        try {
            repository.sync()
        } catch (e: Exception) { ... }
        val page = safeLoad(beforeUserDate = null)
        applyReplace(page)
    }
}

// TimelineViewModel.kt:69 — loadMore toggles isLoading and regroups EVERYTHING
fun loadMore() {
    val current = _state.value
    if (current.endReached || current.isLoading) return
    val cursor = current.pagedItems.lastOrNull()?.userDate ?: return
    viewModelScope.launch {
        _state.update { it.copy(isLoading = true) }
        val page = safeLoad(beforeUserDate = cursor)
        applyAppend(page)
    }
}

// TimelineViewModel.kt:107 — applyAppend re-sorts + regroups the whole merged list
private fun applyAppend(page: List<PhotoItem>) {
    _state.update {
        val merged = it.pagedItems + page
        it.copy(
            isLoading = false,
            pagedItems = merged,
            sections = groupIntoMonthSections(merged),
            endReached = page.size < PAGE_SIZE,
        )
    }
}
```

- `shared/src/commonMain/kotlin/id/homebase/photos/data/MockPhotosRepository.kt`
  — seeds ~60 items with `previewPlaceholder = null` (line 35) and
  `override suspend fun loadThumbnailBytes(item, maxDim): ByteArray? = null` (line 56).
- `shared/src/nativeMain/kotlin/id/homebase/photos/IosBootstrap.kt` — existing
  nativeMain photos file; the new bridge file sits next to it.
- `shared/src/commonMain/kotlin/id/homebase/photos/PhotosModule.kt:60` — the
  iOS-callable `suspend fun loadThumbnailBytes(item, maxDim): ByteArray?` shim
  (leave it; the new NSData function wraps it).
- Convention exemplar for the memcpy bridge — chat-kmp
  `homebase-common/src/nativeMain/kotlin/id/homebase/core/util/FileUtilities.native.kt:278`:

```kotlin
out.usePinned { pinned ->
    memcpy(pinned.addressOf(0), bytes, n.toULong())
}
```

- Repo conventions: MVVM `_state.update {}`, flat UiState data class, Kermit
  logging, minimal terse comments (non-obvious *why* only), strict TDD on logic.
  Existing shared photos tests live under `shared/src/commonTest/kotlin/id/homebase/photos/`
  (e.g. `VideoMarkerTest`) — match their style (kotlin.test, plain asserts).

## Commands you will need

NONE — you are a writer. The verifier runs, after all writers finish:
`./gradlew :shared:compileKotlinJvm :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64`
then `./gradlew :shared:jvmTest` (judge only `id.homebase.photos.*` — ~160
imaging-test failures are pre-existing), then the app builds.

## Scope

**In scope** (the only files you may modify/create):
- `shared/src/commonMain/kotlin/id/homebase/photos/timeline/TimelineViewModel.kt`
- `shared/src/commonMain/kotlin/id/homebase/photos/data/MockPhotosRepository.kt`
- `shared/src/nativeMain/kotlin/id/homebase/photos/PhotosModuleIos.kt` (create)
- `shared/src/commonTest/kotlin/id/homebase/photos/timeline/TimelineSectionsTest.kt` (create)
- `shared/src/commonTest/kotlin/id/homebase/photos/data/MockPlaceholderTest.kt` (create)

**Out of scope** (do NOT touch):
- `PhotosRepository.kt` / `PhotosRepositoryImpl.kt` — interface stays as-is
  (PERF-09/PERF-10 deliberately deferred until the real repo is bound).
- `PhotosModule.kt` (commonMain) — no Koin/DI changes.
- Anything under `androidApp/`, `iosApp/`, `shared/src/*/id/homebase/api/`.
- `docs/superpowers/batch1-contracts.md` — additive changes below are
  documented in the plan README, not by editing the locked contract doc.

## Git workflow

None. Working tree only, no commits, no branches (repo rule: owner commits).

## Steps

### Step 1: Add `isPaginating` to `TimelineUiState` and use it in `loadMore()`

In `TimelineViewModel.kt`:
- Add `val isPaginating: Boolean = false` after `isLoading` in `TimelineUiState`.
- `loadMore()` guard becomes `if (current.endReached || current.isLoading || current.isPaginating) return`.
- Inside `loadMore()`'s launch: `_state.update { it.copy(isPaginating = true) }`
  (do NOT touch `isLoading`).
- `applyAppend` sets `isPaginating = false` instead of `isLoading = false`.

Semantics after this step: `isLoading` = initial load or refresh (replace) in
flight; `isPaginating` = older-page append in flight. Native grids ignore
`isPaginating` for cell rendering and only use it for a footer spinner.

### Step 2: Awaitable refresh

Replace `refresh()`'s body with a delegating launch, and add a suspend variant
(SKIE exposes it to Swift as `async`):

```kotlin
/** Fire-and-forget refresh (Android pull-to-refresh drives isLoading instead). */
fun refresh() { viewModelScope.launch { refreshAndWait() } }

/** Sync then reload, suspending until done — iOS .refreshable awaits this. */
suspend fun refreshAndWait() {
    _state.update { it.copy(isLoading = true, error = null) }
    try {
        repository.sync()
    } catch (e: Exception) {
        Logger.w(tag = TAG) { "refresh sync failed: ${e.message}" }
        emitError(e.message ?: "Sync failed")
    }
    val page = safeLoad(beforeUserDate = null)
    applyReplace(page)
}
```

### Step 3: Incremental append grouping

Add a pure top-level function next to `groupIntoMonthSections` and use it in
`applyAppend`:

```kotlin
/**
 * Append [page] (already userDate DESC and strictly older than existing) to
 * [sections] without re-sorting or rebuilding prior months. The page's first
 * month bucket merges into the last existing section when titles match.
 */
fun appendToMonthSections(sections: List<TimelineSection>, page: List<PhotoItem>): List<TimelineSection>
```

Implementation shape: `groupIntoMonthSections(page)` for the page alone (it
defensively re-sorts just the page — cheap), then if the first new section's
title == `sections.last().title`, emit `sections.dropLast(1) + merged-last +
rest`, else `sections + newSections`. Untouched earlier sections MUST keep
their object identity (no copying) — that's what lets SwiftUI/Compose skip them.
`applyAppend` uses `appendToMonthSections(it.sections, page)`.

### Step 4: iOS NSData bridge (new file `PhotosModuleIos.kt` in nativeMain)

```kotlin
package id.homebase.photos

import id.homebase.photos.domain.PhotoItem
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create

/** iOS-callable: decoded thumbnail as NSData in ONE copy (memcpy), not per-byte interop. */
suspend fun loadThumbnailData(item: PhotoItem, maxDim: Int): NSData? =
    loadThumbnailBytes(item, maxDim)?.toNSData()

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData()
    else usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
```

(`NSData.create(bytes:length:)` copies the buffer, so the pinned array may be
released after the call. Same idiom as chat-kmp `FileUtilities.native.kt:278`.)

### Step 5: Make the mock render — placeholders + thumbnail bytes

In `MockPhotosRepository.kt`:
- Add a private list of the 8 base64 webp micro-placeholders below (20×27 px,
  ~100 bytes each, earthy tones — generated for this plan; paste verbatim):

```kotlin
private val PLACEHOLDERS = listOf(
    "UklGRl4AAABXRUJQVlA4IFIAAABQBACdASoUABsAPvVmpk6qpaMiMAwBUB6JYwCBhgiujA8I5hzGxPMZfuwAAP7DVu/ZRmguqSat6LVRP7DRkv/FAWwNtys4aj47l+m7+Wo7IAAA",
    "UklGRmQAAABXRUJQVlA4IFgAAAAwBACdASoUABsAPwFqrE8rJiQiMAgBYCAJZwCuHBgc8BlzjlpkGboOBIAA/RxpZSvu7saH8DK3aO30TaxqbmYY21oWLDRNmzBl9X3Iv88XmbCGmMWmgAAA",
    "UklGRmAAAABXRUJQVlA4IFQAAACwBACdASoUABsAPwFwrlCrP6QisBgIA/AgCWMAtRevAOjSroj8GoVoA+aYAHUQAP7ea7fhDFGtaQMCGQ3uViBmEWfMrXWSBTljqgPNIJt6UD7DAAA=",
    "UklGRlwAAABXRUJQVlA4IFAAAABQBACdASoUABsAPwFysFIrJr4iqAqrwCAJZwC/7A9mWX1sa26ZYUegzuoAAP7TmzdJCRxFVG1Abk7Vwmt7AORxFSHg8Vz+TM1GcjKk1KAAAA==",
    "UklGRlwAAABXRUJQVlA4IFAAAAAwBACdASoUABsAPv1urU6rJrwiMBgMA4AfiWcAzNAQ6B5ShJW9pATttEAA/q6cVj16Hk+rqTts9iZ1W0gTSJwFL63jGILIUXF+tJ9D52AAAA==",
    "UklGRmIAAABXRUJQVlA4IFYAAADQBACdASoUABsAPvlqqE6qpiOiMAwBUB8JZwDA3CHftRgGZJvFL4AyisueQzVOAAD+nODXP0kno187i6L2//24zo8aqjP3wLOqgJ6gDFAYjprUfwJwAA==",
    "UklGRmAAAABXRUJQVlA4IFQAAACQBACdASoUABsAPwFoqlArJbqisBgMA1AgCWUAvzgQtgx2Q1z29y6mOLMxrzAA/ucfpJV50dumL440AhdFFhYbuhdrF8nMzx8D3+TM1CMsy4YAAAA=",
    "UklGRmAAAABXRUJQVlA4IFQAAACwBACdASoUABsAPwFssFCrJaSisBgIAWAgCWUAwNwGncIH9D8OimoJ+4IDhlQAAPcEFOepGpQBmq0qiwhTPIslSqWlqcjnnjcWAYsaJ4p7oGUeAAA=",
)
```

- Seed each item with `previewPlaceholder = PLACEHOLDERS[i % PLACEHOLDERS.size]`.
- Implement `loadThumbnailBytes` to return the decoded placeholder (exercises
  the full native decode path end-to-end):

```kotlin
@OptIn(ExperimentalEncodingApi::class)
override suspend fun loadThumbnailBytes(item: PhotoItem, maxDim: Int): ByteArray? =
    item.previewPlaceholder?.let { Base64.decode(it) }
```

(`kotlin.io.encoding.Base64` / `kotlin.io.encoding.ExperimentalEncodingApi`.)

### Step 6: Tests (TDD — write these FIRST if you prefer; they must exist)

`TimelineSectionsTest.kt` (kotlin.test, pure functions — no coroutines needed):
- `appendToMonthSections` merges a same-month page into the last section
  (counts add up, title unchanged).
- Appends a new month as a new section.
- Preserves object identity of untouched sections: `assertSame(existing[0], result[0])`.
- Empty page returns the original list.

`MockPlaceholderTest.kt`:
- Every seeded item has a non-null, non-empty `previewPlaceholder`.
- `loadThumbnailBytes` returns non-null bytes whose first 4 bytes are `RIFF`
  (0x52 0x49 0x46 0x46 — webp container magic).

If `TimelineViewModel` behavior tests are feasible with what's already on the
test classpath (check for `kotlinx-coroutines-test` in `shared/build.gradle.kts`
test dependencies), also add: `loadMore` sets `isPaginating` not `isLoading`.
If the dependency is absent, SKIP the VM test — do not add dependencies.

## Test plan

See Step 6. Model after the existing tests in
`shared/src/commonTest/kotlin/id/homebase/photos/` (plain `kotlin.test`).
Verifier command: `./gradlew :shared:jvmTest` — all `id.homebase.photos.*` pass.

## Done criteria

- [ ] `TimelineUiState` has `isPaginating`; `loadMore` never touches `isLoading`.
- [ ] `refreshAndWait()` exists as `suspend`; `refresh()` delegates to it.
- [ ] `appendToMonthSections` exists, is used by `applyAppend`, and its tests pass.
- [ ] `PhotosModuleIos.kt` compiles for iOS with the single-memcpy bridge.
- [ ] Mock items all carry placeholders; `loadThumbnailBytes` returns their bytes.
- [ ] No files outside the in-scope list modified.

## STOP conditions

- Any "Current state" excerpt doesn't match the live file.
- `nativeMain` turns out not to compile for the iOS targets (check
  `shared/build.gradle.kts` source-set wiring) — report, don't relocate the file yourself.
- You find yourself wanting to change `PhotosRepository` or Koin bindings.

## Maintenance notes

- `isPaginating` + `refreshAndWait` are ADDITIVE to the locked Batch-1 contract;
  plans/README.md records the extension.
- When `PhotosRepositoryImpl` gets bound (post-login), revisit deferred
  PERF-09 (per-thumbnail SQL+deserialize) and PERF-10 (double read on sync).
- The webp magic-byte test pins the placeholder format; if placeholders move to
  JPEG later, update the magic check.
