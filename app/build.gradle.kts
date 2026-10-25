import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/** Machine-specific settings, git-ignored; the release key is configured here (see AGENTS.md). */
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "t7helpers.voiceinbox"
    compileSdk = 36

    defaultConfig {
        applicationId = "t7helpers.voiceinbox"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        val storeFile = localProperties.getProperty("release.storeFile")
        if (storeFile != null) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = file(localProperties.getProperty("release.storePasswordFile")).readText().trim()
                keyAlias = localProperties.getProperty("release.keyAlias")
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        debug {
            // Phones and the x86_64 emulator.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = false
            // Without the key in local.properties, release builds come out unsigned.
            signingConfig = signingConfigs.findByName("release")
            // Phones only: Vosk's native library is 10 MB per ABI.
            ndk { abiFilters += "arm64-v8a" }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    // Offline speech recognition; brings JNA and the native libvosk.so.
    implementation(libs.vosk.android)

    testImplementation(libs.junit)
    // Android's org.json is a stub in local unit tests; this is the real one.
    testImplementation(libs.json)
}
