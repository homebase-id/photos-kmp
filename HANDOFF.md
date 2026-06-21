# Homebase Photos — Handoff

**For:** a fresh Claude Code session opened in `~/Documents/GitHub/homebase-photos`.
**Date:** 2026-06-21
**Status:** Design approved + committed. Nothing built yet. Next step = **Batch 0** implementation plan.

---

## What this is

A native (SwiftUI + Jetpack Compose) Google-Photos-class app on the Homebase/Odin protocol,
reusing `chat-kmp`'s `homebase-api` (copied + adapted). Built in batches; MVP first.

**Read the full design first — do not re-derive it:**
[`docs/superpowers/specs/2026-06-21-homebase-photos-design.md`](docs/superpowers/specs/2026-06-21-homebase-photos-design.md)

The spec has the locked decisions, the data format, the batch roadmap, and the risks. This handoff
only adds orientation + reference paths the spec doesn't repeat.

---

## Start here (Batch 0 — the skeleton, its own batch, non-negotiable)

Goal: prove "true native UI over a copied headless Kotlin layer" builds and renders on **both**
platforms before any feature exists. Do NOT skip ahead to features.

1. Scaffold `homebase-photos` repo: `shared` (KMP: android + iosArm64 + iosSimulatorArm64),
   `androidApp` (Compose), `iosApp` (SwiftUI). Trimmed Gradle + version catalog copied from chat-kmp.
2. `shared` = **copy** `homebase-api`; drop `ChatReadCount.sq`; strip the `fileType 7878/8888`
   guards; add `PhotoConfig` (drive GUIDs + fileTypes + `dflt_key` + thumb sizes). Make it compile →
   AAR + xcframework.
3. Wire **SKIE**; prove a `StateFlow` + a `suspend` fn consume cleanly from a SwiftUI test view.
4. Koin boots in `shared`; Android(Compose) AND iOS(SwiftUI) each render one shared `StateFlow`.
5. Image-pipeline harness: copy the encrypted Coil fetcher/decoder; throwaway screen decoding N
   encrypted thumbnails (validate decode + cache + prefetch early — this is where GPhotos perf lives).
6. Bare auth: `YouAuthFlowManager` login proven both sides.
7. Verify `queryBatch` tag-filtering works (albums depend on it); pin the photo-vs-video marker.

**Before writing code, invoke the `superpowers:writing-plans` skill** to turn the spec's Batch 0
into a concrete, reviewable plan. Then `superpowers:using-git-worktrees` / `executing-plans` to run it.

---

## Locked facts (don't re-litigate — see spec for the why)

- **UI:** SwiftUI (iOS) + Jetpack Compose (Android). Shared Kotlin is **headless** — stops at
  `StateFlow<UiState>`. Views are native and duplicate nothing but rendering.
- **Protocol:** copy + adapt `homebase-api`. It has **zero** deps on chat/UI modules — clean copy.
- **iOS interop:** SKIE (`Flow`→`AsyncSequence`, `suspend`→`async`). It is NOT DI.
- **DI:** Koin (runs inside the framework; iOS calls exposed factories).
- **Grid:** declarative first (`LazyVerticalGrid` / `LazyVGrid`). Drop to `RecyclerView` /
  `UICollectionView` ONLY if on-device profiling proves it. Spend perf budget on the **Coil image
  pipeline**, not the layout framework.
- **Drive:** `type = 2af68fe72fb84896f39f97c59d60813a`, `alias = 6483b7b1f71bd43eb6896c86148668cc`.
- **File format:** match the existing Odin Photos format (spec §4). Photo = `fileType 0`,
  `dataType 0`, payload key `dflt_key`, thumbnails webp `15×20 / 225×300 / 900×1200`, inline
  `previewThumbnail` placeholder, `userDate` = EXIF capture millis. Album = `fileType 900` + a tag.
- **Legacy files:** ignored. We write fresh files; no migration.

---

## Reference paths (read, don't reinvent)

| What | Where |
|---|---|
| **The copy source** (protocol layer) | `~/Documents/GitHub/chat-kmp/homebase-api/` |
| iOS framework export pattern | `chat-kmp/homebase-api/build.gradle.kts` (`baseName="homebase-api", isStatic=true`) |
| Transitive-export pattern (reference) | `chat-kmp/homebase-core/build.gradle.kts` |
| Version catalog | `chat-kmp/gradle/libs.versions.toml` |
| Auth (UI-free) | `chat-kmp/homebase-api/.../youauth/YouAuthFlowManager.kt`, `.../client/auth/CredentialsManager.kt` |
| Drive query/upload/sync | `chat-kmp/homebase-api/.../client/drives/`, `.../sync/` |
| SQLDelight schema (drop `ChatReadCount.sq`) | `chat-kmp/homebase-api/.../sync/database/*.sq` |
| FFmpeg integration + iOS serial-queue fix | `chat-kmp` `FFmpegKitBridgeImpl`; `~/Documents/GitHub/ffmpeg-kit/` |
| Image compression / thumbnails | `chat-kmp` `ImageUtils` |
| Encrypted Coil fetcher/decoder | search `chat-kmp` for the Coil fetcher in the encrypted media path |
| App-registration / YouAuth drive request to mirror | `chat-kmp` auth flow (how chat declares its drives) |
| **Odin Photos format + video marker + app-registration source of truth** | `~/Documents/GitHub/DotYouCore/`, `~/Documents/GitHub/homebase-web/`, `~/Documents/GitHub/dotyoucore-js/` |

---

## Gotchas carried over from chat-kmp (will bite Batch 0/1 if ignored)

- **Drift pin:** the copied `homebase-api` is forked from chat-kmp commit **`e67130cd`**. Record this
  in the copied module's README. Re-syncing upstream crypto/sync fixes = diff against this hash.
  (Upstream `improve` backlog flags weak crypto RNG + keys-in-logs in this exact layer.)
- **iOS FFmpegKit crash:** concurrent `ffmpeg_execute` crashes (fftools not reentrant). Copy chat's
  **serial-queue** fix in `FFmpegKitBridgeImpl`. Also: never `CODE_SIGNING_ALLOWED=NO` — unsigned
  bundled FFmpegKit dylibs get dyld-killed at launch.
- **Payload key rule:** must match `^[a-z0-9_]{8,10}$`. `dflt_key` (8) is fine; server 400s otherwise.
- **Original upload:** chat uploads images **byte-for-byte** (no resize) — reuse that path for
  "Original quality". Thumbnails are generated separately.
- **Android thumbnail encode:** `ImageUtils` Android encode is `@RequiresApi(30)`; **WebP encode
  has crashed** historically (PNG safe). Our thumbnails are webp (`225×300` etc.) — validate the
  Android webp-encode path early or fall back.
- **iOS video send:** read PHAsset video via `PHAssetResourceManager`, NOT the image-only
  `PHImageManager` (that ships a few-KB broken file).
- **`stateIn` spinner flash:** seed from a cache snapshot, not empty+loading, or the grid flashes a
  spinner over cached data.

---

## Suggested skills (invoke these in the new session)

1. `superpowers:writing-plans` — **first.** Turn spec Batch 0 into a reviewable plan.
2. `kmp-compose-multiplatform` + `kotlin-coroutines-flows` — load before touching the shared module.
3. `superpowers:using-git-worktrees` then `superpowers:executing-plans` — to run the plan.
4. `frontend-design` — when shaping the native grid/viewer look.
5. Argent MCP (`argent-*` skills) — on-device build/run + grid perf profiling (iOS sim + Android emu).

---

## Open Batch-0 decisions to settle (cheap, do them in the plan)

- `uniqueId` dedup derivation: content hash vs device asset id. Pick deterministic.
- Photo-vs-video marker: confirm from `DotYouCore`/`homebase-web` photo source (likely a `content`
  flag or contentType check).
- Confirm `DriveUploadProvider` handles large (video) payloads, or whether chunked/streaming upload
  is needed.
