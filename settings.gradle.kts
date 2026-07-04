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
    }
}

dependencyResolutionManagement {
    repositories {
        // Local flat maven repo carrying chat-kmp's custom artifact id.homebase.libs:ffmpeg-kit:1.0
        // (the Android FFmpegKit AAR). Copied from chat-kmp gradle/local-repo (pin e67130cd).
        maven { url = uri("gradle/local-repo") }
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
