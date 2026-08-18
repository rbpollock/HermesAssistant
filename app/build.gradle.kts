plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ------------------------------------------------------------------
// Version from git tags (single source of truth = release tags).
// The latest tag (e.g. "v1.6.21") becomes versionName "1.6.21" and
// the APK filename HermesAssistant-v1.6.21.apk; versionCode is
// derived from the numeric parts (1*10000 + 6*100 + 21 = 10621) so it
// strictly increases with every release.
//
// Uses `git tag --sort=-v:refname` (VERSION sort, newest first) NOT
// `git describe`, which picks the lexicographically-first tag when
// several point at the same commit ("v1.6.21" < "v1.6.99-test").
// providers.exec keeps this config-cache compatible (Gradle 9 forbids
// raw ProcessBuilder at configuration time).
// ------------------------------------------------------------------
val appVersionName = providers.exec {
    commandLine("git", "tag", "--sort=-v:refname")
}.standardOutput.asText.get()
    .lineSequence()
    .firstOrNull { it.isNotBlank() }
    ?.trim()
    ?.removePrefix("v")
    ?: "0.0.0"

val appVersionCode = run {
    val parts = appVersionName.split(".").mapNotNull { it.toIntOrNull() }
    if (parts.size >= 3) parts[0] * 10000 + parts[1] * 100 + parts[2]
    else 1
}

android {
    namespace = "com.example.hermesassistant"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.hermesassistant"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Name the built APK HermesAssistant-v{versionName}.apk instead of the
// generic app-debug.apk / app-release.apk, so the file that lands on the
// phone (and in GitHub releases) carries its version.
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("HermesAssistant-v$appVersionName.apk")
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Compose (Phase 1: added alongside legacy Views; interop is fine
    // until the migration completes)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}