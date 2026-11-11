package dev.tilbrook.kandroid.typedmanifest.lint

import org.w3c.dom.Document
import org.w3c.dom.Element

internal const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
internal const val ACTIVITY_ANNOTATION = "dev.tilbrook.kandroid.typedmanifest.Manifest.Activity"
internal const val INTENT_CLASS = "android.content.Intent"

internal data class FilterInfo(
    val actions: List<String>,
    val categories: List<String>,
    val autoVerify: Boolean,
)

internal data class ActivityInfo(
    val name: String,
    /** null when not specified. */
    val exported: Boolean?,
    val filters: List<FilterInfo>,
    /** `android:permission`; an exported activity guarded by a permission is a deliberate choice. */
    val permission: String? = null,
)

/** Intent filters that only work when other apps or the system can start the activity. */
internal object ExportRules {

    private val categories = mapOf(
        "android.intent.category.LAUNCHER" to "it is a launcher entry point (LAUNCHER)",
        "android.intent.category.LEANBACK_LAUNCHER" to "it is a TV launcher entry point (LEANBACK_LAUNCHER)",
        "android.intent.category.HOME" to "it is a home screen (HOME)",
        "android.intent.category.BROWSABLE" to "browsers and other apps open its links (BROWSABLE)",
        "android.intent.category.APP_BROWSER" to "it is opened as the default browser (APP_BROWSER)",
    )

    private val actions = mapOf(
        "android.intent.action.SEND" to "other apps share to it (SEND)",
        "android.intent.action.SEND_MULTIPLE" to "other apps share to it (SEND_MULTIPLE)",
        "android.intent.action.SENDTO" to "other apps send messages to it (SENDTO)",
        "android.intent.action.APPLICATION_PREFERENCES" to "system Settings opens it (APPLICATION_PREFERENCES)",
        "android.intent.action.CREATE_SHORTCUT" to "the launcher opens it to create shortcuts (CREATE_SHORTCUT)",
        "android.appwidget.action.APPWIDGET_CONFIGURE" to "the home screen opens it to configure a widget (APPWIDGET_CONFIGURE)",
        "android.service.quicksettings.action.QS_TILE_PREFERENCES" to "System UI opens it from a Quick Settings tile (QS_TILE_PREFERENCES)",
    )

    /** Why [filter] needs `exported = true`, or null if it doesn't. */
    fun reason(filter: FilterInfo): String? {
        if (filter.autoVerify) return "it handles verified App Links (autoVerify)"
        filter.categories.firstNotNullOfOrNull { categories[it] }?.let { return it }
        return filter.actions.firstNotNullOfOrNull { actions[it] }
    }

    fun reason(activity: ActivityInfo): String? = activity.filters.firstNotNullOfOrNull { reason(it) }
}

/** Reads `<activity>` entries from a manifest document (merged or source). */
internal object ManifestActivities {
    fun read(document: Document): List<ActivityInfo> {
        val root = document.documentElement ?: return emptyList()
        val pkg = root.getAttribute("package").orEmpty()
        val nodes = document.getElementsByTagName("activity")
        return (0 until nodes.length).map { i ->
            val el = nodes.item(i) as Element
            val raw = el.getAttributeNS(ANDROID_NS, "name")
            val name = if (raw.startsWith(".") && pkg.isNotEmpty()) pkg + raw else raw
            ActivityInfo(name, exported(el), filters(el), el.getAttributeNS(ANDROID_NS, "permission").ifEmpty { null })
        }
    }

    fun exported(el: Element): Boolean? = when (el.getAttributeNS(ANDROID_NS, "exported")) {
        "true" -> true
        "false" -> false
        else -> null
    }

    fun filters(el: Element): List<FilterInfo> = children(el, "intent-filter").map { filter ->
        FilterInfo(
            actions = children(filter, "action").map { it.getAttributeNS(ANDROID_NS, "name") },
            categories = children(filter, "category").map { it.getAttributeNS(ANDROID_NS, "name") },
            autoVerify = filter.getAttributeNS(ANDROID_NS, "autoVerify") == "true",
        )
    }

    private fun children(el: Element, tag: String): List<Element> {
        val out = ArrayList<Element>()
        var node = el.firstChild
        while (node != null) {
            if (node is Element && (node.localName ?: node.tagName) == tag) out += node
            node = node.nextSibling
        }
        return out
    }
}
