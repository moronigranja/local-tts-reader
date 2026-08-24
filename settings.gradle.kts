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

// JVM-only module; the Android app modules arrive with the app foundation slice.
include(":core-locate")
