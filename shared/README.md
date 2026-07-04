# shared

Copied + adapted from `chat-kmp/homebase-api` (protocol / drives / sync / crypto / youauth) and the
encrypted-image pipeline from `chat-kmp/homebase-common` (`id.homebase.core.image`).

**Upstream pin:** chat-kmp commit `e67130cd`. Re-sync upstream security/sync fixes by diffing against
this hash. (Upstream `improve` backlog flags weak crypto RNG + keys-in-logs in this layer.)

The `shared` module is **headless** — it stops at the protocol layer + `StateFlow<UiState>`. UI is
fully native (SwiftUI / Jetpack Compose) and lives in `iosApp/` and `androidApp/`. Compose appears
here only as a *library for data types* (see below), never as shared UI.

## Removed on copy

- `wasmJs` target + all its source (`wasmJsMain`, `wasmJsTest`) and ffmpeg-wasm test wiring — out of
  scope for the photos app.
- `ChatReadCount.sq` (table) + `ChatReadCountWrapper.kt` (+ its test) — chat unread-count machinery.
- Chat `fileType == 7878` logging block in `DriveSync.kt`.
- `MessageFetchPlanTest.kt` (jvmTest) — chat message-fetch planning test.

## Deviations from the Batch-0 plan (Task 2) — files / deps / catalog entries added or changed

The plan's Task 2 step 5 build file is the baseline. Everything below is in addition to (or a
deliberate divergence from) it, recorded so the diff vs upstream and vs the plan stays auditable.

### SKIE NOT applied yet (deferred to Task 4)
The plan's Task-2 build snippet lists `alias(libs.plugins.skie)`. Applying it fails configuration:
**SKIE 0.10.6 does not support Kotlin 2.3.21** (max supported is 2.2.10). Per the task brief
("Do NOT apply the SKIE plugin yet — that's Task 4"), the plugin is left out here. Task 4 owns the
SKIE version gate / fallback decision. The `skie` plugin + version stay in the catalog unused.

### Compose-as-a-library (data types only, NOT shared UI)
The copied protocol layer references Compose **data types**: `@Immutable` (10 files),
`androidx.compose.ui.graphics.ImageBitmap` / `asImageBitmap` (androidMain) / `toComposeImageBitmap`
(skiaMain). To resolve these on all targets:
- Plugins applied to `:shared`: **`composeMultiplatform`** (`org.jetbrains.compose`) and
  **`composeCompiler`** (`org.jetbrains.kotlin.plugin.compose`).
- commonMain deps added: **`jetbrains-compose-runtime`**, **`jetbrains-compose-ui-graphics`**,
  **`jetbrains-compose-foundation`**.
This is Compose used purely for these data types — there are no `@Composable` screens in `shared`.

### CommonMark parser
`MarkdownPlain.kt` / `MarkdownLineBreaks.kt` import `org.intellij.markdown.*`. Added commonMain dep
**`jetbrains-markdown`** (`org.jetbrains:markdown:0.7.3`). (The plan's catalog-trim list said to drop
`jetbrains-markdown`; the copied protocol layer still uses it, so it is kept.)

### Encrypted-image pipeline — extra files copied from homebase-common
Beyond the four `HomebaseImage*.kt` named in the plan, these were copied because the pipeline
references them transitively:
- `id/homebase/core/image/FullPayloadByteCache.kt` — in-memory full-payload byte cache used by
  `HomebaseImageLoader`.
- `id/homebase/core/clipboard/ClipboardPlatformFileFactory.kt` (commonMain `expect`) + its
  `.jvm.kt` / `.native.kt` / `.android.kt` actuals — `HomebaseImageLoader.loadPendingFile` calls
  `platformFileFromPath`. (wasmJs actual deliberately not copied.)

### jvmMain — ktor-server (desktop YouAuth callback)
`jvmMain/.../browser/LocalCallbackServer.kt` (the desktop YouAuth redirect callback server, reached
via the `RedirectConfig` jvm actual) uses `io.ktor.server.*`. Added jvmMain deps + catalog entries
**`ktor-server-core`**, **`ktor-server-cio`**. JVM is a unit-test target here, not a product target;
adding the deps keeps the copied source byte-for-byte vs upstream.

### SQLDelight index relocated (consequence of deleting ChatReadCount.sq)
The kept `QueryBatch.kt` hard-references the index `idx_chatmessage_convid_userDate` via an
`INDEXED BY` hint. That index was *defined* in the deleted `ChatReadCount.sq` but indexes
`DriveMainIndex`. It was relocated into `DriveMainIndex.sq`:
`CREATE INDEX IF NOT EXISTS idx_chatmessage_convid_userDate ON DriveMainIndex(groupId,fileType,userDate DESC);`
Without it, 5 `QueryBatchCursorAdvanceTest` tests fail with `no such index`. The second
(chat-only covering) index from `ChatReadCount.sq` was NOT relocated.

### Minimal source edits beyond pure copy (kept as small as possible)
- `DatabaseManager.kt` — removed the `ChatReadCount` adapter constant, its constructor arg, its
  `TABLE_NAMES` entry, and the `chatReadCount` wrapper property (the generated `ChatReadCount` type
  no longer exists after deleting the `.sq`).
- `commonTest/TestDatabaseFactory.kt` — removed the `ChatReadCount` adapter (constant + constructor arg).
- `jvmTest/WrapperReadsRouteThroughLaneTest.kt` — removed the `chatReadCountReads_routeThroughLane`
  test method (and the now-unused `OdinId` import); kept the Outbox/KeyValue/ConnectionCache coverage.
- `DriveMainIndex.sq` — dropped the stale `-- ... created in ChatReadCount.sq` comment.

### Local maven repo (Android FFmpegKit AAR)
`settings.gradle.kts` now declares `maven { url = uri("gradle/local-repo") }`, and `gradle/local-repo/`
was copied from chat-kmp. It hosts `id.homebase.libs:ffmpeg-kit:1.0` (the Android FFmpegKit AAR),
which the androidMain source requires.

### iOS FFmpegKit test cinterop — KEPT (not dropped)
`shared/libs/ffmpegkit-bundled.xcframework` was copied from chat-kmp; the iosSimulatorArm64 **test**
cinterop + linker wiring (`src/nativeTest/cinterop/ffmpegkit.def`) is carried over verbatim. Verified:
`:shared:compileTestKotlinIosSimulatorArm64` (incl. `cinteropTestFfmpegkitIosSimulatorArm64`) builds.
This is TEST-only and does not touch the production framework export.

## Verified green

- `:shared:compileKotlinJvm` — BUILD SUCCESSFUL
- `:shared:compileAndroidMain` — BUILD SUCCESSFUL
- `:shared:compileKotlinIosSimulatorArm64` — BUILD SUCCESSFUL
- `:shared:jvmTest --tests "id.homebase.api.sync.database.*"` — 125 tests, 0 failures
- `:shared:compileTestKotlinIosSimulatorArm64` — BUILD SUCCESSFUL (FFmpegKit cinterop)
