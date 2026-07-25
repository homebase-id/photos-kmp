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
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Opt-in single-ABI build for low-disk on-device QA: -PdeviceAbi=arm64-v8a.
        // No-op for normal builds, which keep all ABIs.
        (project.findProperty("deviceAbi") as String?)?.let { ndk { abiFilters += it } }
    }
    buildFeatures { compose = true }
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
