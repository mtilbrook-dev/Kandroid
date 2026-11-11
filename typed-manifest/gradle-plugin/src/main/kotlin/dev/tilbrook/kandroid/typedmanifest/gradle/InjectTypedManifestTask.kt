package dev.tilbrook.kandroid.typedmanifest.gradle

import dev.tilbrook.kandroid.typedmanifest.internal.generate.GeneratorOptions
import dev.tilbrook.kandroid.typedmanifest.internal.generate.ManifestGenerator
import dev.tilbrook.kandroid.typedmanifest.internal.generate.ManifestInjector
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
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Transforms AGP's merged manifest, adding `@Manifest.*` entries. Used for
 * `com.android.kotlin.multiplatform.library`, whose manifest merge ignores `sources.manifests`.
 */
@CacheableTask
abstract class InjectTypedManifestTask : DefaultTask() {

    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectories: ConfigurableFileCollection

    @get:Input
    abstract val activitiesEnabled: Property<Boolean>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val updatedManifest: RegularFileProperty

    @TaskAction
    fun inject() {
        val files = sourceDirectories.asFileTree.matching { it.include("**/*.kt", "**/*.java") }.files.sorted()
        val result = try {
            val entries = ManifestGenerator.collect(
                files.asSequence().map { SourceInput(it.path, it.readText()) },
                GeneratorOptions(activities = activitiesEnabled.get()),
            )
            ManifestInjector.inject(mergedManifest.get().asFile.readText(), entries)
        } catch (e: TypedManifestException) {
            throw GradleException("Invalid @Manifest annotations:\n" + e.problems.joinToString("\n") { "  $it" })
        }
        updatedManifest.get().asFile.apply { parentFile.mkdirs() }.writeText(result)
    }
}
