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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // S22 Ultra is arm64-v8a; one ABI makes the APK-size number the
            // shipping-relevant one (decisions #30).
            abiFilters += listOf("arm64-v8a")
        }
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
    implementation(libs.kotlinx.coroutines.core) // exposed to consumers by core-tts only at runtime
    // core-tts's jar JNA has no Android natives (5.17 central jar ships none);
    // the AAR carries jni/<abi>/libjnidispatch.so, resolved via System.loadLibrary.
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    implementation(project(":core-tts")) { exclude(group = "net.java.dev.jna") } // the raw Kokoro port under test
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
