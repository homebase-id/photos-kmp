import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
    // iOS interop polish: Flow→AsyncSequence, suspend→async. 0.10.12 supports Kotlin 2.3.21.
    alias(libs.plugins.skie)
    // Compose-as-a-library only: the copied protocol layer references Compose data types
    // (@Immutable, ImageBitmap / asImageBitmap / toComposeImageBitmap). NOT shared Compose UI.
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
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
            // Compose-as-a-library: data types only (@Immutable, ImageBitmap). NOT UI.
            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.ui.graphics)
            implementation(libs.jetbrains.compose.foundation)
            // CommonMark AST parser for MarkdownPlain / MarkdownLineBreaks (org.intellij.markdown.*).
            implementation(libs.jetbrains.markdown)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.okio.fakefilesystem)
        }
        jvmTest.dependencies {
            // Skiko is pulled transitively as `skiko-awt` only (API, no native lib). jvmTest actually
            // decodes images through the skiaMain path (createThumbnails → org.jetbrains.skia.Image),
            // which needs the host-native runtime jar (the .dylib/.so + .sha256) on the classpath.
            // Host-detected so this works on macOS dev + Linux CI; version tracks the resolved skiko.
            val hostOs = System.getProperty("os.name").lowercase()
            val hostArch = System.getProperty("os.arch").lowercase()
            val skikoTarget = when {
                hostOs.contains("mac") && hostArch.contains("aarch64") -> "macos-arm64"
                hostOs.contains("mac") -> "macos-x64"
                hostOs.contains("linux") && hostArch.contains("aarch64") -> "linux-arm64"
                hostOs.contains("linux") -> "linux-x64"
                hostOs.contains("win") -> "windows-x64"
                else -> error("Unsupported host for skiko test runtime: $hostOs/$hostArch")
            }
            implementation("org.jetbrains.skiko:skiko-awt-runtime-$skikoTarget:0.9.37.4")
        }
        androidMain.dependencies {
            implementation(libs.koin.android)   // androidContext() for platformModule()'s FileOperationsProvider
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
            // Desktop YouAuth redirect callback server (LocalCallbackServer.kt) — JVM-only.
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.sqldelight.sqlite.driver.get().toString()) {
                exclude(group = "org.xerial", module = "sqlite-jdbc")
            }
            implementation(libs.sqlite.jdbc.crypt)
            implementation(libs.metadata.extractor)
        }
    }

    // Cinterop the bundled FFmpegKit xcframework into the iOS simulator *test* compilation only
    // (FfmpegDecoderCommonTest stands up a real FFmpegKitBridge). Carried over verbatim from
    // chat-kmp homebase-api; TEST-only, never touches the production framework export above.
    val ffmpegKitBundleRoot = project.projectDir.resolve("libs/ffmpegkit-bundled.xcframework")
    val ffmpegKitSimulatorFrameworkDirs = listOf(
        "ffmpegkit",
        "libavcodec",
        "libavdevice",
        "libavfilter",
        "libavformat",
        "libavutil",
        "libswresample",
        "libswscale",
    ).map { name ->
        ffmpegKitBundleRoot.resolve("$name.xcframework/ios-arm64_x86_64-simulator").absolutePath
    }
    val ffmpegKitFrameworkDir = ffmpegKitSimulatorFrameworkDirs.first()  // ffmpegkit slice

    iosSimulatorArm64().compilations.getByName("test").cinterops.create("ffmpegkit") {
        defFile(project.file("src/nativeTest/cinterop/ffmpegkit.def"))
        compilerOpts("-F$ffmpegKitFrameworkDir")
    }
    // dyld needs a search path at runtime too; the test binary runs outside iosApp's
    // Embed Frameworks step. Absolute paths are OK — the test binary never ships.
    val testBinaryLinkerOpts = buildList {
        add("-F$ffmpegKitFrameworkDir")
        add("-framework"); add("ffmpegkit")
        ffmpegKitSimulatorFrameworkDirs.forEach { add("-rpath"); add(it) }
        add("-lsqlite3")
    }
    iosSimulatorArm64().binaries
        .getTest(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.DEBUG)
        .linkerOpts(testBinaryLinkerOpts)

    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
            }
        }
    }
}

sqldelight {
    linkSqlite.set(false)
    databases {
        create("OdinDatabase") {
            packageName.set("id.homebase.api.sync.database")
            dialect(libs.sqldelight.sqlite338.dialect)
        }
    }
}
