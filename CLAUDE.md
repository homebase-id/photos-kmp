# CLAUDE.md — Homebase Photos

Guidance for Claude Code working in this repo.

## What this project is

A native **Google-Photos-class** app on the Homebase/Odin protocol, reusing `chat-kmp`'s
`homebase-api` (copied + adapted). **Nothing is built yet** — the repo currently holds only the
design. Start by reading, in order:

1. [`HANDOFF.md`](HANDOFF.md) — orientation + Batch 0 start + reference paths + gotchas.
2. [`docs/superpowers/specs/2026-06-21-homebase-photos-design.md`](docs/superpowers/specs/2026-06-21-homebase-photos-design.md) — full design. **Don't re-derive it.**

Next concrete step: invoke `superpowers:writing-plans` to turn the spec's **Batch 0** (skeleton)
into a reviewable implementation plan.

## IMPORTANT — architecture overrides (read before the skills mislead you)

The `kmp-compose-multiplatform` skill in `.claude/skills/` is great for **KMP structure, clean
architecture, coroutines/Flow, build, and iOS interop** — apply it there. But its **Compose
Multiplatform UI** guidance does **NOT** apply here:

- **UI is fully native: SwiftUI (iOS) + Jetpack Compose (Android).** There is NO shared Compose UI,
  no Compose Multiplatform. The `shared` KMP module is **headless** — it stops at
  `StateFlow<UiState>`. Views live in `androidApp/` (Compose) and `iosApp/` (SwiftUI) and duplicate
  nothing but rendering.
- iOS consumes the shared module via an **xcframework + SKIE** (Flow→AsyncSequence, suspend→async).
  SKIE is interop polish, **not DI**. DI is **Koin**, running inside the framework.
- `homebase-api` is **copied + adapted** (pinned to `chat-kmp` commit `e67130cd`), not a live Gradle
  dependency. Record/keep that pin so upstream security/sync fixes can be diffed in.

## Data model (summary — full detail in spec §4)

- Photos drive: `type = 2af68fe72fb84896f39f97c59d60813a`, `alias = 6483b7b1f71bd43eb6896c86148668cc`.
- Match the existing Odin Photos format for fresh files. Photo = `fileType 0`/`dataType 0`, payload
  key `dflt_key`, webp thumbnails `15×20 / 225×300 / 900×1200`, inline `previewThumbnail`
  placeholder, `userDate` = EXIF capture millis. Album = `fileType 900` + a tag.

## Conventions (inherited from chat-kmp)

- **MVVM:** `ViewModel` + `StateFlow<UiState>` (flat `data class`, `_uiState.update { }`); one-time
  events on a separate `SharedFlow`. ViewModels live in `shared`, consumed by both native UIs.
- **DI:** Koin. **DB:** SQLDelight (`OdinDatabase`, minus `ChatReadCount.sq`). **Net:** Ktor.
  **Logging:** Kermit. **Images:** Coil 3 (encrypted fetcher copied from chat-kmp).
- **Per-module compile checks** (KMP library tasks, not AGP app tasks):
  `:shared:compileKotlinJvm` (if a JVM target), `:shared:compileAndroidMain`,
  `:shared:compileKotlinIosSimulatorArm64`. iOS app: open `iosApp/` in Xcode.
- Don't use `/` in git branch names.
- Minimal code comments — terse one-liner only for non-obvious *why*, never "what the code does".

## Gotchas

See [`HANDOFF.md`](HANDOFF.md) "Gotchas" — payload-key regex, iOS FFmpegKit serial-queue +
no-`CODE_SIGNING_ALLOWED=NO`, byte-for-byte original upload, Android webp thumbnail-encode
`@RequiresApi(30)` risk, PHAssetResourceManager for iOS video, `stateIn` cache-seed.

## On-device testing (argent)

Argent MCP drives the iOS simulator + Android emulator (see `.claude/rules/argent.md`). The argent
device skills are CLI/MCP-managed — if they're not present, run `argent --help` to install/start
them in this repo. Use them to validate the photo grid's scroll/frame budget on real devices.

## Git

Don't commit or push unless asked. End commit messages with:
`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
