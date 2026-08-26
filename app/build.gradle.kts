plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.moronigranja.localttsreader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.moronigranja.localttsreader"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(project(":core-model"))
    implementation(project(":core-ebook"))
    implementation(project(":core-locate"))
    implementation(project(":core-persistence"))
    implementation(project(":core-player")) // PlayerPhase etc. for the player surface
    implementation(project(":feature-library"))
    implementation(project(":feature-player"))
    implementation(project(":feature-settings"))
    implementation(project(":feature-ocr"))
    implementation(project(":feature-share"))
    implementation(project(":core-ocr"))
    // The engine's ORT + JNA runtimes ship app-side (decisions #25/#32): the
    // Android ORT AAR (core-tts is compileOnly) and JNA AAR (the plain jar
    // has no Android natives); core-tts's jar JNA is excluded below.
    implementation(libs.onnxruntime.android)
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.room.runtime)
    androidTestImplementation(project(":core-tts")) { exclude(group = "net.java.dev.jna") } // SegmentAnchor on the test classpath
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // core-tts's jar JNA is excluded at the feature-player seam; the AAR above is the only JNA.
}
