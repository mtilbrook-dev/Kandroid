package dev.tilbrook.kandroid.typedmanifest.internal.model

/**
 * A manifest XML element. Attribute keys are `android:` attribute local names
 * (e.g. `name`, `exported`), in emission order.
 */
internal data class ManifestElement(
    val tag: String,
    val attributes: List<Pair<String, String>> = emptyList(),
    val children: List<ManifestElement> = emptyList(),
) {
    fun attr(name: String): String? = attributes.firstOrNull { it.first == name }?.second
}

/** User-facing errors with source locations. */
internal class TypedManifestException(val problems: List<String>) :
    RuntimeException(problems.joinToString(separator = "\n"))
