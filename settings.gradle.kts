pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "local-tts-reader"

// Pure-JVM modules (testable without the Android SDK) plus the Android
// app modules (toolchain in Docker, see tools/docker-build.sh) and the
// T3 measurement harness.
include(":core-model")
include(":core-ebook")
include(":core-locate")
include(":core-persistence")
include(":core-tts")
include(":core-player")
include(":app")
include(":feature-library")
include(":spike-tts")
include(":feature-player")
include(":core-ocr")
include(":feature-ocr")
include(":feature-settings")
