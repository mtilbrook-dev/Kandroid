package dev.tilbrook.kandroid.typedmanifest.internal.model

/**
 * A manifest XML element. Attribute keys are `android:` attribute local names
 * (e.g. `name`, `exported`), in emission order.
 */
internal data class ManifestElement(
    val tag: String,
    val attributes: List<Pair<String, String>> = emptyList(),
    val children: List<ManifestElement> = emptyList(),
    /** Where this element was declared in source; null for elements read from XML. */
    val source: SourceRef? = null,
) {
    fun attr(name: String): String? = attributes.firstOrNull { it.first == name }?.second
}

/**
 * A source position for a generated element: the annotation (or nested call) line, plus the line of
 * each argument that produced an attribute, keyed by attribute name.
 */
internal data class SourceRef(
    /** Path relative to the project directory (or absolute if outside it). */
    val path: String,
    val line: Int,
    val attributeLines: Map<String, Int> = emptyMap(),
) {
    fun lineFor(attribute: String): Int = attributeLines[attribute] ?: line
}

/** User-facing errors with source locations. */
internal class TypedManifestException(val problems: List<String>) :
    RuntimeException(problems.joinToString(separator = "\n"))
