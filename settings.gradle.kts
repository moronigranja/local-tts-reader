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

// JVM-only modules; the Android app modules arrive with the app foundation slice.
include(":core-model")
include(":core-ebook")
include(":core-locate")
include(":spike-tts")
