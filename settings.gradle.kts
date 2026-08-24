pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "local-tts-reader"

// JVM-only modules; the Android app modules arrive with the app foundation slice.
include(":core-model")
include(":core-ebook")
include(":core-locate")
