plugins {
    alias(libs.plugins.androidApplication)
    // AGP 9 has built-in Kotlin — no kotlin.android plugin. Compose compiler enables @Composable.
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "id.homebase.photos.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "id.homebase.photos"
        minSdk = 28
        targetSdk = 36
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Opt-in single-ABI build for low-disk on-device QA: -PdeviceAbi=arm64-v8a.
        // No-op for normal builds, which keep all ABIs.
        (project.findProperty("deviceAbi") as String?)?.let { ndk { abiFilters += it } }
    }
    buildFeatures { compose = true }

    // ponytail: release signing only exists when CI exports the keystore env (env var names
    // match photo-app's release workflow). Local `assembleRelease` stays unsigned, as before.
    val ciKeystore = System.getenv("SIGNING_KEYSTORE_FILE_PATH")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (ciKeystore != null) create("release") {
            storeFile = file(ciKeystore)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }
    buildTypes {
        release { if (ciKeystore != null) signingConfig = signingConfigs.getByName("release") }
    }
}

kotlin {
    // CoilSetup builds HomebaseImageData with kotlin.uuid.Uuid (ExperimentalUuidApi).
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.composeLifecycle.viewmodel)
    implementation(libs.androidx.composeLifecycle.runtime)
    implementation(libs.koin.android)
    implementation(libs.coil3.compose)
    // Viewer video playback (Batch B): decrypt-to-temp files played via ExoPlayer + PlayerView.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.splashscreen)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
