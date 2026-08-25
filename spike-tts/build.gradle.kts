plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.moronigranja.localttsreader.spiketts"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.moronigranja.localttsreader.spiketts"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        // onnxruntime-android bundles libonnxruntime.so; strip debug symbols like the app module does.
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.onnxruntime.android)
}
