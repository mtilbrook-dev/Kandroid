package dev.tilbrook.kandroid.typedmanifest.internal.migrate

import dev.tilbrook.kandroid.typedmanifest.internal.model.MANIFEST_FQN
import dev.tilbrook.kandroid.typedmanifest.internal.model.Specs
import dev.tilbrook.kandroid.typedmanifest.internal.source.ParsedSource
import dev.tilbrook.kandroid.typedmanifest.internal.source.SourceParser
import dev.tilbrook.kandroid.typedmanifest.internal.source.SourceSyntaxException
import java.io.File

internal data class MigrationResult(
    val manifestText: String,
    /** Changed source files -> new contents. */
    val sourceChanges: Map<File, String>,
    val migrated: List<String>,
    /** Activity name -> reason it was left in the manifest. */
    val skipped: List<Pair<String, String>>,
    val notes: List<String>,
) {
    val manifestChanged get() = migrated.isNotEmpty()
}

/**
 * Moves `<activity>` declarations out of a manifest and onto their classes as `@Manifest.Activity`.
 * Activities that can't be represented exactly are left untouched and reported.
 */
internal class ManifestMigrator(
    private val sourceRoots: List<File>,
    private val namespace: String?,
) {
    private val parsedCache = HashMap<File, ParsedSource?>()
    private val allSourceFiles by lazy {
        sourceRoots.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        }.distinct()
    }

    fun migrate(manifestText: String): MigrationResult {
        val manifest = ManifestActivityReader.read(manifestText)
        val ns = namespace ?: manifest.packageName
        val skipped = ArrayList<Pair<String, String>>()
        val migrated = ArrayList<String>()
        val notes = ArrayList<String>()
        val removals = ArrayList<IntRange>()
        // file -> list of (offset, annotation text) plus whether an import is needed
        val insertions = LinkedHashMap<File, MutableList<Pair<Int, String>>>()
        val needsImport = HashSet<File>()

        for (activity in manifest.activities) {
            val label = activity.rawName ?: "<activity without android:name>"
            if (activity.unsupported != null) { skipped += label to activity.unsupported; continue }
            val raw = activity.rawName
            if (raw == null) { skipped += label to "missing android:name"; continue }
            if (raw.contains("\${")) { skipped += label to "uses a manifest placeholder"; continue }
            val fqn = when {
                raw.startsWith(".") -> ns?.let { it + raw }
                !raw.contains('.') -> ns?.let { "$it.$raw" }
                else -> raw
            }
            if (fqn == null) { skipped += label to "relative name but no namespace/package is known"; continue }

            val located = locate(fqn)
            if (located == null) { skipped += label to "class $fqn not found in source roots"; continue }
            val (file, parsed) = located
            val declaration = parsed.classes.first { it.binaryName == fqn }
            if (parsed.annotatedClasses(Specs.activity.fqName).any { it.binaryName == fqn }) {
                skipped += label to "already annotated with @Manifest.Activity"
                continue
            }

            val manifestRef = manifestRefFor(parsed)
            val renderer = AnnotationRenderer(java = parsed.isJava, manifestRef = manifestRef)
            val annotation = try {
                renderer.renderActivity(activity.element!!)
            } catch (e: AnnotationRenderer.UnsupportedException) {
                skipped += label to e.message!!
                continue
            }
            renderer.notes.forEach { notes += "$fqn: $it" }

            val lineStart = parsed.text.lastIndexOf('\n', declaration.classKeywordOffset - 1) + 1
            val indent = parsed.text.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
            val text = annotation.lines().joinToString("\n") { if (it.isEmpty()) it else indent + it } + "\n"
            insertions.getOrPut(file) { ArrayList() } += lineStart to text
            if (manifestRef == "Manifest" && !parsed.refersTo(listOf("Manifest"), MANIFEST_FQN)) needsImport += file
            removals += activity.start until activity.end
            migrated += fqn
        }

        val changes = LinkedHashMap<File, String>()
        for ((file, edits) in insertions) {
            val parsed = parsedCache[file]!!
            var text = parsed.text
            for ((offset, snippet) in edits.sortedByDescending { it.first }) {
                text = text.substring(0, offset) + snippet + text.substring(offset)
            }
            if (file in needsImport) text = addImport(text, parsed)
            changes[file] = text
        }

        var newManifest = manifestText
        for (range in removals.sortedByDescending { it.first }) {
            val (s, e) = expandToLines(newManifest, range.first, range.last + 1)
            newManifest = newManifest.substring(0, s) + newManifest.substring(e)
        }

        return MigrationResult(newManifest, changes, migrated, skipped, notes)
    }

    private fun manifestRefFor(parsed: ParsedSource): String {
        val imported = parsed.imports["Manifest"]
        val clashes = (imported != null && imported != MANIFEST_FQN) ||
            parsed.classes.any { it.simpleName == "Manifest" && !it.isNested }
        return if (clashes) MANIFEST_FQN else "Manifest"
    }

    private fun addImport(text: String, parsed: ParsedSource): String {
        val statement = "import $MANIFEST_FQN" + if (parsed.isJava) ";" else ""
        val offset = parsed.importInsertionOffset
        return when {
            offset < 0 -> "$statement\n\n$text"
            parsed.imports.isEmpty() && parsed.wildcardImports.isEmpty() ->
                text.substring(0, offset) + "\n\n" + statement + text.substring(offset)
            else -> text.substring(0, offset) + "\n" + statement + text.substring(offset)
        }
    }

    private fun parse(file: File): ParsedSource? = parsedCache.getOrPut(file) {
        try {
            SourceParser.parse(file.path, file.readText())
        } catch (e: SourceSyntaxException) {
            null
        }
    }

    private fun locate(fqn: String): Pair<File, ParsedSource>? {
        val outer = fqn.substringBefore('$')
        val relative = outer.replace('.', '/')
        val direct = sourceRoots.flatMap { root -> listOf(File(root, "$relative.kt"), File(root, "$relative.java")) }
        val simple = fqn.substringAfterLast('.').substringAfterLast('$')
        val candidates = direct.filter { it.isFile } +
            allSourceFiles.filter { it !in direct && it.readText().contains(simple) }
        for (file in candidates) {
            val parsed = parse(file) ?: continue
            if (parsed.classes.any { it.binaryName == fqn }) return file to parsed
        }
        return null
    }

    /** Widen a removal to whole lines when the element sits alone on its lines. */
    private fun expandToLines(text: String, start: Int, end: Int): Pair<Int, Int> {
        var s = start
        while (s > 0 && (text[s - 1] == ' ' || text[s - 1] == '\t')) s--
        var e = end
        while (e < text.length && (text[e] == ' ' || text[e] == '\t' || text[e] == '\r')) e++
        val aloneBefore = s == 0 || text[s - 1] == '\n'
        val aloneAfter = e == text.length || text[e] == '\n'
        return if (aloneBefore && aloneAfter) s to minOf(e + 1, text.length) else start to end
    }
}
