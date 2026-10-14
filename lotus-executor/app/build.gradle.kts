plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is configured only by an authorized build environment.
// Never commit a keystore or passwords. Use the SAME key for every release.
val releaseSigning = mapOf(
    "keystore" to System.getenv("LOTUS_EXECUTOR_KEYSTORE_PATH"),
    "storePassword" to System.getenv("LOTUS_EXECUTOR_STORE_PASSWORD"),
    "keyAlias" to System.getenv("LOTUS_EXECUTOR_KEY_ALIAS"),
    "keyPassword" to System.getenv("LOTUS_EXECUTOR_KEY_PASSWORD"),
)
val providedSigningFields = releaseSigning.values.count { !it.isNullOrBlank() }
check(providedSigningFields == 0 || providedSigningFields == releaseSigning.size) {
    "Incomplete Lotus Executor release-signing environment; unsigned fallback forbidden"
}
val releaseSigningAvailable = providedSigningFields == releaseSigning.size

// Fail closed: an unsigned release must never be mistaken for an in-place upgrade.
// The debug CI task remains available without any signing credentials.
gradle.taskGraph.whenReady {
    val releasePackaging = allTasks.any { task ->
        task.name.endsWith("Release") &&
            (task.name.startsWith("assemble") || task.name.startsWith("package") ||
             task.name.startsWith("bundle"))
    }
    check(!releasePackaging || releaseSigningAvailable) {
        "Lotus Executor release requires a persistent authorized signing key; refusing unsigned release"
    }
}

android {
    namespace = "io.lotus.executor"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.lotus.executor"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "0.9-manual-python"
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("persistentRelease") {
                storeFile = file(releaseSigning.getValue("keystore")!!)
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("persistentRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Background retries persist even if the transient intent activity exits.
dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.github.mwiede:jsch:2.28.8")
}
