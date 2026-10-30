import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

extensions.configure<ApplicationExtension> {
    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }
    namespace = "com.korgnote.androidapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()

        // Raise versionCode on every release: Android refuses a lower one over a higher one.
        versionCode = 7
        versionName = "1.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release key lives in KeePassXC, and scripts/release.sh hands it over as these properties;
    // without them a release build comes out unsigned, as before.
    val releaseStoreFile = providers.gradleProperty("KORGNOTE_STORE_FILE").orNull
    if (releaseStoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("KORGNOTE_STORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("KORGNOTE_KEY_ALIAS").orNull ?: "korgnote"
                keyPassword = providers.gradleProperty("KORGNOTE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        // A debug build is its own app beside the release one: signed with another key, it could
        // not be installed over it.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    // Without the dependency list AGP otherwise signs into the APK, encrypted with a key only Google
    // Play can read: the APK goes out on GitHub, where it is only an opaque blob. A bundle, which only
    // Play takes, keeps it.
    dependenciesInfo {
        includeInApk = false
    }

}

dependencies {
    implementation(projects.composeApp)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)

    implementation(libs.koin.core)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)

    // The home-screen widgets, which read the agenda and sync the notes with the app closed.
    implementation(projects.shared)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.work.runtime)
}