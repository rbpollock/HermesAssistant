plugins {
    alias(libs.plugins.android.application)
}

// Single source of truth for the app version. Bump here on each release;
// it feeds both the Android versionName and the built APK filename.
val appVersionName = "1.6.16"

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
        versionCode = 16
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
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}