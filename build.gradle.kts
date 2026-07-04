plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.sqldelight) apply false
    // SKIE is applied directly in :shared (Task 4), not declared here.
}

subprojects {
    configurations.all {
        resolutionStrategy {
            // Force the encrypted sqlite-jdbc everywhere (used by the JVM test driver).
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
