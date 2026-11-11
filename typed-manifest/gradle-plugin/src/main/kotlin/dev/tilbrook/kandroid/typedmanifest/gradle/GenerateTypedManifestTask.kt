package dev.tilbrook.kandroid.typedmanifest.gradle

import dev.tilbrook.kandroid.typedmanifest.internal.generate.GeneratorOptions
import dev.tilbrook.kandroid.typedmanifest.internal.generate.ManifestGenerator
import dev.tilbrook.kandroid.typedmanifest.internal.generate.SourceInput
import dev.tilbrook.kandroid.typedmanifest.internal.model.TypedManifestException
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Scans Kotlin/Java sources for `@Manifest.*` annotations and writes a manifest overlay for AGP to merge. */
@CacheableTask
abstract class GenerateTypedManifestTask : DefaultTask() {

    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectories: ConfigurableFileCollection

    @get:Input
    abstract val activitiesEnabled: Property<Boolean>

    /**
     * False when `android.builtInKotlin=false` and no Kotlin plugin is applied: `.kt` files aren't
     * compiled then, so annotations in them must not produce manifest entries.
     */
    @get:Input
    abstract val includeKotlinSources: Property<Boolean>

    @get:OutputFile
    abstract val outputManifest: RegularFileProperty

    @TaskAction
    fun generate() {
        val patterns = if (includeKotlinSources.getOrElse(true)) arrayOf("**/*.kt", "**/*.java") else arrayOf("**/*.java")
        val files = sourceDirectories.asFileTree.matching { it.include(*patterns) }.files.sorted()
        val entries = try {
            ManifestGenerator.collect(
                files.asSequence().map { SourceInput(it.path, it.readText()) },
                GeneratorOptions(activities = activitiesEnabled.get()),
            )
        } catch (e: TypedManifestException) {
            throw GradleException("Invalid @Manifest annotations:\n" + e.problems.joinToString("\n") { "  $it" })
        }
        val out = outputManifest.get().asFile
        out.parentFile.mkdirs()
        out.writeText(ManifestGenerator.render(entries))
        logger.info("typed-manifest: generated {} entries into {}", entries.size, out)
    }
}
