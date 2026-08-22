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
    // Strictly-increasing code for any tag shape:
    //   "1.10.2"        -> 1*100000 + 10*1000 + 2*10 + 0 = 110020
    //   "2.0.0-alpha1"  -> 200001, "2.0.0-alpha2" -> 200002
    //   "2.0.0"         -> 200000  (final always beats its pre-releases)
    val parts = appVersionName.split(".").map { part ->
        part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    val pre = Regex("""-(?:alpha|beta|rc)(\d+)""")
        .find(appVersionName)
        ?.groupValues?.get(1)
        ?.toIntOrNull()
        ?: 0
    major * 100000 + minor * 1000 + patch * 10 + pre
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
            // Sign release with the debug keystore — the same cert every
            // published OTA build has used (verified: v1.10.2 is
            // CN=Android Debug). An unsigned APK never installs and a
            // different cert would force an uninstall/reinstall.
            signingConfig = signingConfigs.getByName("debug")
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