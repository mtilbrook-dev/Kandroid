package dev.tilbrook.kandroid.typedmanifest.internal.generate

import dev.tilbrook.kandroid.typedmanifest.internal.model.AnnotationSpec
import dev.tilbrook.kandroid.typedmanifest.internal.model.ManifestElement
import dev.tilbrook.kandroid.typedmanifest.internal.model.ParamKind
import dev.tilbrook.kandroid.typedmanifest.internal.model.Specs
import dev.tilbrook.kandroid.typedmanifest.internal.source.AnnotatedClass
import dev.tilbrook.kandroid.typedmanifest.internal.source.AnnotationCall
import dev.tilbrook.kandroid.typedmanifest.internal.source.AnnotationValue
import dev.tilbrook.kandroid.typedmanifest.internal.source.ArrayValue
import dev.tilbrook.kandroid.typedmanifest.internal.source.BooleanValue
import dev.tilbrook.kandroid.typedmanifest.internal.source.ClassRefValue
import dev.tilbrook.kandroid.typedmanifest.internal.source.NumberValue
import dev.tilbrook.kandroid.typedmanifest.internal.source.ParsedSource
import dev.tilbrook.kandroid.typedmanifest.internal.source.ReferenceValue
import dev.tilbrook.kandroid.typedmanifest.internal.source.StringValue

/** Converts a parsed `@Manifest.*` annotation into a [ManifestElement]. */
internal class AnnotationConverter(private val source: ParsedSource) {

    private val problems = ArrayList<String>()

    private fun problem(line: Int, message: String) {
        problems += "${source.path}:$line: $message"
    }

    /** Returns the element, or null with [problems] populated. */
    fun convertActivity(annotated: AnnotatedClass): Pair<ManifestElement?, List<String>> {
        problems.clear()
        val spec = Specs.activity
        val body = convert(annotated.annotation, spec) ?: return null to problems.toList()
        val attrs = ArrayList<Pair<String, String>>()
        attrs += "name" to annotated.binaryName
        // Secure default: always emit exported, false unless explicitly set.
        attrs += "exported" to (body.attr("exported") ?: "false")
        attrs += body.attributes.filter { it.first != "exported" }

        if (body.attributes.count { it.first == "parentActivityName" } > 1) {
            problem(annotated.annotation.line, "Set either parentActivity or parentActivityName, not both")
        }
        val element = ManifestElement("activity", attrs, body.children)
        validateActivity(element, annotated)
        return (if (problems.isEmpty()) element else null) to problems.toList()
    }

    private fun validateActivity(element: ManifestElement, annotated: AnnotatedClass) {
        val exported = element.attr("exported") == "true"
        val isLauncher = element.children.any { filter ->
            filter.tag == "intent-filter" &&
                filter.children.any { it.tag == "action" && it.attr("name") == "android.intent.action.MAIN" } &&
                filter.children.any { it.tag == "category" && it.attr("name") == "android.intent.category.LAUNCHER" }
        }
        if (isLauncher && !exported) {
            problem(
                annotated.annotation.line,
                "${annotated.simpleName} has a MAIN/LAUNCHER intent filter but is not exported. " +
                    "Set exported = true on @Manifest.Activity.",
            )
        }
    }

    private fun convert(call: AnnotationCall, spec: AnnotationSpec): ManifestElement? {
        val attrs = ArrayList<Pair<String, String>>()
        val children = ArrayList<ManifestElement>()
        val seen = HashSet<String>()
        val before = problems.size
        for (arg in call.arguments) {
            val name = arg.name
            if (name == null) {
                problem(arg.line, "Use named arguments in @Manifest.${spec.simpleName}")
                continue
            }
            val param = spec.param(name)
            if (param == null) {
                problem(arg.line, "Unknown parameter '$name' for @Manifest.${spec.simpleName}")
                continue
            }
            if (!seen.add(name)) {
                problem(arg.line, "Parameter '$name' is set more than once")
                continue
            }
            when (val kind = param.kind) {
                is ParamKind.NamedChildren -> asList(arg.value).forEach { v ->
                    string(v)?.let { children += ManifestElement(kind.tag, listOf("name" to it)) }
                }
                is ParamKind.Nested -> {
                    val nestedSpec = kind.spec()
                    val values = if (kind.repeated) asList(arg.value) else listOf(arg.value)
                    values.forEach { v ->
                        val nestedCall = v as? AnnotationCall
                        if (nestedCall == null || nestedCall.nameParts.last() != nestedSpec.simpleName) {
                            problem(v.line, "Expected Manifest.${nestedSpec.simpleName}(...) for '$name'")
                        } else {
                            convert(nestedCall, nestedSpec)?.let { children += it }
                        }
                    }
                }
                else -> scalar(kind, arg.value)?.let { attrs += param.xmlName to it }
            }
        }
        if (spec === Specs.metaData) {
            val hasName = attrs.any { it.first == "name" }
            val valueCount = attrs.count { it.first == "value" || it.first == "resource" }
            if (!hasName) problem(call.line, "Manifest.MetaData requires a name")
            if (valueCount != 1) problem(call.line, "Manifest.MetaData needs exactly one of value or resource")
        }
        if (problems.size > before) return null
        return ManifestElement(spec.tag, attrs, children)
    }

    private fun asList(value: AnnotationValue): List<AnnotationValue> =
        if (value is ArrayValue) value.items else listOf(value) // Java allows `x = "a"` for arrays

    private fun string(value: AnnotationValue): String? = when (value) {
        is StringValue -> value.value
        is ReferenceValue -> {
            val holder = value.parts.getOrNull(value.parts.size - 2)
            val constant = holder?.let { Specs.constants[it]?.get(value.parts.last()) }
            constant ?: run {
                problem(
                    value.line,
                    "Cannot resolve '${value.parts.joinToString(".")}'. Use a string literal or a " +
                        "Manifest.Action / Manifest.Category constant.",
                )
                null
            }
        }
        else -> { problem(value.line, "Expected a string"); null }
    }

    private fun scalar(kind: ParamKind, value: AnnotationValue): String? = when (kind) {
        ParamKind.Str -> string(value)
        ParamKind.Bool -> (value as? BooleanValue)?.value?.toString()
            ?: run { problem(value.line, "Expected true or false"); null }
        ParamKind.IntNum -> (value as? NumberValue)?.let { parseInt(it) }
            ?: run { if (value !is NumberValue) problem(value.line, "Expected an integer"); null }
        ParamKind.FloatNum -> (value as? NumberValue)?.let { parseFloat(it) }
            ?: run { if (value !is NumberValue) problem(value.line, "Expected a number"); null }
        is ParamKind.EnumValue -> enumValue(kind.type, value)
        is ParamKind.EnumFlags -> {
            val parts = asList(value).map { enumValue(kind.type, it) }
            if (parts.any { it == null }) null else parts.joinToString("|")
        }
        ParamKind.ClassName -> (value as? ClassRefValue)?.let { source.resolveClass(it.parts) }
            ?: run { problem(value.line, "Expected a class reference (Foo::class)"); null }
        is ParamKind.NamedChildren, is ParamKind.Nested -> error("not scalar")
    }

    private fun enumValue(type: Class<out Enum<*>>, value: AnnotationValue): String? {
        val ref = value as? ReferenceValue
        val result = ref?.let { Specs.enumManifestValue(type, it.parts.last()) }
        if (result == null) {
            problem(value.line, "Expected a Manifest.${type.simpleName} entry")
        }
        return result
    }

    private fun parseInt(value: NumberValue): String? {
        val text = value.text.replace("_", "").trimEnd('L', 'l', 'u', 'U')
        val parsed = when {
            text.startsWith("0x", true) -> text.substring(2).toLongOrNull(16)
            text.startsWith("-0x", true) -> text.substring(3).toLongOrNull(16)?.unaryMinus()
            else -> text.toLongOrNull()
        }
        if (parsed == null || parsed !in Int.MIN_VALUE..Int.MAX_VALUE) {
            problem(value.line, "Expected an integer, got ${value.text}")
            return null
        }
        return parsed.toString()
    }

    private fun parseFloat(value: NumberValue): String? {
        val text = value.text.replace("_", "").trimEnd('f', 'F', 'd', 'D')
        val parsed = text.toDoubleOrNull()
        if (parsed == null) {
            problem(value.line, "Expected a number, got ${value.text}")
            return null
        }
        return if (parsed == Math.floor(parsed) && !parsed.isInfinite()) {
            parsed.toLong().toString() + ".0"
        } else {
            parsed.toString()
        }
    }
}
