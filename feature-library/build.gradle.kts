plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.moronigranja.localttsreader.featurelibrary"
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
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // material-icons-core: the settings gear on the library top bar.
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.tooling)
    // PersistenceModule constructs the Room database; core-persistence only
    // exposes it via project(), so Room itself must be on this classpath.
    implementation(libs.room.runtime)

    // Library-row pre-generation action/progress (decisions #42) drives
    // feature-player's PregenManager; observeAsState for its LiveData.
    // WorkInfo/PregenWorker live on this module's API surface.
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation(libs.work.runtime.ktx)
    implementation(project(":feature-player"))

    implementation(project(":core-model"))
    implementation(project(":core-ebook"))
    implementation(project(":core-locate"))
    implementation(project(":core-persistence"))
    implementation(project(":core-player")) // PlayerStore binding (T4-1)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}
