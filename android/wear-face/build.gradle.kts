plugins {
    id("com.android.application")
}

// Watch Face Format: resource-only package (no Kotlin/Java).
// Must stay separate from :wear — Play/Wear OS require hasCode=false for WFF.
android {
    namespace = "io.grokify.os.wear.face"
    compileSdk = 35
    // WFF projects disable Kotlin in newer AGP; we simply ship no source sets.
    defaultConfig {
        applicationId = "io.grokify.os.wear.face"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.1"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}
