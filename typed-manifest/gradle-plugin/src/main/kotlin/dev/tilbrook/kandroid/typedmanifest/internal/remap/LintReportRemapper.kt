package dev.tilbrook.kandroid.typedmanifest.internal.remap

import dev.tilbrook.kandroid.typedmanifest.internal.generate.SourceLocation
import dev.tilbrook.kandroid.typedmanifest.internal.json.Json
import dev.tilbrook.kandroid.typedmanifest.internal.json.JsonNumber

/**
 * Rewrites lint report locations that point into a generated manifest so they point at the
 * `@Manifest` annotation instead. Text-level edits keep each report's formatting intact.
 *
 * @param sourceLine returns line [line] (1-based) of the project-relative source [path], or null.
 */
internal class LintReportRemapper(
    private val maps: List<SourceMap>,
    private val sourceLine: (path: String, line: Int) -> String?,
) {
    /** Number of locations rewritten by the last call. */
    var remapped = 0
        private set

    private fun lookup(path: String, line: Int): Pair<SourceMap, SourceLocation>? {
        val map = maps.firstOrNull { it.isGeneratedManifest(path) } ?: return null
        // Lines without an exact entry (e.g. the <application> wrapper) map to the nearest entry above.
        val location = map.lines[line] ?: map.lines.filterKeys { it <= line }.maxByOrNull { it.key }?.value ?: return null
        return map to location
    }

    // ---- XML (lint-results-*.xml) ----

    private val issueBlock = Regex("""<issue\b[\s\S]*?</issue>""")
    private val locationTag = Regex("""<location\b[^>]*?/>""")
    private val fixTag = Regex("""\s*<fix-[\w-]+\b[^>]*?/>|\s*<fix-([\w-]+)\b[^>]*?>[\s\S]*?</fix-\1>""")

    fun remapXml(text: String): String {
        remapped = 0
        return issueBlock.replace(text) { block ->
            var primary: SourceLocation? = null
            val locations = locationTag.replace(block.value) { tag ->
                val file = attr(tag.value, "file") ?: return@replace tag.value
                val line = attr(tag.value, "line")?.toIntOrNull() ?: return@replace tag.value
                val (map, loc) = lookup(file, line) ?: return@replace tag.value
                remapped++
                if (primary == null) primary = loc
                var out = setAttr(tag.value, "file", xmlEscape(map.swapPath(xmlUnescape(file), loc.path)))
                out = setAttr(out, "line", loc.line.toString())
                removeAttr(out, "column")
            }
            val loc = primary ?: return@replace block.value
            var out = locations.replace(fixTag, "") // fixes would edit the generated file
            val code = sourceLine(loc.path, loc.line)
            if (code != null) {
                out = setAttr(out, "errorLine1", xmlEscape(code))
                out = setAttr(out, "errorLine2", xmlEscape(caret(code)))
            }
            out
        }
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\s$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

    /** Replaces an attribute's value in the first tag of [text], if the attribute is there. */
    private fun setAttr(text: String, name: String, value: String): String {
        val existing = Regex("""(\s$name=")[^"]*(")""")
        val firstTagEnd = text.indexOf('>').let { if (it < 0) text.length else it }
        val match = existing.find(text)
        if (match != null && match.range.first < firstTagEnd) {
            return text.replaceRange(match.range, match.groupValues[1] + value + match.groupValues[2])
        }
        return text
    }

    private fun removeAttr(tag: String, name: String): String = tag.replace(Regex("""\s+$name="[^"]*""""), "")

    // ---- SARIF (lint-results-*.sarif) ----

    fun remapSarif(text: String): String {
        remapped = 0
        val root = Json.parse(text)
        walk(root)
        return Json.write(root) + if (text.endsWith("\n")) "\n" else ""
    }

    @Suppress("UNCHECKED_CAST")
    private fun walk(node: Any?) {
        when (node) {
            is MutableMap<*, *> -> {
                val map = node as MutableMap<String, Any?>
                (map["physicalLocation"] as? MutableMap<String, Any?>)?.let { remapPhysical(it) }
                (map["fixes"] as? MutableList<Any?>)?.removeAll { fix -> referencesGenerated(fix) }
                map.values.forEach { walk(it) }
            }
            is List<*> -> node.forEach { walk(it) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun remapPhysical(physical: MutableMap<String, Any?>) {
        val artifact = physical["artifactLocation"] as? MutableMap<String, Any?> ?: return
        val uri = artifact["uri"] as? String ?: return
        val region = physical["region"] as? MutableMap<String, Any?>
        val line = (region?.get("startLine") as? JsonNumber)?.toInt() ?: return
        val (map, loc) = lookup(uri, line) ?: return
        remapped++
        artifact["uri"] = map.swapPath(uri, loc.path)
        val newRegion = linkedMapOf<String, Any?>("startLine" to loc.line)
        sourceLine(loc.path, loc.line)?.let { newRegion["snippet"] = linkedMapOf("text" to it) }
        physical["region"] = newRegion
        physical.remove("contextRegion")
    }

    @Suppress("UNCHECKED_CAST")
    private fun referencesGenerated(node: Any?): Boolean = when (node) {
        is Map<*, *> -> (node["uri"] as? String)?.let { uri -> maps.any { it.isGeneratedManifest(uri) } } == true ||
            node.values.any { referencesGenerated(it) }
        is List<*> -> node.any { referencesGenerated(it) }
        else -> false
    }

    // ---- Text (textReport / lint-results-*.txt) ----

    private val header = Regex("""^(.+?):(\d+):( (?:Error|Warning|Information|Hint|Fatal|Ignore): .*)$""")
    private val caretLine = Regex("""^\s*[~^]+\s*$""")

    fun remapText(text: String): String {
        remapped = 0
        val lines = text.split("\n").toMutableList()
        var i = 0
        while (i < lines.size) {
            val m = header.matchEntire(lines[i])
            val hit = m?.let { lookup(it.groupValues[1], it.groupValues[2].toInt()) }
            if (m != null && hit != null) {
                val (map, loc) = hit
                remapped++
                lines[i] = map.swapPath(m.groupValues[1], loc.path) + ":" + loc.line + ":" + m.groupValues[3]
                val code = sourceLine(loc.path, loc.line)
                if (code != null && i + 2 < lines.size && caretLine.matches(lines[i + 2])) {
                    lines[i + 1] = code
                    lines[i + 2] = caret(code)
                    i += 2
                }
            }
            i++
        }
        return lines.joinToString("\n")
    }

    private fun caret(code: String) = " ".repeat(code.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)) + "^"

    private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private fun xmlUnescape(s: String) = s.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
