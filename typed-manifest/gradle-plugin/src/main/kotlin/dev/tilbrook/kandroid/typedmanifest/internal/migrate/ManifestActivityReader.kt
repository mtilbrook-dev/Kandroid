package dev.tilbrook.kandroid.typedmanifest.internal.migrate

import dev.tilbrook.kandroid.typedmanifest.internal.model.ManifestElement
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/** An `<activity>` found in a manifest, with the exact text range it occupies. */
internal data class ManifestActivity(
    /** Raw `android:name` value. */
    val rawName: String?,
    /** Mirror of the XML using android: local names; null when [unsupported] is set. */
    val element: ManifestElement?,
    /** Why this activity can't be migrated automatically. */
    val unsupported: String?,
    /** Offsets of `<activity ... />` or `<activity>...</activity>` in the original text. */
    val start: Int,
    val end: Int,
)

internal data class ParsedManifest(val packageName: String?, val activities: List<ManifestActivity>)

internal object ManifestActivityReader {

    fun read(text: String): ParsedManifest {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // No DTDs / external entities.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
        val root = doc.documentElement
        val packageName = root.getAttribute("package").takeIf { it.isNotEmpty() }
        val nodes = doc.getElementsByTagName("activity")
        val spans = ActivitySpanFinder.find(text)
        check(spans.size == nodes.length) {
            "Found ${nodes.length} <activity> elements but ${spans.size} text spans; manifest layout not understood"
        }
        val activities = (0 until nodes.length).map { index ->
            val el = nodes.item(index) as Element
            val rawName = el.getAttributeNS(ANDROID_NS, "name").takeIf { it.isNotEmpty() }
            val problems = ArrayList<String>()
            val element = toElement(el, problems)
            val parentOk = (el.parentNode as? Element)?.localName == "application"
            if (!parentOk) problems += "<activity> is not a direct child of <application>"
            ManifestActivity(
                rawName = rawName,
                element = if (problems.isEmpty()) element else null,
                unsupported = problems.takeIf { it.isNotEmpty() }?.joinToString("; "),
                start = spans[index].first,
                end = spans[index].last + 1,
            )
        }
        return ParsedManifest(packageName, activities)
    }

    private fun toElement(el: Element, problems: MutableList<String>): ManifestElement {
        val attrs = ArrayList<Pair<String, String>>()
        val map = el.attributes
        for (i in 0 until map.length) {
            val a = map.item(i)
            when {
                a.namespaceURI == XMLConstants.XMLNS_ATTRIBUTE_NS_URI -> Unit
                a.namespaceURI == ANDROID_NS -> attrs += a.localName to a.nodeValue
                else -> problems += "unsupported attribute ${a.nodeName} on <${el.tagName}>"
            }
        }
        val children = ArrayList<ManifestElement>()
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            when (n.nodeType) {
                Node.ELEMENT_NODE -> children += toElement(n as Element, problems)
                Node.TEXT_NODE -> if (n.nodeValue.isNotBlank()) problems += "unexpected text inside <${el.tagName}>"
                else -> Unit // comments are dropped
            }
        }
        return ManifestElement(el.localName ?: el.tagName, attrs, children)
    }
}

/** Finds `<activity>` element ranges in raw XML text, skipping comments, CDATA and PIs. */
internal object ActivitySpanFinder {
    fun find(text: String): List<IntRange> {
        val result = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("<!--", i) -> i = skipPast(text, i, "-->")
                text.startsWith("<![CDATA[", i) -> i = skipPast(text, i, "]]>")
                text.startsWith("<?", i) -> i = skipPast(text, i, "?>")
                text.startsWith("<!", i) -> i = skipPast(text, i, ">")
                text.startsWith("<activity", i) && isNameEnd(text, i + "<activity".length) -> {
                    val (tagEnd, selfClosing) = startTagEnd(text, i)
                    val end = if (selfClosing) {
                        tagEnd
                    } else {
                        val close = findClose(text, tagEnd + 1)
                        close
                    }
                    result += i..end
                    i = end + 1
                }
                else -> i++
            }
        }
        return result
    }

    private fun isNameEnd(text: String, at: Int) =
        at < text.length && (text[at].isWhitespace() || text[at] == '>' || text[at] == '/')

    private fun skipPast(text: String, from: Int, terminator: String): Int {
        val idx = text.indexOf(terminator, from)
        require(idx >= 0) { "Unterminated XML construct at offset $from" }
        return idx + terminator.length
    }

    /** Returns the index of the closing '>' of the start tag and whether it is self-closing. */
    private fun startTagEnd(text: String, from: Int): Pair<Int, Boolean> {
        var i = from
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return i to (text[i - 1] == '/')
            }
            i++
        }
        error("Unterminated <activity> start tag")
    }

    /** Index of the final '>' of the matching `</activity>`. Activities never nest. */
    private fun findClose(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            when {
                text.startsWith("<!--", i) -> i = skipPast(text, i, "-->")
                text.startsWith("<![CDATA[", i) -> i = skipPast(text, i, "]]>")
                text.startsWith("</activity", i) && (text.getOrNull(i + 10) == '>' || text.getOrNull(i + 10)?.isWhitespace() == true) ->
                    return text.indexOf('>', i)
                else -> i++
            }
        }
        error("Missing </activity>")
    }
}
