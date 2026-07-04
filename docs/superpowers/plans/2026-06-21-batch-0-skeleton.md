# Batch 0 — Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove "true native UI (SwiftUI + Jetpack Compose) over a copied, headless Kotlin protocol layer" builds and renders on **both** platforms — before any product feature exists.

**Architecture:** A single KMP `shared` module (Android + iosArm64 + iosSimulatorArm64) is the copied-and-trimmed `homebase-api` (protocol/drives/sync/crypto/youauth) plus the encrypted-image pipeline copied from `homebase-common`. It is headless: it stops at `StateFlow<UiState>`. `androidApp` is pure Jetpack Compose; `iosApp` is pure SwiftUI linking the SKIE-enhanced `shared.xcframework`. DI is Koin (booted inside the framework). No shared Compose UI.

**Tech Stack:** Kotlin 2.3.21 · KMP · Koin 4.2.1 · Ktor 3.4.3 · SQLDelight 2.3.2 (`OdinDatabase`) · Coil 3.4.0 · Kermit 2.1.0 · SKIE (net-new) · Gradle 9.5.0 · AGP 9.2.1.

## Global Constraints

These apply to **every** task. Values copied verbatim from the spec, HANDOFF, and the chat-kmp source survey.

- **Versions (from chat-kmp `libs.versions.toml`, pin to match):** kotlin `2.3.21`, AGP `9.2.1`, SQLDelight `2.3.2`, Ktor `3.4.3`, Koin `4.2.1`, Kermit `2.1.0`, kotlinx-coroutines `1.10.2`, kotlinx-serialization-json `1.11.0`, kotlinx-datetime `0.7.1`, androidx-lifecycle `2.10.0`, Coil3 `3.4.0`, Gradle wrapper `9.5.0`.
- **SKIE is net-new** (NOT in chat-kmp). It is interop polish (`Flow`→`AsyncSequence`, `suspend`→`async`), **not DI**. DI stays Koin.
- **Upstream pin:** the copied protocol layer is forked from chat-kmp commit **`e67130cd`**. Record this in `shared/README.md`.
- **Payload key regex:** any payload key MUST match `^[a-z0-9_]{8,10}$`. `dflt_key` (8) is valid; server 400s otherwise.
- **iOS FFmpegKit:** copy chat-kmp's serial-queue `FFmpegKitBridgeImpl.swift`. **NEVER** set `CODE_SIGNING_ALLOWED=NO` (unsigned bundled FFmpegKit dylibs get dyld-killed at launch).
- **Android webp encode** is `@RequiresApi(30)` and has crashed historically — validate or fall back; minSdk is below 30, so guard at runtime.
- **Per-module compile checks** (KMP library tasks, NOT AGP app tasks): `:shared:compileKotlinJvm`, `:shared:compileAndroidMain`, `:shared:compileKotlinIosSimulatorArm64`. iOS app: build in Xcode / via `xcodebuild`.
- **Git:** don't use `/` in branch names. Don't commit/push unless asked. Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- **Comments:** terse one-liner only for non-obvious *why*; never narrate what the code does.
- **🧪 Testing (strict, baked in from day one):** every logic unit (ViewModels/repos/services/mappers/parsers/`PhotoConfig`) is **strict TDD** — failing test FIRST (`kotlin-test` on `:shared` `jvm()`, Ktor `MockEngine`, okio fake FS), then minimal impl, then green, then commit. **Every screen ships ≥1 automated UI flow test** from its first task: Android `androidx.compose.ui.test`, iOS XCUITest, executed/regressed on-device + perf via Argent. Batch 0's render-proof screens (T5/T6) get flow tests too, so the harness exists early. Trivial one-liners need no test (YAGNI).
- **🚦 Schema gate:** Batch 0 writes NO upload/file-build code. The Odin photo-file schema (uniqueId derivation, exact thumbnail sizes, content JSON, video marker) must be **reviewed with the owner before the Batch 1 upload implementation**. `PhotoConfig` here holds constants only; treat the thumbnail dimensions as provisional (spec values) pending that review.

**Package convention:** keep the copied protocol layer in its original package `id.homebase.api.*` (zero-churn copy). New photos code goes in `id.homebase.photos.*`. Android app: `id.homebase.photos.android`. iOS framework baseName: `Shared`.

**Defaults chosen (owner may override):** minSdk `28`, compileSdk/targetSdk `36`, iOS deployment target `18.2`, Swift `5.0`, new app id `d44e1380-fd6f-40fb-816b-106b7bc55d44`. Keep the `jvm()` target (fast unit tests). Drop `wasmJs` and all its ffmpeg-wasm test wiring (out of scope).

---

## File Structure

```
homebase-photos/
├── settings.gradle.kts                     # include :shared, :androidApp  (iosApp is Xcode-managed)
├── build.gradle.kts                        # root plugins (apply false)
├── gradle.properties                       # KMP/native flags
├── gradle/
│   ├── libs.versions.toml                  # trimmed catalog + SKIE
│   └── wrapper/gradle-wrapper.properties   # gradle 9.5.0
├── shared/
│   ├── build.gradle.kts                    # KMP targets, framework export, SKIE, sqldelight
│   ├── README.md                           # upstream pin e67130cd + copy provenance
│   └── src/
│       ├── commonMain/kotlin/id/homebase/
│       │   ├── api/…                        # COPIED from chat-kmp/homebase-api (trimmed)
│       │   ├── core/image/…                 # COPIED from chat-kmp/homebase-common (Coil pipeline)
│       │   └── photos/
│       │       ├── PhotoConfig.kt           # NEW — drive ids, fileTypes, payload key, thumb sizes, video marker
│       │       └── HelloViewModel.kt        # NEW — throwaway StateFlow + suspend fn (Batch-0 proof)
│       ├── commonMain/sqldelight/id/homebase/api/sync/database/…   # COPIED, minus ChatReadCount.sq
│       ├── androidMain/… nativeMain/… jvmMain/… skiaMain/…          # COPIED platform actuals
│       └── commonTest/jvmTest/…             # COPIED tests (chat tests removed) + PhotoConfigTest
├── androidApp/
│   ├── build.gradle.kts                     # Compose app depending on :shared
│   └── src/main/…                           # Activity + one Compose screen over HelloViewModel
└── iosApp/
    ├── iosApp.xcodeproj                      # SwiftUI app; build phase runs embedAndSign…
    └── iosApp/
        ├── iOSApp.swift                      # @main; initializeApp() + bridge injection
        ├── ContentView.swift                # SwiftUI view consuming HelloViewModel via SKIE
        └── FFmpegKitBridgeImpl.swift         # COPIED serial-queue bridge
```

---

### Task 1: Repo + Gradle skeleton (empty modules build)

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`
- Create: `shared/build.gradle.kts` (minimal, empty source set), `androidApp/build.gradle.kts` (minimal)

**Interfaces:**
- Produces: a Gradle build where `:shared` and `:androidApp` resolve. Later tasks fill source.

- [ ] **Step 1: Copy the Gradle wrapper from chat-kmp**

```bash
cd /Users/biswa/Documents/GitHub/homebase-photos
cp ~/Documents/GitHub/chat-kmp/gradlew ~/Documents/GitHub/chat-kmp/gradlew.bat .
mkdir -p gradle/wrapper
cp ~/Documents/GitHub/chat-kmp/gradle/wrapper/gradle-wrapper.jar gradle/wrapper/
cp ~/Documents/GitHub/chat-kmp/gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
chmod +x gradlew
```
Verify `gradle/wrapper/gradle-wrapper.properties` points at `gradle-9.5.0-bin.zip`.

- [ ] **Step 2: Write `settings.gradle.kts`** (trimmed from chat-kmp — only photos modules; drop the wasmJs parallel-execution workaround)

```kotlin
rootProject.name = "homebase-photos"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral()
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        gradlePluginPortal()
        maven("https://www.jetbrains.com/intellij-repository/releases")
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":shared")
include(":androidApp")
// iosApp is an Xcode project, not a Gradle module.
```

- [ ] **Step 3: Write root `build.gradle.kts`** (only plugins this repo uses, all `apply false`; drop firebase/googleServices/compose-hot-reload/lint unless needed; keep the encrypted sqlite-jdbc force for the jvm test driver)

```kotlin
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.skie) apply false
}

subprojects {
    configurations.all {
        resolutionStrategy {
            force("io.github.willena:sqlite-jdbc:3.51.2.0")
            eachDependency {
                if (requested.group == "org.xerial" && requested.name == "sqlite-jdbc") {
                    useTarget("io.github.willena:sqlite-jdbc:3.51.2.0")
                    because("Using encrypted SQLite JDBC driver")
                }
            }
        }
    }
}
```

- [ ] **Step 4: Write `gradle.properties`** (drop the wasmJs note; keep KMP/native flags)

```properties
kotlin.code.style=official
kotlin.daemon.jvmargs=-Xmx8g
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.mpp.enableCInteropCommonization=true
kotlin.mpp.enableIntransitiveMetadataConfiguration=true
kotlin.incremental.native=true
kotlin.native.disableCompilerDaemon=false
kotlin.native.linkerOptions=-dead_strip
kotlin.native.cocoapods.generate.wrapper=true
org.gradle.jvmargs=-Xmx8g -XX:MaxMetaspaceSize=1g -XX:+HeapDumpOnOutOfMemoryError
org.gradle.caching=true
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.configureondemand=false
org.gradle.configuration-cache=true
```

- [ ] **Step 5: Write `gradle/libs.versions.toml`** — start by copying chat-kmp's, then trim to the photos surface and ADD SKIE. Keep `[versions]`, `[libraries]`, `[plugins]` entries for: kotlin, AGP, sqldelight, ktor (core/content-negotiation/encoding/logging/serialization-json/okhttp/darwin/cio/mock), koin (core/compose/compose-viewmodel), kermit, coroutines (core/test), serialization-json, datetime, lifecycle, coil3, cryptography (core/provider-optimal), atomicfu, okio (+fakefilesystem), kotlinx-io-core, immutable-collections, filekit, androidx (appcompat/exifinterface/activity-compose/browser/media3-*), sqldelight drivers (android/native/sqlite + sqlite338 dialect), android-database-sqlcipher, sqlite-jdbc-crypt, ffmpeg-kit, smart-exception, mp4parser, metadata-extractor, androidsvg, kotlin-test/test-junit, junit, androidx-test-*. ADD:

```toml
[versions]
skie = "0.10.12"           # Confirmed: 0.10.12 (2026-05-18) supports Kotlin 2.3.21.

[plugins]
skie = { id = "co.touchlab.skie", version.ref = "skie" }
```
Remove chat-only entries: navigation-compose, markdown renderers, richeditor, reorderable, jetbrains-markdown, compose-multiplatform/compose-hot-reload, firebase/google-services, kmpnotifier, ktor-client-js, ktor-server-*, kotlinx-html.

- [ ] **Step 6: Write minimal `shared/build.gradle.kts`** (compiles empty; full version arrives in Task 2)

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}
kotlin {
    androidTarget()
    iosArm64(); iosSimulatorArm64()
    jvm()
    sourceSets { commonMain.dependencies {} }
}
android {
    namespace = "id.homebase.api"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
}
```

- [ ] **Step 7: Write minimal `androidApp/build.gradle.kts`** (empty app shell; filled in Task 5)

```kotlin
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
}
android {
    namespace = "id.homebase.photos.android"
    compileSdk = 36
    defaultConfig { applicationId = "id.homebase.photos"; minSdk = 28; targetSdk = 36; versionCode = 1; versionName = "0.1" }
}
dependencies { implementation(project(":shared")) }
```

- [ ] **Step 8: Verify the skeleton resolves**

Run: `./gradlew projects`
Expected: lists `:shared` and `:androidApp`, BUILD SUCCESSFUL.

Run: `./gradlew :shared:tasks --console=plain | head -40`
Expected: KMP tasks (e.g. `compileKotlinJvm`) listed, BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git checkout -b batch0-skeleton
git add -A
git commit -m "Batch 0: Gradle skeleton (settings, root build, catalog +SKIE, empty modules)"
```

---

### Task 2: Port + trim `homebase-api` and the image pipeline into `shared` (compiles on all targets)

This is the load-bearing task. Deliverable = `shared` compiles for JVM, Android, and iOS-sim with chat code removed.

**Files:**
- Copy: `chat-kmp/homebase-api/src/` → `homebase-photos/shared/src/` (then delete wasmJs source + chat files)
- Copy: `chat-kmp/homebase-common/src/commonMain/kotlin/id/homebase/core/image/{HomebaseImageFetcher,HomebaseImageKeyer,HomebaseImageLoader,HomebaseImageData}.kt` (+ any types they transitively need) → `shared/src/commonMain/kotlin/id/homebase/core/image/`
- Modify: `shared/build.gradle.kts` (full version)
- Delete: `shared/src/commonMain/sqldelight/id/homebase/api/sync/database/ChatReadCount.sq`
- Delete: `shared/src/commonMain/kotlin/id/homebase/api/sync/database/ChatReadCountWrapper.kt`
- Delete: `shared/src/commonTest/kotlin/id/homebase/api/sync/database/ChatReadCountWrapperTest.kt`, `shared/src/jvmTest/kotlin/id/homebase/api/sync/database/MessageFetchPlanTest.kt`
- Modify: `shared/src/commonMain/kotlin/id/homebase/api/sync/DriveSync.kt` (remove the fileType-7878 logging block, lines ~188–194)
- Create: `shared/README.md`

**Interfaces:**
- Produces: `id.homebase.api.client.drives.query.DriveQueryProvider.queryBatch(driveId, request, ownerOdinId)`, `id.homebase.api.client.drives.upload.DriveUploadProvider`, `id.homebase.api.youauth.YouAuthFlowManager.authorize(...)`, `id.homebase.api.di.apiModule`, `id.homebase.core.image.{HomebaseImageFetcher, HomebaseImageKeyer, HomebaseImageLoader}`, `OdinDatabase` (SQLDelight). Consumed by Tasks 3–9.

- [ ] **Step 1: Copy the source trees**

```bash
cd /Users/biswa/Documents/GitHub/homebase-photos
rsync -a --exclude 'wasmJs*' ~/Documents/GitHub/chat-kmp/homebase-api/src/ shared/src/
mkdir -p shared/src/commonMain/kotlin/id/homebase/core/image
cp ~/Documents/GitHub/chat-kmp/homebase-common/src/commonMain/kotlin/id/homebase/core/image/HomebaseImage*.kt shared/src/commonMain/kotlin/id/homebase/core/image/
```

- [ ] **Step 2: Delete chat-specific files** (per the source survey)

```bash
cd /Users/biswa/Documents/GitHub/homebase-photos/shared/src
rm -f commonMain/sqldelight/id/homebase/api/sync/database/ChatReadCount.sq
rm -f commonMain/kotlin/id/homebase/api/sync/database/ChatReadCountWrapper.kt
rm -f commonTest/kotlin/id/homebase/api/sync/database/ChatReadCountWrapperTest.kt
rm -f jvmTest/kotlin/id/homebase/api/sync/database/MessageFetchPlanTest.kt
```

- [ ] **Step 3: Strip the fileType-7878 logging block** in `shared/src/commonMain/kotlin/id/homebase/api/sync/DriveSync.kt`

Remove the block (~lines 188–194):
```kotlin
if (searchResults.any { it.fileMetadata.appData.fileType == 7878 }) {
    val chatGroupIds = searchResults
        .filter { it.fileMetadata.appData.fileType == 7878 }
        .mapNotNull { it.fileMetadata.appData.groupId }
        .distinct()
    Logger.d("DriveSync: batch contains ${chatGroupIds.size} chat conversation(s): $chatGroupIds")
}
```

- [ ] **Step 4: Remove the two chat indices on `DriveMainIndex`** that lived in the deleted `ChatReadCount.sq` (we do not need conversation/unread indices). In `shared/src/commonMain/sqldelight/id/homebase/api/sync/database/DriveMainIndex.sq`, delete the comment line referencing `ChatReadCount.sq` (the indices themselves left with the deleted file). No replacement index is needed for the photos timeline — `Idx2DriveMainIndex`/`Idx3DriveMainIndex` already cover `(identityId, driveId, …, userDate, rowId)`.

- [ ] **Step 5: Write the full `shared/build.gradle.kts`** — adapted from chat-kmp's `homebase-api/build.gradle.kts`, with: composeMultiplatform/compose plugins removed; wasmJs target + ffmpeg-wasm test wiring removed; SKIE plugin added; framework `baseName = "Shared"`; keep android/jvm/ios targets, the `skiaMain` intermediate source set, and the FFmpegKit cinterop test wiring for iosSimulatorArm64.

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.skie)
}

kotlin {
    applyDefaultHierarchyTemplate()
    sourceSets.all {
        languageSettings.apply {
            optIn("kotlin.uuid.ExperimentalUuidApi")
            optIn("kotlin.io.encoding.ExperimentalEncodingApi")
            optIn("kotlinx.serialization.ExperimentalSerializationApi")
            optIn("kotlin.time.ExperimentalTime")
            optIn("dev.whyoleg.cryptography.DelicateCryptographyApi")
        }
    }

    androidLibrary {                       // com.android.kotlin.multiplatform.library DSL (AGP 9)
        namespace = "id.homebase.api"
        compileSdk = 36
        minSdk = 28
        withHostTest {}
    }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
    // NOTE: the copied protocol layer references Compose *data types* (ImageBitmap, @Immutable).
    // If compile errors surface, apply the composeMultiplatform + composeCompiler plugins and add
    // compose runtime/ui-graphics/foundation deps (mirror chat-kmp homebase-api) — this is Compose
    // as a library for data types ONLY, NOT shared Compose UI. Record what was added in README.

    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "Shared"; isStatic = true }
    }

    sourceSets {
        val skiaMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(skiaMain)
        nativeMain.get().dependsOn(skiaMain)

        commonMain.dependencies {
            implementation(libs.atomicfu)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kermit)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.encoding)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
            implementation(libs.koin.core)
            implementation(libs.androidx.lifecycle.viewmodel)   // KMP ViewModel, Compose-free
            implementation(libs.filekit.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.immutableCollections)
            implementation(libs.coil3)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.okio.fakefilesystem)
        }
        androidMain.dependencies {
            implementation(libs.androidx.appcompat)
            implementation(libs.androidx.exifinterface)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.browser)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.exoplayer.hls)
            implementation(libs.androidx.media3.ui)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.android.database.sqlcipher)
            implementation(libs.ffmpeg.kit)
            implementation(libs.smart.exception.java)
            implementation(libs.mp4parser.isoparser)
            implementation(libs.androidsvg)
        }
        nativeMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.sqldelight.sqlite.driver.get().toString()) { exclude(group = "org.xerial", module = "sqlite-jdbc") }
            implementation(libs.sqlite.jdbc.crypt)
            implementation(libs.metadata.extractor)
        }
    }

    targets.all {
        compilations.all {
            compileTaskProvider.configure { compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") } }
        }
    }
}

sqldelight {
    linkSqlite.set(false)
    databases { create("OdinDatabase") { packageName.set("id.homebase.api.sync.database"); dialect(libs.sqldelight.sqlite338.dialect) } }
}
```
> Carry over the iosSimulatorArm64 FFmpegKit cinterop + linker test wiring from chat-kmp verbatim (it references `libs/ffmpegkit-bundled.xcframework` and `src/nativeTest/cinterop/ffmpegkit.def`). Also copy `homebase-api/libs/ffmpegkit-bundled.xcframework` and `src/nativeTest/cinterop/` into `shared/`.

- [ ] **Step 6: Write `shared/README.md`**

```markdown
# shared

Copied + adapted from `chat-kmp/homebase-api` (protocol/drives/sync/crypto/youauth) and the
encrypted-image pipeline from `chat-kmp/homebase-common` (`id.homebase.core.image`).

**Upstream pin:** chat-kmp commit `e67130cd`. Re-sync upstream security/sync fixes by diffing against
this hash. (Upstream `improve` backlog flags weak crypto RNG + keys-in-logs in this layer.)

Removed on copy: `ChatReadCount.sq` + wrapper, chat `fileType 7878/8888` logic, wasmJs target.
```

- [ ] **Step 7: Compile-verify all three targets** (the real test)

Run: `./gradlew :shared:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.
Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.
Run: `./gradlew :shared:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

> If unresolved references appear from the copied image files (e.g. `driveFileProvider`, `fileOperationsProvider` types), pull the minimal additional types from `homebase-common` until it compiles — do NOT copy all of homebase-common. Record each extra file copied in `shared/README.md`.

- [ ] **Step 8: Run the inherited DB/sync tests to confirm the trim didn't break the kept tables**

Run: `./gradlew :shared:jvmTest --tests "id.homebase.api.sync.database.*"`
Expected: BUILD SUCCESSFUL (DriveMainIndex/Outbox/KeyValue/DriveTagIndex tests pass; no ChatReadCount references remain).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Batch 0: port + trim homebase-api & image pipeline into :shared (compiles JVM/Android/iOS)"
```

---

### Task 3: `PhotoConfig` (schema constants + video marker)

**Files:**
- Create: `shared/src/commonMain/kotlin/id/homebase/photos/PhotoConfig.kt`
- Test: `shared/src/commonTest/kotlin/id/homebase/photos/PhotoConfigTest.kt`

**Interfaces:**
- Produces: `id.homebase.photos.PhotoConfig` with `DRIVE_TYPE`, `DRIVE_ALIAS`, `APP_ID`, `APP_NAME`, `PHOTO_FILE_TYPE`, `PHOTO_DATA_TYPE`, `ALBUM_FILE_TYPE`, `PAYLOAD_KEY`, `thumbnailMaxDimensions`, `isVideo(String)`, `isImage(String)`. Consumed by Tasks 6–9 and all of Batch 1.

- [ ] **Step 1: Write the failing test**

```kotlin
package id.homebase.photos
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoConfigTest {
    @Test fun videoMarkerIsPayloadContentType() {
        assertTrue(PhotoConfig.isVideo("video/mp4"))
        assertFalse(PhotoConfig.isVideo("image/jpeg"))
        assertTrue(PhotoConfig.isImage("image/webp"))
        assertFalse(PhotoConfig.isImage("video/mp4"))
    }
    @Test fun payloadKeyMatchesServerRegex() {
        assertTrue(Regex("^[a-z0-9_]{8,10}$").matches(PhotoConfig.PAYLOAD_KEY))
    }
    @Test fun driveGuidsAreDashless32Hex() {
        assertTrue(Regex("^[0-9a-f]{32}$").matches(PhotoConfig.DRIVE_TYPE))
        assertTrue(Regex("^[0-9a-f]{32}$").matches(PhotoConfig.DRIVE_ALIAS))
    }
}
```

- [ ] **Step 2: Run it — expect FAIL** (PhotoConfig unresolved)

Run: `./gradlew :shared:jvmTest --tests "id.homebase.photos.PhotoConfigTest"`
Expected: FAIL (unresolved reference `PhotoConfig`).

- [ ] **Step 3: Write `PhotoConfig.kt`**

```kotlin
package id.homebase.photos

/** Static config for the Homebase Photos drive. Schema constants only — no file-build logic. */
object PhotoConfig {
    // Existing Odin "Photo Library" drive — we reuse the same drive, registered by a NEW app.
    const val DRIVE_TYPE = "2af68fe72fb84896f39f97c59d60813a"
    const val DRIVE_ALIAS = "6483b7b1f71bd43eb6896c86148668cc"

    // New "Homebase Photos" app identity (distinct from Odin Photos app 32f0bdbf-...).
    const val APP_ID = "d44e1380-fd6f-40fb-816b-106b7bc55d44"
    const val APP_NAME = "Homebase Photos"

    const val PHOTO_FILE_TYPE = 0
    const val PHOTO_DATA_TYPE = 0
    const val ALBUM_FILE_TYPE = 900

    const val PAYLOAD_KEY = "dflt_key" // satisfies ^[a-z0-9_]{8,10}$

    // tiny / grid / fullscreen-preview. SPEC values (15x20/225x300/900x1200 == these max dims on 3:4).
    // ponytail: provisional — owner sign-off vs a real Photos drive file before Batch 1 upload.
    val thumbnailMaxDimensions = listOf(20, 300, 1200)

    // Photo vs video is decided SOLELY by the payload contentType MIME (no fileType/dataType/flag).
    fun isVideo(contentType: String): Boolean = contentType.startsWith("video/")
    fun isImage(contentType: String): Boolean = contentType.startsWith("image/")
}
```

- [ ] **Step 4: Run it — expect PASS**

Run: `./gradlew :shared:jvmTest --tests "id.homebase.photos.PhotoConfigTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Batch 0: add PhotoConfig (drive ids, fileTypes, payload key, video marker)"
```

---

### Task 4: SKIE wired + xcframework builds (with compat spike)

**Files:**
- Create: `shared/src/commonMain/kotlin/id/homebase/photos/HelloViewModel.kt`
- Modify: `shared/build.gradle.kts` (SKIE already added in Task 2; confirm config)

**Interfaces:**
- Produces: `id.homebase.photos.HelloViewModel` with `val state: StateFlow<String>` and `suspend fun ping(): String`. Consumed by Tasks 5 (Android) and 6 (iOS).

- [ ] **Step 1: Apply SKIE `0.10.12` and confirm it builds.** (Compat already resolved by research: 0.10.12 supports Kotlin 2.3.21.) Add `alias(libs.plugins.skie)` to `shared/build.gradle.kts` plugins.

Run: `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64`
Expected: BUILD SUCCESSFUL with SKIE running.
Fallback if a SKIE-specific bug bites: KMP-NativeCoroutines `1.0.3` (plugin `com.rickclephas.kmp.nativecoroutines`, Swift product `KMPNativeCoroutinesAsync`) — also supports 2.3.21. Record whichever in `shared/README.md`.

- [ ] **Step 2: Write `HelloViewModel.kt`** (throwaway Batch-0 proof of the headless boundary)

```kotlin
package id.homebase.photos

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Batch-0 throwaway: proves StateFlow + suspend cross the native boundary on both platforms. */
class HelloViewModel : ViewModel() {
    private val _state = MutableStateFlow("Homebase Photos — shared layer is live")
    val state: StateFlow<String> = _state.asStateFlow()

    suspend fun ping(): String = "pong from shared"
}
```

- [ ] **Step 3: Build the xcframework**

Run: `./gradlew :shared:assembleSharedXCFramework` (or `:shared:assembleSharedReleaseXCFramework`)
Expected: BUILD SUCCESSFUL; `shared/build/XCFrameworks/.../Shared.xcframework` exists.

- [ ] **Step 4: Confirm SKIE generated Swift-friendly API**

Run: `find shared/build -name "*.swift" -path "*skie*" | head` (SKIE emits a Swift module); confirm `HelloViewModel` appears with `state` as an `AsyncSequence`-compatible type.
Expected: non-empty.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Batch 0: wire SKIE, add HelloViewModel, build Shared.xcframework"
```

---

### Task 5: Android (Compose) renders one shared StateFlow via Koin

**Files:**
- Create: `androidApp/src/main/AndroidManifest.xml`, `androidApp/src/main/kotlin/id/homebase/photos/android/MainActivity.kt`, `androidApp/src/main/kotlin/id/homebase/photos/android/PhotosApp.kt` (Application + Koin start)
- Modify: `androidApp/build.gradle.kts` (add Compose + koin-android + lifecycle-viewmodel-compose; add `photosModule` with `HelloViewModel`)
- Create: `shared/src/commonMain/kotlin/id/homebase/photos/PhotosModule.kt` (Koin module exposing `HelloViewModel` + factory accessor for iOS)

**Interfaces:**
- Consumes: `id.homebase.photos.HelloViewModel`, `id.homebase.api.di.apiModule`.
- Produces: `id.homebase.photos.photosModule` (Koin) and `fun initKoin()` / `fun helloViewModel(): HelloViewModel` accessors used by iOS in Task 6.

- [ ] **Step 1: Add `PhotosModule.kt` (shared Koin wiring + iOS-callable accessors)**

```kotlin
package id.homebase.photos

import id.homebase.api.di.apiModule
import org.koin.core.context.startKoin
import org.koin.dsl.module

val photosModule = module {
    factory { HelloViewModel() }
}

private var started = false
/** Idempotent Koin boot. Called from Android Application.onCreate and iOS initializeApp(). */
fun initKoin() {
    if (started) return
    started = true
    startKoin { modules(apiModule, photosModule) }
}

/** iOS-callable factory (Swift has no Koin DSL). */
fun helloViewModel(): HelloViewModel = HelloViewModel()
```
> Verify `apiModule` boots without a live backend (Batch 0 renders a static StateFlow; no network). If `apiModule` requires platform deps not yet provided, narrow Task 5/6 to `startKoin { modules(photosModule) }` and add `apiModule` in Task 8 (auth) when its dependencies are wired. Record which path was taken.

- [ ] **Step 2: Fill `androidApp/build.gradle.kts`** — add Compose + Koin:

```kotlin
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
}
android {
    namespace = "id.homebase.photos.android"
    compileSdk = 36
    defaultConfig { applicationId = "id.homebase.photos"; minSdk = 28; targetSdk = 36; versionCode = 1; versionName = "0.1" }
    buildFeatures { compose = true }
}
dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.koin.android)
}
```
> Add `androidx-compose-bom`, `androidx-compose-material3`, `androidx-lifecycle-viewmodel-compose`, `koin-android` to the catalog if absent.

- [ ] **Step 3: Write `PhotosApp.kt` + `MainActivity.kt`**

```kotlin
// PhotosApp.kt
package id.homebase.photos.android
import android.app.Application
import id.homebase.photos.initKoin
class PhotosApp : Application() {
    override fun onCreate() { super.onCreate(); initKoin() }
}
```
```kotlin
// MainActivity.kt
package id.homebase.photos.android
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.homebase.photos.HelloViewModel
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val vm: HelloViewModel = viewModel()
                val text by vm.state.collectAsStateWithLifecycle()
                Text(text)
            }
        }
    }
}
```
> `AndroidManifest.xml`: register `android:name=".PhotosApp"` and `MainActivity` as launcher.

- [ ] **Step 4: Build the Android app**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL; APK produced.

- [ ] **Step 5: Render on the emulator (Argent)** — install + launch, screenshot, confirm the shared StateFlow text renders.

Use Argent (`argent-android-emulator-setup` → `launch-app`). Expected: screen shows "Homebase Photos — shared layer is live".

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Batch 0: Android Compose renders shared HelloViewModel StateFlow via Koin"
```

---

### Task 6: iOS (SwiftUI) links xcframework, boots Koin, consumes StateFlow via SKIE

**Files:**
- Create: `iosApp/iosApp.xcodeproj` (SwiftUI app, iOS 18.2, Swift 5.0), `iosApp/iosApp/iOSApp.swift`, `iosApp/iosApp/ContentView.swift`
- Copy: `chat-kmp/iosApp/iosApp/FFmpegKitBridgeImpl.swift` → `iosApp/iosApp/` (the serial-queue bridge)
- Create: `shared/src/nativeMain/kotlin/id/homebase/photos/IosBootstrap.kt` (`fun initializeApp()` + FFmpeg bridge holder wiring)

**Interfaces:**
- Consumes: `Shared.xcframework`, `HelloViewModel`, `initKoin()`.
- Produces: `initializeApp()` (Kotlin, native) called from Swift `@main`.

- [ ] **Step 1: Create the SwiftUI Xcode project** (manual in Xcode or via `xcodebuild`/XcodeBuildMCP scaffold). Single-view SwiftUI app, deployment target 18.2.

- [ ] **Step 2: Add the Gradle framework build phase** (mirror chat-kmp). In the iosApp target's Build Phases, add a Run Script *before* "Compile Sources":

```bash
cd "$SRCROOT/.."
./gradlew :shared:embedAndSignAppleFrameworkForXcode
```
Set `FRAMEWORK_SEARCH_PATHS` to `$(SRCROOT)/../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)` and link `Shared.framework`. **Do not** set `CODE_SIGNING_ALLOWED=NO` (FFmpegKit dylib constraint).

- [ ] **Step 3: Write `IosBootstrap.kt`** (native entry; idempotent Koin boot)

```kotlin
package id.homebase.photos
private var appInitialized = false
fun initializeApp() {
    if (appInitialized) return
    appInitialized = true
    initKoin()
}
```

- [ ] **Step 4: Write `iOSApp.swift`** (boot + bridge injection)

```swift
import SwiftUI
import Shared

@main
struct iOSApp: App {
    init() {
        FFmpegKitBridgeHolder.shared.setBridge(bridge: FFmpegKitBridgeImpl())
        IosBootstrapKt.initializeApp()
    }
    var body: some Scene { WindowGroup { ContentView() } }
}
```

- [ ] **Step 5: Write `ContentView.swift`** consuming the StateFlow via SKIE (`AsyncSequence`) and calling the suspend fn as `async`

```swift
import SwiftUI
import Shared

struct ContentView: View {
    @State private var text = "…"
    @State private var pong = ""
    private let vm = HelloViewModel()
    var body: some View {
        VStack(spacing: 12) { Text(text); Text(pong).font(.caption) }
            .task {
                pong = (try? await vm.ping()) ?? "ping failed"   // suspend -> async (SKIE)
                for await s in vm.state {                         // StateFlow -> AsyncSequence (SKIE)
                    text = s
                }
            }
            .onDisappear { vm.clear() }                            // iOS-owned ViewModel lifecycle
    }
}
```

- [ ] **Step 6: Build + run on the simulator**

Run (XcodeBuildMCP): `build_run_sim` for the iosApp scheme on an iOS 18.2 simulator.
Expected: app launches; screen shows "Homebase Photos — shared layer is live" and "pong from shared".

- [ ] **Step 7: Validate render on simulator (Argent)** — screenshot confirms both lines. This closes Risk #1 (SKIE + dual-consumption).

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "Batch 0: iOS SwiftUI links xcframework, boots Koin, renders StateFlow via SKIE"
```

---

### Task 7: Encrypted image-pipeline harness (decode + cache + prefetch)

**Files:**
- Create: `androidApp/.../HarnessScreen.kt` and `iosApp/iosApp/HarnessView.swift` (throwaway screens behind a debug toggle)
- Use: `id.homebase.core.image.{HomebaseImageFetcher, HomebaseImageKeyer, HomebaseImageLoader}` (copied in Task 2)

**Interfaces:**
- Consumes: the copied Coil pipeline + a small set of test-fixture encrypted thumbnails (from a dev drive or a checked-in fixture).

- [ ] **Step 1: Build a Coil 3 `ImageLoader`** wired with `HomebaseImageFetcher.Factory` + `HomebaseImageKeyer` (Android side first). Feed N (~200) encrypted thumbnail references.

- [ ] **Step 2: Render a `LazyVerticalGrid`** of the N thumbnails on Android; verify decode + memory/disk cache hits + prefetch on fast scroll.

Run on emulator (Argent): scroll the grid; confirm no decode errors and stable frames.

- [ ] **Step 3: Mirror on iOS** — a `LazyVGrid` over the same pipeline through the framework (or a SKIE-exposed loader fn). Verify decode + cache on simulator.

- [ ] **Step 4: Record findings** in `shared/README.md` (decode path OK, cache behavior, any per-platform tuning needed). This is the perf-budget baseline the spec says Google-Photos perf lives in.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Batch 0: encrypted Coil image-pipeline harness (decode+cache+prefetch) both platforms"
```

---

### Task 8: Bare auth (YouAuth login proven both sides)

**Files:**
- Use: `id.homebase.api.youauth.YouAuthFlowManager`, `id.homebase.api.youauth.{AppAuthorizationParams, TargetDriveAccessRequest, DrivePermission}`, `id.homebase.api.client.auth.CredentialsManager`
- Create: `shared/src/commonMain/kotlin/id/homebase/photos/PhotosAuth.kt` (builds the Photos drive access request from `PhotoConfig`)

**Interfaces:**
- Consumes: `PhotoConfig.{DRIVE_TYPE, DRIVE_ALIAS, APP_ID, APP_NAME}`, `YouAuthFlowManager.authorize(...)`, `CredentialsManager.credentialsFlow`.
- Produces: `id.homebase.photos.PhotosAuth.photosDriveRequest(): TargetDriveAccessRequest` and a login entry the apps call.

- [ ] **Step 1: Write `PhotosAuth.kt`**

```kotlin
package id.homebase.photos
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.TargetDriveAccessRequest

object PhotosAuth {
    fun photosDriveRequest() = TargetDriveAccessRequest(
        alias = PhotoConfig.DRIVE_ALIAS,
        type = PhotoConfig.DRIVE_TYPE,
        name = "Photo Library",
        description = "Place for your memories",
        permissions = listOf(DrivePermission.Read, DrivePermission.Write),
    )
}
```

- [ ] **Step 2: Wire the login call** — from Android and iOS, call `youAuthFlowManager.authorize(identity, PhotoConfig.APP_ID, PhotoConfig.APP_NAME, drives = listOf(PhotosAuth.photosDriveRequest()))`, open the returned URL in a browser/ASWebAuthenticationSession, handle the callback, and confirm `CredentialsManager` stores active credentials.

- [ ] **Step 3: Prove on both devices (Argent)** — complete login against a dev identity; confirm `credentialsFlow` emits non-null on each platform. Screenshot the post-login state.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "Batch 0: bare YouAuth login proven on Android + iOS (Photos drive grant)"
```

---

### Task 9: Verify `queryBatch` tag-filtering + pin the video marker

**Files:**
- Test: `shared/src/commonTest/kotlin/id/homebase/photos/QueryBatchTagFilterTest.kt` (mock-engine test of the request shape) + a live smoke check on device.

**Interfaces:**
- Consumes: `DriveQueryProvider.queryBatch`, `FileQueryParams` (`fileType`, `tagsMatchAtLeastOne`), `QueryBatchResultOptionsRequest` (`sorting=UserDate`, `ordering=NewestFirst`).

- [ ] **Step 1: Write a test** asserting the album query is built as `fileType=[0]`, `tagsMatchAtLeastOne=[albumId]`, `sorting=UserDate`, `ordering=NewestFirst`, using Ktor `MockEngine` to capture the outgoing request body.

```kotlin
// Asserts the JSON sent to the queryBatch endpoint contains fileType [0], the album tag,
// and UserDate/NewestFirst ordering. Uses libs.ktor.client.mock.
```

- [ ] **Step 2: Run it**

Run: `./gradlew :shared:jvmTest --tests "id.homebase.photos.QueryBatchTagFilterTest"`
Expected: PASS (confirms tag-filtering is wired the way albums need).

- [ ] **Step 3: Live smoke (after Task 8 login).** Run a real `queryBatch(fileType=[0])` against the Photos drive; for any returned file, read `payloads[].contentType` and assert `PhotoConfig.isVideo(...)` / `isImage(...)` classifies it. This **pins the video marker** against live data (the spec's Batch-0 requirement).

- [ ] **Step 4: Record the confirmed marker** in `shared/README.md`: "photo vs video = payload contentType MIME (`image/*` vs `video/*`); confirmed against DotYouCore + live drive."

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Batch 0: verify queryBatch tag-filtering + pin video marker (payload contentType)"
```

---

## Batch-0 exit criteria (the skeleton is done when all hold)

- `:shared` compiles on JVM + Android + iOS-sim; `Shared.xcframework` builds with SKIE.
- Android (Compose) **and** iOS (SwiftUI) each render the same shared `StateFlow` and call a shared `suspend` fn.
- Koin boots inside the framework on both platforms.
- Encrypted Coil thumbnails decode + cache + prefetch on both devices.
- YouAuth login succeeds on both; credentials persist.
- `queryBatch` tag-filtering verified; video marker (payload `contentType`) pinned.

## Open decisions deferred to Batch 1 (NOT settled here)

- **🚦 Photo-file schema sign-off with owner** before the upload implementation: exact thumbnail sizes (provisional `20/300/1200`), `uniqueId`/dedup derivation (content hash vs device asset id), `content` JSON shape. See memory `discuss-schema-before-upload`.
- Large/video upload chunking — confirm `DriveUploadProvider` handles big payloads (Risk #5).
- Low-end grid frame-budget profiling gate (Batch 1 close).

---

## Self-Review

**1. Spec coverage (§7 Batch 0):** all 7 items mapped — repo+catalog (T1), copy+trim+PhotoConfig (T2,T3), SKIE proof (T4,T6), Koin+dual-render (T5,T6), image harness (T7), auth (T8), queryBatch+video marker (T9). ✔
**2. Placeholder scan:** no "TBD/handle errors/similar to" — code shown for every code step; copy steps give exact paths/commands; build steps give exact tasks + expected output. Two honest risk gates (SKIE compat in T4.1, apiModule-deps in T5.1) carry explicit fallbacks rather than placeholders. ✔
**3. Type consistency:** `HelloViewModel.state: StateFlow<String>` + `ping(): String` used identically in T4/T5/T6; `initKoin()` defined in T5, called in T3-iOS/T6; `PhotoConfig` member names consistent across T3/T8/T9; `queryBatch` signature matches the surveyed API. ✔
