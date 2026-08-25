plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.moronigranja.localttsreader.persistence"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric runs the Room DAO/store tests against a real SQLite.
            isIncludeAndroidResources = true
            all {
                it.useJUnitPlatform()
                it.testLogging {
                    events("passed", "failed", "skipped")
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    implementation(project(":core-model"))

    testImplementation(libs.junit4)
    testImplementation(libs.vintage.engine)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Room 2.8.4's kapt processor bundles a kotlin-metadata-jvm reader capped at
// metadata 2.3.0; Kotlin 2.4.10's stdlib/coroutines on the kapt classpath carry
// 2.4.0 metadata, so processing any suspend DAO method crashes the processor.
// Force the newer reader (API-compatible) on the kapt classpaths of this module
// until Room/KSP support Kotlin 2.4 metadata (decision #22).
configurations.configureEach {
    if (name.uppercase().startsWith("KAPT")) {
        resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")
    }
}
