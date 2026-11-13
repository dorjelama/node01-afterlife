plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.node01.afterlife"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        // Its own app, installed alongside the NODE webcam (dev.node01.afterlife,
        // on feature/webcam). The Kotlin namespace stays shared.
        applicationId = "dev.node01.companion"
        // The target hardware is a Redmi Note 8 on Android 11 (API 30). Pinning
        // minSdk to 30 lets us use WindowInsetsController directly with no
        // legacy systemUiVisibility fallback path.
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // MediaPipe ships native code for every ABI; the Redmi Note 8 needs only this one.
        ndk { abiFilters += "arm64-v8a" }
    }

    // The hand model is read straight out of the APK, which needs it stored uncompressed.
    androidResources { noCompress += "task" }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    // JDK 21 runs the build, but we compile to 17 bytecode. Deliberately NOT
    // using jvmToolchain(17) -- only JDK 21 is installed on this machine, and a
    // toolchain request would trigger auto-provisioning.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // No BuildConfig, no Compose, no view binding, no resource shrinking.
    buildFeatures {
        buildConfig = false
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.version", "kotlin/**", "**/*.kotlin_metadata")
        }
    }
}

// One runtime dependency: MediaPipe's on-device hand landmarker, for hand
// tracking and nothing else. kotlin-stdlib is contributed automatically by the
// Kotlin Android plugin. JUnit is test-only and never reaches the APK.
dependencies {
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    testImplementation("junit:junit:4.13.2")
}
