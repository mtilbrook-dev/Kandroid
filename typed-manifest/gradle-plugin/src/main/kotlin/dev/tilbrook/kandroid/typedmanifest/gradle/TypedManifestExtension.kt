package dev.tilbrook.kandroid.typedmanifest.gradle

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * ```kotlin
 * typedManifest {
 *     addAnnotationDependency = true // default
 *     activities { enabled = true }  // default
 * }
 * ```
 */
abstract class TypedManifestExtension @Inject constructor(objects: ObjectFactory) {

    /** Add the annotations artifact as a compile-only dependency for each enabled entry kind. Default `true`. */
    abstract val addAnnotationDependency: Property<Boolean>

    /** Options for `@Manifest.Activity`. */
    val activities: EntryOptions = objects.newInstance(EntryOptions::class.java)

    fun activities(action: Action<in EntryOptions>) = action.execute(activities)

    internal fun options(kind: ManifestEntryKind): EntryOptions = when (kind) {
        ManifestEntryKind.Activity -> activities
    }

    init {
        addAnnotationDependency.convention(true)
        activities.enabled.convention(true)
    }
}

abstract class EntryOptions {
    /** Generate manifest entries of this kind. Default `true`. */
    abstract val enabled: Property<Boolean>
}
