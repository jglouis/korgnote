plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.hot.reload) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// ktor-client-core 3.6.0 pins ws 8.20.1 (GHSA, fixed in 8.21.0); drop once ktor ships a newer pin.
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension>().resolution("ws", "8.21.0")
}

// Build tools only, nothing that ships in an app or the server: AGP 9.1.1 (its classpath and its test
// platform tools) pulls Guava 33.3.1 (GHSA-xxph-c9ww-hj94, fixed in 33.7.2) and the Kotlin plugin's
// Swift export tools pull opentelemetry-api 1.41.0 (GHSA-ccrj-gw8r-854m, fixed in 1.67.0). GitHub's
// dependency graph resolves every configuration, so they still raise alerts. Constraints only ever
// raise a version; drop these once AGP and Kotlin ship newer ones.
buildscript {
    dependencies.constraints {
        classpath("com.google.guava:guava:33.7.2-jre")
    }
}
subprojects {
    configurations.matching { it.name.startsWith("_internal-unified-test-platform") || it.name.startsWith("swiftExport") }
        .configureEach {
            dependencyConstraints.add(project.dependencies.constraints.create("com.google.guava:guava:33.7.2-jre"))
            dependencyConstraints.add(project.dependencies.constraints.create("io.opentelemetry:opentelemetry-api:1.67.0"))
        }
}