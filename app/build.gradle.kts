plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ng.ainow.nova"
    compileSdk = 35

    defaultConfig {
        applicationId = "ng.ainow.nova"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.2"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // On-device OCR. Bundled model: works offline immediately, adds a few MB.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Wake word ("Hey Nova" style): vendored openWakeWord Kotlin port (Apache-2.0) needs these.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    implementation("org.apache.commons:commons-math3:3.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
