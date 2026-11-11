package dev.tilbrook.kandroid.typedmanifest.gradle

import dev.tilbrook.kandroid.typedmanifest.internal.remap.LintReportRemapper
import dev.tilbrook.kandroid.typedmanifest.internal.remap.SourceMap
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.File

/**
 * Runs after `lintReport<Variant>` and rewrites findings located in the generated manifest so they
 * point at the `@Manifest` annotation that produced them. Handles XML, SARIF and text reports;
 * the HTML report and the console output from `lint<Variant>` still show the generated file.
 */
@UntrackedTask(because = "Rewrites the lint report task's outputs in place")
abstract class RemapLintReportsTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceMaps: ConfigurableFileCollection

    /** The lint report task's outputs. Not an input: this task edits them in place. */
    @get:Internal
    abstract val reports: ConfigurableFileCollection

    @get:Internal
    abstract val projectDirectory: DirectoryProperty

    init {
        group = "typed manifest"
        description = "Points lint findings in the generated manifest at their @Manifest annotations."
    }

    @TaskAction
    fun remap() {
        val maps = sourceMaps.files.filter { it.isFile }.map { SourceMap.read(it) }.filter { it.lines.isNotEmpty() }
        if (maps.isEmpty()) return
        val root = projectDirectory.get().asFile
        val cache = HashMap<String, List<String>?>()
        val remapper = LintReportRemapper(maps) { path, line ->
            cache.getOrPut(path) { File(root, path).takeIf { it.isFile }?.readLines() }?.getOrNull(line - 1)
        }
        val files = reports.files.flatMap { if (it.isDirectory) it.walkTopDown().filter(File::isFile).toList() else listOf(it) }
        for (file in files.filter { it.isFile }) {
            val text = file.readText()
            val updated = when (file.extension) {
                "xml" -> remapper.remapXml(text)
                "sarif" -> remapper.remapSarif(text)
                "txt" -> remapper.remapText(text)
                else -> continue
            }
            if (remapper.remapped > 0) {
                file.writeText(updated)
                logger.info("typed-manifest: remapped {} lint locations in {}", remapper.remapped, file)
            }
        }
    }
}
