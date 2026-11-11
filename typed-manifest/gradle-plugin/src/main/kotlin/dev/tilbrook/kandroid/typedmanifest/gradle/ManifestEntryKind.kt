package dev.tilbrook.kandroid.typedmanifest.gradle

/** The kind of Android module the plugin is applied to. */
enum class ModuleType(val pluginId: String) {
    ANDROID_APPLICATION("com.android.application"),
    ANDROID_LIBRARY("com.android.library"),

    /** `com.android.kotlin.multiplatform.library` */
    KMP_ANDROID_LIBRARY("com.android.kotlin.multiplatform.library"),

    /** Legacy `org.jetbrains.kotlin.multiplatform` + `com.android.application`. */
    KMP_ANDROID_APPLICATION("com.android.application"),

    /** Legacy `org.jetbrains.kotlin.multiplatform` + `com.android.library`. */
    KMP_ANDROID_LEGACY_LIBRARY("com.android.library"),
    ;

    val isMultiplatform: Boolean get() = this == KMP_ANDROID_LIBRARY || this == KMP_ANDROID_APPLICATION || this == KMP_ANDROID_LEGACY_LIBRARY

    /** Configuration that compile-only dependencies for Android sources go into. */
    val compileOnlyConfiguration: String get() = if (isMultiplatform) "androidMainCompileOnly" else "compileOnly"
}

/**
 * A manifest entry type the plugin can generate. Each kind declares which module types it is valid
 * for and which compile-time artifact provides its annotation. Only [Activity] exists today;
 * services, receivers, providers and application-level entries slot in here.
 */
sealed class ManifestEntryKind(
    val id: String,
    val supportedModuleTypes: Set<ModuleType>,
    /** `group:name` of the annotations artifact; the plugin version is appended. */
    val annotationModule: String,
    /** `group:name` of the lint checks for this kind, added to `lintChecks`. */
    val lintModule: String?,
) {
    data object Activity : ManifestEntryKind(
        id = "activity",
        supportedModuleTypes = ModuleType.entries.toSet(),
        annotationModule = ANNOTATIONS_MODULE,
        lintModule = LINT_MODULE,
    )

    companion object {
        const val ANNOTATIONS_MODULE = "dev.tilbrook:typed-manifest-annotations"
        const val LINT_MODULE = "dev.tilbrook:typed-manifest-lint"
        val all: List<ManifestEntryKind> get() = listOf(Activity)
    }
}
