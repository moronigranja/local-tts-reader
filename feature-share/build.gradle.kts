plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.moronigranja.localttsreader.featureshare"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            it.testLogging {
                events("passed", "failed", "skipped")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.tooling)
    // material-icons-core for result icons.
    implementation("androidx.compose.material:material-icons-core")

    implementation(project(":core-model"))
    implementation(project(":core-locate")) // TextIndex/IndexRebuilder — must be the API visible to the resolver
    implementation(project(":core-persistence")) // AppSettings mirror (threshold, OCR langs)
    implementation(project(":core-ocr"))
    implementation(project(":feature-ocr")) // OcrEngine binding (tess-two adapter)
    // ImportModule/PersistenceModule: the single TextIndex + IndexRebuilder and
    // AppSettings singletons (the share gateway must query THE index the app
    // rebuilds, never a second instance).
    implementation(project(":feature-library"))
    implementation(project(":core-tts")) { exclude(group = "net.java.dev.jna") }

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

