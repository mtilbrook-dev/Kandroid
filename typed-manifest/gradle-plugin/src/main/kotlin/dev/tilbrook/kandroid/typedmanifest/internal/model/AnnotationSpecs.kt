package dev.tilbrook.kandroid.typedmanifest.internal.model

import dev.tilbrook.kandroid.typedmanifest.Manifest

/**
 * Single source of truth for how each `@Manifest.*` annotation parameter maps to manifest XML.
 * Used in both directions: annotation -> XML (generation) and XML -> annotation (migration).
 */
internal sealed interface ParamKind {
    data object Str : ParamKind
    data object Bool : ParamKind
    data object IntNum : ParamKind
    data object FloatNum : ParamKind
    data class EnumValue(val type: Class<out Enum<*>>) : ParamKind
    /** `a|b|c` flag attributes, written as an array of enum entries. */
    data class EnumFlags(val type: Class<out Enum<*>>) : ParamKind
    /** `KClass` parameter written as a fully-qualified class name. */
    data object ClassName : ParamKind
    /** Array of strings, each becoming `<tag android:name="..."/>`. */
    data class NamedChildren(val tag: String) : ParamKind
    data class Nested(val spec: () -> AnnotationSpec, val repeated: Boolean) : ParamKind
}

internal data class ParamSpec(
    val name: String,
    val kind: ParamKind,
    /** XML attribute name; defaults to [name]. */
    val xmlName: String = name,
)

internal data class AnnotationSpec(
    /** Simple name inside `Manifest`, e.g. `Activity`. */
    val simpleName: String,
    val tag: String,
    val params: List<ParamSpec>,
) {
    val fqName = "${MANIFEST_FQN}.$simpleName"
    private val byName = params.associateBy { it.name }
    fun param(name: String) = byName[name]

    /** Params that map to XML attributes, by XML attribute name. */
    fun attributeParam(xmlName: String): ParamSpec? = params.firstOrNull {
        it.xmlName == xmlName && it.kind !is ParamKind.NamedChildren && it.kind !is ParamKind.Nested
    }

    fun childParam(tag: String): ParamSpec? = params.firstOrNull {
        when (val k = it.kind) {
            is ParamKind.NamedChildren -> k.tag == tag
            is ParamKind.Nested -> k.spec().tag == tag
            else -> false
        }
    }
}

internal const val MANIFEST_FQN = "dev.tilbrook.kandroid.typedmanifest.Manifest"

internal object Specs {
    private fun str(vararg names: String) = names.map { ParamSpec(it, ParamKind.Str) }
    private fun bool(vararg names: String) = names.map { ParamSpec(it, ParamKind.Bool) }
    private inline fun <reified E : Enum<E>> enum(name: String) = ParamSpec(name, ParamKind.EnumValue(E::class.java))

    val data = AnnotationSpec(
        "Data", "data",
        str("scheme", "host", "port", "path", "pathPrefix", "pathPattern", "pathSuffix", "pathAdvancedPattern", "mimeType"),
    )

    val intentFilter = AnnotationSpec(
        "IntentFilter", "intent-filter",
        listOf(
            ParamSpec("actions", ParamKind.NamedChildren("action")),
            ParamSpec("categories", ParamKind.NamedChildren("category")),
            ParamSpec("data", ParamKind.Nested({ data }, repeated = true)),
            ParamSpec("autoVerify", ParamKind.Bool),
            ParamSpec("priority", ParamKind.IntNum),
            ParamSpec("order", ParamKind.IntNum),
        ) + str("label", "icon", "roundIcon", "banner", "logo"),
    )

    val metaData = AnnotationSpec("MetaData", "meta-data", str("name", "value", "resource"))

    val layout = AnnotationSpec("Layout", "layout", str("defaultWidth", "defaultHeight", "gravity", "minWidth", "minHeight"))

    val activity = AnnotationSpec(
        "Activity", "activity",
        listOf(ParamSpec("exported", ParamKind.Bool), ParamSpec("enabled", ParamKind.Bool)) +
            str(
                "label", "description", "icon", "roundIcon", "banner", "logo", "theme", "permission",
                "process", "taskAffinity",
            ) +
            listOf(
                ParamSpec("parentActivity", ParamKind.ClassName, xmlName = "parentActivityName"),
                ParamSpec("parentActivityName", ParamKind.Str),
                enum<Manifest.LaunchMode>("launchMode"),
                enum<Manifest.ScreenOrientation>("screenOrientation"),
                ParamSpec("configChanges", ParamKind.EnumFlags(Manifest.ConfigChange::class.java)),
                ParamSpec("windowSoftInputMode", ParamKind.EnumFlags(Manifest.WindowSoftInputMode::class.java)),
                enum<Manifest.DocumentLaunchMode>("documentLaunchMode"),
                enum<Manifest.PersistableMode>("persistableMode"),
                enum<Manifest.LockTaskMode>("lockTaskMode"),
                enum<Manifest.UiOptions>("uiOptions"),
                enum<Manifest.ColorMode>("colorMode"),
                enum<Manifest.ContentUriPermission>("requireContentUriPermissionFromCaller"),
                ParamSpec("maxRecents", ParamKind.IntNum),
                ParamSpec("maxAspectRatio", ParamKind.FloatNum),
                ParamSpec("minAspectRatio", ParamKind.FloatNum),
            ) +
            bool(
                "allowEmbedded", "allowTaskReparenting", "allowUntrustedActivityEmbedding", "alwaysRetainTaskState",
                "autoRemoveFromRecents", "canDisplayOnRemoteDevices", "clearTaskOnLaunch", "directBootAware",
                "enableOnBackInvokedCallback", "excludeFromRecents", "finishOnTaskLaunch", "hardwareAccelerated",
                "immersive", "multiprocess", "noHistory", "preferMinimalPostProcessing", "relinquishTaskIdentity",
                "resizeableActivity", "showForAllUsers", "showWhenLocked", "stateNotNeeded",
                "supportsPictureInPicture", "turnScreenOn", "visibleToInstantApps",
            ) +
            str("attributionTags", "knownActivityEmbeddingCerts", "requiredDisplayCategory", "splitName") +
            listOf(
                ParamSpec("intentFilters", ParamKind.Nested({ intentFilter }, repeated = true)),
                ParamSpec("metaData", ParamKind.Nested({ metaData }, repeated = true)),
                ParamSpec("layout", ParamKind.Nested({ layout }, repeated = false)),
            ),
    )

    /** `Manifest.Action.X` / `Manifest.Category.X` constants: simple holder name -> (const name -> value). */
    val constants: Map<String, Map<String, String>> = listOf(Manifest.Action::class.java, Manifest.Category::class.java)
        .associate { holder ->
            holder.simpleName to holder.declaredFields
                .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                .associate { it.isAccessible = true; it.name to (it.get(null) as String) }
        }

    fun enumManifestValue(type: Class<out Enum<*>>, constant: String): String? {
        val entry = type.enumConstants.firstOrNull { it.name == constant } ?: return null
        return type.getMethod("getManifestValue").invoke(entry) as String
    }

    fun enumConstantFor(type: Class<out Enum<*>>, manifestValue: String): String? =
        type.enumConstants.firstOrNull { type.getMethod("getManifestValue").invoke(it) == manifestValue }?.name

    /** Kotlin source spelling of an enum type, e.g. `Manifest.LaunchMode`. */
    fun enumTypeReference(type: Class<out Enum<*>>) = "Manifest.${type.simpleName}"
}
