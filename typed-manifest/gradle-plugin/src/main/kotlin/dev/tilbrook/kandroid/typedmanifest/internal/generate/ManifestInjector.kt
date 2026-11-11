package dev.tilbrook.kandroid.typedmanifest.internal.generate

import dev.tilbrook.kandroid.typedmanifest.internal.migrate.ManifestActivityReader
import dev.tilbrook.kandroid.typedmanifest.internal.model.ManifestElement
import dev.tilbrook.kandroid.typedmanifest.internal.model.TypedManifestException

/**
 * Inserts generated entries into an already-merged manifest. Used where AGP doesn't merge
 * `variant.sources.manifests` overlays (the `com.android.kotlin.multiplatform.library` plugin).
 */
internal object ManifestInjector {

    fun inject(merged: String, entries: List<ManifestElement>): String {
        if (entries.isEmpty()) return merged
        val existing = ManifestActivityReader.read(merged).activities.mapNotNull { it.rawName }.toSet()
        val clashes = entries.mapNotNull { it.attr("name") }.filter { it in existing }
        if (clashes.isNotEmpty()) {
            throw TypedManifestException(
                clashes.map { "$it is declared both in AndroidManifest.xml and with @Manifest.Activity; remove one (see migrateManifestToAnnotations)" },
            )
        }
        val block = ManifestGenerator.renderEntries(entries, depth = 2)

        val close = merged.lastIndexOf("</application>")
        if (close >= 0) {
            val lineStart = merged.lastIndexOf('\n', close - 1) + 1
            val insertAt = if (merged.substring(lineStart, close).isBlank()) lineStart else close
            return merged.substring(0, insertAt) + block + merged.substring(insertAt)
        }
        val selfClosing = Regex("<application\\b[^>]*?/>").find(merged)
        if (selfClosing != null) {
            val open = selfClosing.value.removeSuffix("/>").trimEnd() + ">"
            return merged.replaceRange(selfClosing.range, "$open\n$block    </application>")
        }
        val manifestClose = merged.lastIndexOf("</manifest>")
        require(manifestClose >= 0) { "Merged manifest has no </manifest>" }
        return merged.substring(0, manifestClose) + "    <application>\n" + block + "    </application>\n" +
            merged.substring(manifestClose)
    }
}
