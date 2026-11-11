plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.hot.reload) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kmp.library) apply false
}

// ktor-client-core 3.6.1 pins ws 8.20.1 (GHSA, fixed in 8.21.0); drop once ktor ships a newer pin.
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension>().resolution("ws", "8.21.0")
}

// Build tools only, nothing that ships in an app or the server. GitHub's dependency graph resolves every
// configuration, so these still raise alerts:
// - AGP 9.3.3 (its classpath, its lint tool and its test platform tools) pulls Guava 32.0.1 and 33.4.0
//   (GHSA-xxph-c9ww-hj94, fixed in 33.7.2) and Bouncy Castle 1.79 (GHSA-p93r-85wp-75v3 and
//   GHSA-c3fc-8qff-9hwx, fixed in 1.80.2). bcpkix and bcutil move with bcprov so the three stay on one release.
// - The Kotlin plugin's Swift export tools pull opentelemetry-api 1.41.0 (GHSA-ccrj-gw8r-854m, fixed in 1.67.0).
// Constraints only ever raise a version; drop these once AGP and Kotlin ship newer ones. AGP 9.3 renamed
// its test platform configurations from "_internal-unified-test-platform…" to "unified-test-platform…";
// both are matched.
val buildToolPins = listOf(
    "com.google.guava:guava:33.7.2-jre",
    "org.bouncycastle:bcprov-jdk18on:1.80.2",
    "org.bouncycastle:bcpkix-jdk18on:1.80.2",
    "org.bouncycastle:bcutil-jdk18on:1.80.2",
    "io.opentelemetry:opentelemetry-api:1.67.0",
)
buildscript {
    dependencies.constraints {
        classpath("com.google.guava:guava:33.7.2-jre")
        classpath("org.bouncycastle:bcprov-jdk18on:1.80.2")
        classpath("org.bouncycastle:bcpkix-jdk18on:1.80.2")
        classpath("org.bouncycastle:bcutil-jdk18on:1.80.2")
    }
}
fun isBuildToolConfiguration(name: String) =
    name.startsWith("_internal-unified-test-platform") || name.startsWith("unified-test-platform") ||
        name == "androidLintTool" || name.startsWith("swiftExport")
subprojects {
    configurations.matching { isBuildToolConfiguration(it.name) }.configureEach {
        buildToolPins.forEach { dependencyConstraints.add(project.dependencies.constraints.create(it)) }
    }
}
