package dev.tilbrook.kandroid.typedmanifest.internal.migrate

import dev.tilbrook.kandroid.typedmanifest.internal.model.AnnotationSpec
import dev.tilbrook.kandroid.typedmanifest.internal.model.ManifestElement
import dev.tilbrook.kandroid.typedmanifest.internal.model.ParamKind
import dev.tilbrook.kandroid.typedmanifest.internal.model.Specs

/** Renders manifest XML elements as `@Manifest.*` annotation source for Kotlin or Java. */
internal class AnnotationRenderer(
    private val java: Boolean,
    /** How `Manifest` is spelled in the target file: `Manifest` or the fully-qualified name. */
    private val manifestRef: String = "Manifest",
) {
    class UnsupportedException(message: String) : Exception(message)

    private sealed interface Node
    private data class Literal(val text: String) : Node
    private data class ListNode(val items: List<Node>) : Node
    private data class Call(val name: String, val args: List<Pair<String, Node>>) : Node

    /** Notes about semantic choices made while rendering (e.g. implicit export preserved). */
    val notes = ArrayList<String>()

    /** Renders the annotation for an `<activity>` element, without trailing newline. */
    fun renderActivity(element: ManifestElement): String {
        val explicitExported = element.attr("exported")
        val attrs = element.attributes.filter { it.first != "name" && it.first != "exported" }
        val args = ArrayList<Pair<String, Node>>()
        when (explicitExported) {
            "true" -> args += "exported" to Literal("true")
            "false", null -> if (explicitExported == null && element.children.any { it.tag == "intent-filter" }) {
                // Pre-API-31 implicit export: keep behaviour, but call it out.
                notes += "had intent filters without android:exported; migrated as exported = true (review this)"
                args += "exported" to Literal("true")
            }
            else -> throw UnsupportedException("android:exported=\"$explicitExported\" is not a literal boolean")
        }
        args += renderBody(element.copy(attributes = attrs), Specs.activity)
        val call = Call("$manifestRef.Activity", args)
        return "@" + if (args.isEmpty()) call.name else format(call, 0, topLevel = true)
    }

    private fun renderBody(element: ManifestElement, spec: AnnotationSpec): List<Pair<String, Node>> {
        val args = ArrayList<Pair<String, Node>>()
        val scalars = element.attributes.map { (xmlName, value) ->
            var param = spec.attributeParam(xmlName)
                ?: throw UnsupportedException("android:$xmlName on <${element.tag}> has no @Manifest equivalent")
            if (param.kind == ParamKind.ClassName) {
                param = spec.param("parentActivityName") ?: error("missing parentActivityName")
            }
            param to scalar(param.kind, value, xmlName)
        }
        // Declaration order of the annotation parameters, so output is stable regardless of XML attribute order.
        scalars.sortedBy { spec.params.indexOf(it.first) }.forEach { (param, node) -> args += param.name to node }
        val grouped = element.children.groupBy { it.tag }
        for ((tag, children) in grouped) {
            val param = spec.childParam(tag)
                ?: throw UnsupportedException("<$tag> inside <${element.tag}> is not supported")
            when (val kind = param.kind) {
                is ParamKind.NamedChildren -> args += param.name to ListNode(children.map { child ->
                    if (child.attributes.map { it.first } != listOf("name") || child.children.isNotEmpty()) {
                        throw UnsupportedException("<$tag> must only have android:name")
                    }
                    stringLiteral(child.attr("name")!!, constants = true)
                })
                is ParamKind.Nested -> {
                    val nestedSpec = kind.spec()
                    val calls = children.map { Call("$manifestRef.${nestedSpec.simpleName}", renderBody(it, nestedSpec)) }
                    if (kind.repeated) {
                        args += param.name to ListNode(calls)
                    } else {
                        if (calls.size > 1) throw UnsupportedException("more than one <$tag>")
                        args += param.name to calls.single()
                    }
                }
                else -> error("unexpected")
            }
        }
        return args
    }

    private fun scalar(kind: ParamKind, value: String, xmlName: String): Node = when (kind) {
        ParamKind.Str -> stringLiteral(value, constants = false)
        ParamKind.Bool -> if (value == "true" || value == "false") Literal(value)
            else throw UnsupportedException("android:$xmlName=\"$value\" is not a literal boolean")
        ParamKind.IntNum -> value.toIntOrNull()?.let { Literal(it.toString()) }
            ?: throw UnsupportedException("android:$xmlName=\"$value\" is not a literal integer")
        ParamKind.FloatNum -> value.toFloatOrNull()?.let { Literal(value.trim() + "f") }
            ?: throw UnsupportedException("android:$xmlName=\"$value\" is not a literal number")
        is ParamKind.EnumValue -> enumLiteral(kind.type, value, xmlName)
        is ParamKind.EnumFlags -> ListNode(value.split('|').map { enumLiteral(kind.type, it.trim(), xmlName) })
        ParamKind.ClassName, is ParamKind.NamedChildren, is ParamKind.Nested -> error("not scalar")
    }

    private fun enumLiteral(type: Class<out Enum<*>>, value: String, xmlName: String): Literal {
        val constant = Specs.enumConstantFor(type, value)
            ?: throw UnsupportedException("android:$xmlName=\"$value\" has no Manifest.${type.simpleName} entry")
        return Literal("$manifestRef.${type.simpleName}.$constant")
    }

    private fun stringLiteral(value: String, constants: Boolean): Literal {
        if (constants) {
            for ((holder, values) in Specs.constants) {
                values.entries.firstOrNull { it.value == value }?.let { return Literal("$manifestRef.$holder.${it.key}") }
            }
        }
        val escaped = buildString {
            value.forEach {
                when (it) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\t' -> append("\\t")
                    '$' -> append(if (java) "$" else "\\$")
                    else -> append(it)
                }
            }
        }
        return Literal("\"$escaped\"")
    }

    // ---- formatting ----

    private val indentUnit = "    "
    private val maxInline = 100

    private fun inline(node: Node): String = when (node) {
        is Literal -> node.text
        is ListNode -> if (java) node.items.joinToString(", ", "{", "}") { inline(it) }
            else node.items.joinToString(", ", "[", "]") { inline(it) }
        is Call -> {
            val prefix = if (java) "@" else ""
            if (node.args.isEmpty()) "$prefix${node.name}()" else
                node.args.joinToString(", ", "$prefix${node.name}(", ")") { (k, v) -> "$k = ${inline(v)}" }
        }
    }

    private fun containsCall(node: Node): Boolean = when (node) {
        is Literal -> false
        is ListNode -> node.items.any { it is Call || containsCall(it) }
        is Call -> node.args.any { it.second is Call || containsCall(it.second) }
    }

    private fun format(node: Node, depth: Int, topLevel: Boolean = false): String {
        val oneLine = inline(node)
        if (!topLevel && !containsCall(node) && oneLine.length + depth * indentUnit.length <= maxInline) return oneLine
        val inner = indentUnit.repeat(depth + 1)
        val outer = indentUnit.repeat(depth)
        // Kotlin allows trailing commas everywhere; Java annotation argument lists don't.
        val trailing = if (java) "" else ","
        return when (node) {
            is Literal -> node.text
            is ListNode -> {
                val (open, close) = if (java) "{" to "}" else "[" to "]"
                node.items.joinToString(",\n", "$open\n", "$trailing\n$outer$close") { inner + format(it, depth + 1) }
            }
            is Call -> {
                // At top level the leading '@' is added by the caller.
                val prefix = if (java && !topLevel) "@" else ""
                node.args.joinToString(",\n", "$prefix${node.name}(\n", "$trailing\n$outer)") { (k, v) ->
                    "$inner$k = ${format(v, depth + 1)}"
                }
            }
        }
    }
}
