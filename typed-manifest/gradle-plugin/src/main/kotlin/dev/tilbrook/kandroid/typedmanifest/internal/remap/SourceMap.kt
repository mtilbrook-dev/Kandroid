package dev.tilbrook.kandroid.typedmanifest.internal.remap

import dev.tilbrook.kandroid.typedmanifest.internal.generate.SourceLocation
import dev.tilbrook.kandroid.typedmanifest.internal.json.Json
import dev.tilbrook.kandroid.typedmanifest.internal.json.JsonNumber
import java.io.File

/**
 * Maps lines of a generated manifest back to the `@Manifest` annotation arguments that produced
 * them. Paths are relative to the module's project directory.
 */
internal data class SourceMap(
    val generatedManifest: String,
    val lines: Map<Int, SourceLocation>,
) {
    fun toJson(): String = Json.write(
        linkedMapOf(
            "version" to 1,
            "generatedManifest" to generatedManifest,
            "lines" to lines.entries.associate { (line, loc) ->
                line.toString() to linkedMapOf("file" to loc.path, "line" to loc.line)
            },
        ),
    ) + "\n"

    /** True if [path] (absolute, project-relative or root-relative; any separators) is the generated manifest. */
    fun isGeneratedManifest(path: String): Boolean = normalise(path).endsWith(normalise(generatedManifest))

    /** [path] with the generated-manifest suffix swapped for [sourcePath], keeping whatever prefix it had. */
    fun swapPath(path: String, sourcePath: String): String {
        val n = normalise(path)
        val prefix = n.removeSuffix(normalise(generatedManifest))
        val separator = if ('\\' in path && '/' !in path.removePrefix(prefix)) "\\" else "/"
        return prefix + sourcePath.replace("/", separator)
    }

    companion object {
        fun normalise(path: String) = path.replace('\\', '/')

        fun read(file: File): SourceMap {
            @Suppress("UNCHECKED_CAST")
            val root = Json.parse(file.readText()) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val lines = (root["lines"] as Map<String, Map<String, Any?>>).entries.associate { (k, v) ->
                k.toInt() to SourceLocation(v["file"] as String, (v["line"] as JsonNumber).toInt()!!)
            }
            return SourceMap(root["generatedManifest"] as String, lines)
        }
    }
}
