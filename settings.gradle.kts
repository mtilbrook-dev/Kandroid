enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kandroid"

// Shared modules used by more than one plugin live under core/ (see core/README.md).
// e.g. include(":core:source-scanner")

// typed-manifest plugin
include(":typed-manifest:annotations")
include(":typed-manifest:gradle-plugin")
