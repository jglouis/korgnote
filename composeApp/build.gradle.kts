import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.hot.reload)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        namespace = "com.korgnote.app"
        // Without it the app's strings, which Compose resources keep, are left out of the APK.
        androidResources { enable = true }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.androidx.navigationevent.compose)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.materialIconsExtended)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(projects.shared)
            implementation(libs.kotlinx.datetime)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            // Ktor client
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.compose.ui.tooling)
            implementation(libs.androidx.activity.compose)
            // Ktor Android engine (choose exactly one)
            implementation(libs.ktor.client.android)
            // WebDAV's PROPFIND and MKCOL, which HttpURLConnection, under the Android engine, refuses.
            implementation(libs.ktor.client.okhttp)
            // Ktor client logging (debug diagnostics)
            implementation(libs.ktor.client.logging)
            // Scans the pairing QR code on the device itself, without Play services.
            implementation(libs.zxing.android.embedded)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            // Compose's own test API: the drawer and the editor are where the last few regressions
            // have been, and nothing here could click a row until now.
            implementation(libs.compose.ui.test)
        }
        jvmTest.dependencies {
            // The desktop runtime the UI tests render on.
            implementation(compose.desktop.currentOs)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.java)
            // The system's keyring — macOS Keychain, Windows Credential Manager, Secret Service on
            // Linux — for the key that seals the pairing token.
            implementation(libs.java.keyring)
        }
        iosMain.dependencies {
            // Ktor iOS engine (Darwin)
            implementation(libs.ktor.client.darwin)
        }
        wasmJsMain.dependencies {
            // Ktor JS engine for Wasm/JS target
            implementation(libs.ktor.client.js)
        }
    }
}



// The UI tests look for the English words: in English, whatever language the machine is set to.
tasks.withType<Test>().configureEach {
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
}

compose.desktop {
    application {
        mainClass = "com.korgnote.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.korgnote.app"
            packageVersion = "1.5.1"
        }
    }
}
