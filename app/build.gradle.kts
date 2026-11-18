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
        versionName = "0.1"
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
}
