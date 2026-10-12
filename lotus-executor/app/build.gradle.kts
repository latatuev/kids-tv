plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.lotus.executor"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.lotus.executor"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
