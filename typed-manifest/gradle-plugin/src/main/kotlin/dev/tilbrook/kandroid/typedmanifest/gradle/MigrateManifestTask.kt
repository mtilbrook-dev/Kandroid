package dev.tilbrook.kandroid.typedmanifest.gradle

import dev.tilbrook.kandroid.typedmanifest.internal.migrate.ManifestMigrator
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option

/**
 * Moves `<activity>` entries from the module's AndroidManifest.xml onto their classes as
 * `@Manifest.Activity` annotations. Entries that can't be represented exactly (tools: attributes,
 * resource-valued booleans, placeholders, classes outside this module) are left in place and reported.
 *
 * `./gradlew :app:migrateManifestToAnnotations [--dry-run]`
 */
@UntrackedTask(because = "Rewrites source files in place")
abstract class MigrateManifestTask : DefaultTask() {

    @get:Internal
    abstract val manifestFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceRoots: ConfigurableFileCollection

    /** Used to resolve relative `android:name` values; falls back to the manifest's `package`. */
    @get:Input
    @get:Optional
    abstract val namespace: Property<String>

    @get:Input
    @get:Option(option = "dry-run", description = "Report what would change without writing files.")
    abstract val dryRun: Property<Boolean>

    init {
        dryRun.convention(false)
        group = "typed manifest"
        description = "Moves <activity> entries from AndroidManifest.xml onto @Manifest.Activity annotations."
    }

    @TaskAction
    fun migrate() {
        val manifest = manifestFile.get().asFile
        if (!manifest.isFile) throw GradleException("Manifest not found: $manifest")
        val roots = sourceRoots.files.filter { it.isDirectory }
        val result = try {
            ManifestMigrator(roots, namespace.orNull).migrate(manifest.readText())
        } catch (e: Exception) {
            throw GradleException("Could not migrate $manifest: ${e.message}", e)
        }

        val dry = dryRun.get()
        if (!dry) {
            result.sourceChanges.forEach { (file, text) -> file.writeText(text) }
            if (result.manifestChanged) manifest.writeText(result.manifestText)
        }

        val verb = if (dry) "Would migrate" else "Migrated"
        logger.lifecycle("$verb ${result.migrated.size} activit${if (result.migrated.size == 1) "y" else "ies"} from $manifest")
        result.migrated.forEach { logger.lifecycle("  + $it") }
        result.sourceChanges.keys.forEach { logger.lifecycle("  ~ ${it.path}") }
        if (result.skipped.isNotEmpty()) {
            logger.warn("Left ${result.skipped.size} in the manifest:")
            result.skipped.forEach { (name, reason) -> logger.warn("  - $name: $reason") }
        }
        result.notes.forEach { logger.warn("  ! $it") }
    }
}
