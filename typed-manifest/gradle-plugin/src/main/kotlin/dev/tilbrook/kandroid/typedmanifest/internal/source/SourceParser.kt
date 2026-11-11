package dev.tilbrook.kandroid.typedmanifest.internal.source

/** Annotation argument values as written in source. */
internal sealed interface AnnotationValue {
    val line: Int
}

internal data class StringValue(val value: String, override val line: Int) : AnnotationValue
internal data class BooleanValue(val value: Boolean, override val line: Int) : AnnotationValue
internal data class NumberValue(val text: String, override val line: Int) : AnnotationValue
internal data class ReferenceValue(val parts: List<String>, override val line: Int) : AnnotationValue
internal data class ClassRefValue(val parts: List<String>, override val line: Int) : AnnotationValue
internal data class ArrayValue(val items: List<AnnotationValue>, override val line: Int) : AnnotationValue
internal data class AnnotationCall(
    val nameParts: List<String>,
    val arguments: List<Argument>,
    override val line: Int,
) : AnnotationValue

internal data class Argument(val name: String?, val value: AnnotationValue, val line: Int)

/** A class declaration carrying the target annotation. */
internal data class AnnotatedClass(
    /** Binary name, nested classes joined with `$`, e.g. `com.example.Outer$Inner`. */
    val binaryName: String,
    val simpleName: String,
    val isNested: Boolean,
    val annotation: AnnotationCall,
    /** Offset of the `class` keyword. */
    val classKeywordOffset: Int,
)

/** A class declaration (annotated or not), used by the migrator to find insertion points. */
internal data class ClassDeclaration(
    val binaryName: String,
    val simpleName: String,
    val isNested: Boolean,
    val classKeywordOffset: Int,
)

internal class ParsedSource(
    val path: String,
    val text: String,
    val isJava: Boolean,
    val packageName: String,
    /** simple name (or alias) -> fully-qualified name */
    val imports: Map<String, String>,
    val wildcardImports: List<String>,
    /** Offset just after the last import (or package) statement, -1 if neither exists. */
    val importInsertionOffset: Int,
    val hasPackage: Boolean,
    val classes: List<ClassDeclaration>,
    private val annotationsByTarget: (String) -> List<AnnotatedClass>,
) {
    fun annotatedClasses(targetFqn: String): List<AnnotatedClass> = annotationsByTarget(targetFqn)

    /** True if [parts] (as written in source) can refer to [targetFqn]. */
    fun refersTo(parts: List<String>, targetFqn: String): Boolean {
        val written = parts.joinToString(".")
        if (written == targetFqn) return true
        imports[parts.first()]?.let { imported ->
            if ((listOf(imported) + parts.drop(1)).joinToString(".") == targetFqn) return true
        }
        val prefixes = wildcardImports + listOfNotNull(packageName.takeIf { it.isNotEmpty() })
        return prefixes.any { "$it.$written" == targetFqn } || (packageName.isEmpty() && written == targetFqn)
    }

    /** Best-effort resolution of a class reference to a fully-qualified name. */
    fun resolveClass(parts: List<String>): String {
        imports[parts.first()]?.let { return (listOf(it) + parts.drop(1)).joinToString(".") }
        val first = parts.first()
        // Looks already qualified: com.example.Foo
        if (parts.size > 1 && first.first().isLowerCase()) return parts.joinToString(".")
        // Declared in this file? Use the binary name so nested classes come out right.
        classes.firstOrNull { it.simpleName == parts.last() && parts.size == 1 }?.let { return it.binaryName }
        return if (packageName.isEmpty()) parts.joinToString(".") else "$packageName.${parts.joinToString(".")}"
    }
}

internal object SourceParser {

    private val declarationKeywords = setOf("class", "interface", "object", "enum")
    private val modifiers = setOf(
        "public", "private", "protected", "internal", "open", "final", "abstract", "sealed", "data",
        "inner", "static", "strictfp", "value", "expect", "actual", "external", "inline", "annotation",
        "fun", "non-sealed",
    )

    fun parse(path: String, text: String, isJava: Boolean = path.endsWith(".java")): ParsedSource {
        val tokens = Tokenizer.tokenize(text)
        var packageName = ""
        var hasPackage = false
        val imports = LinkedHashMap<String, String>()
        val wildcards = ArrayList<String>()
        var insertionOffset = -1
        val classes = ArrayList<ClassDeclaration>()
        val annotationSites = ArrayList<Pair<AnnotationCall, Int>>() // annotation + token index after it

        // name, brace depth of its body
        data class Frame(val name: String?, val depth: Int)
        data class Pending(val name: String?, val parenDepth: Int)

        val stack = ArrayList<Frame>()
        var pending: Pending? = null
        var braceDepth = 0
        var parenDepth = 0
        val classStackAt = HashMap<Int, List<String?>>() // annotation token index -> enclosing names

        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.type == TokenType.SYMBOL) {
                when (t.text) {
                    "{" -> {
                        val p = pending
                        if (p != null && p.parenDepth == parenDepth) {
                            stack += Frame(p.name, braceDepth + 1)
                            pending = null
                        }
                        braceDepth++
                    }
                    "}" -> {
                        braceDepth--
                        while (stack.isNotEmpty() && stack.last().depth > braceDepth) stack.removeAt(stack.lastIndex)
                    }
                    "(", "[" -> parenDepth++
                    ")", "]" -> parenDepth--
                    ";" -> if (pending?.parenDepth == parenDepth) pending = null
                    "@" -> {
                        val next = tokens.getOrNull(i + 1)
                        if (next != null && next.type == TokenType.IDENT && next.text != "interface") {
                            val (parts, afterName) = qualifiedName(tokens, i + 1)
                            // `@file:Foo` / `@get:Foo` use-site targets
                            if (tokens.getOrNull(afterName)?.text == ":") { i = afterName + 1; continue }
                            classStackAt[annotationSites.size] = stack.map { it.name }
                            if (tokens.getOrNull(afterName)?.text == "(") {
                                // Arguments are parsed lazily (only for target annotations) to stay
                                // tolerant of arbitrary expressions in unrelated annotations.
                                val close = matching(tokens, afterName)
                                annotationSites += AnnotationCall(parts, emptyList(), t.line) to afterName
                                i = close + 1
                            } else {
                                annotationSites += AnnotationCall(parts, emptyList(), t.line) to -afterName - 1
                                i = afterName
                            }
                            continue
                        }
                    }
                }
                i++
                continue
            }

            if (t.type == TokenType.IDENT && braceDepth == 0 && parenDepth == 0) {
                if (t.text == "package" && !hasPackage && classes.isEmpty()) {
                    val (parts, after) = qualifiedName(tokens, i + 1)
                    packageName = parts.joinToString(".")
                    hasPackage = true
                    var end = after
                    if (tokens.getOrNull(end)?.text == ";") end++
                    insertionOffset = tokens[end - 1].end
                    i = end
                    continue
                }
                if (t.text == "import") {
                    var j = i + 1
                    if (tokens.getOrNull(j)?.text == "static") j++
                    val (parts, after) = qualifiedName(tokens, j)
                    var end = after
                    if (tokens.getOrNull(end)?.text == "." && tokens.getOrNull(end + 1)?.text == "*") {
                        wildcards += parts.joinToString(".")
                        end += 2
                    } else if (tokens.getOrNull(end)?.text == "as" && tokens.getOrNull(end + 1)?.type == TokenType.IDENT) {
                        imports[tokens[end + 1].text] = parts.joinToString(".")
                        end += 2
                    } else {
                        imports[parts.last()] = parts.joinToString(".")
                    }
                    if (tokens.getOrNull(end)?.text == ";") end++
                    insertionOffset = tokens[end - 1].end
                    i = end
                    continue
                }
            }

            if (t.type == TokenType.IDENT && t.text in setOf("fun", "val", "var") && pending?.parenDepth == parenDepth) {
                // A body-less Kotlin class followed by a function/property: don't attach the class to that body.
                pending = null
            }

            if (t.type == TokenType.IDENT && t.text in declarationKeywords && isDeclarationKeyword(tokens, i, isJava)) {
                val next = tokens.getOrNull(i + 1)
                val name = when {
                    next?.type == TokenType.IDENT -> next.text
                    t.text == "object" && tokens.getOrNull(i - 1)?.text == "companion" -> "Companion"
                    else -> null
                }
                if (name != null) {
                    val enclosing = stack.map { it.name }
                    if (enclosing.none { it == null }) {
                        val prefix = if (packageName.isEmpty()) "" else "$packageName."
                        classes += ClassDeclaration(
                            binaryName = prefix + (enclosing.filterNotNull() + name).joinToString("$"),
                            simpleName = name,
                            isNested = enclosing.isNotEmpty(),
                            classKeywordOffset = t.start,
                        )
                    }
                }
                pending = Pending(name, parenDepth)
                i += if (name != null && next?.text == name) 2 else 1
                continue
            }
            i++
        }

        val sites = annotationSites.toList()
        val stacks = classStackAt.toMap()
        lateinit var parsed: ParsedSource
        val cache = HashMap<String, List<AnnotatedClass>>()
        parsed = ParsedSource(
            path = path,
            text = text,
            isJava = isJava,
            packageName = packageName,
            imports = imports,
            wildcardImports = wildcards,
            importInsertionOffset = insertionOffset,
            hasPackage = hasPackage,
            classes = classes,
            annotationsByTarget = { target ->
                cache.getOrPut(target) {
                    sites.mapIndexedNotNull { index, (call, marker) ->
                        if (!parsed.refersTo(call.nameParts, target)) return@mapIndexedNotNull null
                        val hasArgs = marker >= 0
                        val afterName = if (hasArgs) marker else -(marker + 1)
                        val (args, afterArgs) =
                            if (hasArgs) parseArguments(tokens, afterName) else emptyList<Argument>() to afterName
                        val classIndex = findAnnotatedDeclaration(tokens, afterArgs, call.line)
                        val name = tokens[classIndex + 1].text
                        val enclosing = stacks[index].orEmpty()
                        if (enclosing.any { it == null }) {
                            throw SourceSyntaxException("@${call.nameParts.joinToString(".")} is not supported inside anonymous objects", call.line)
                        }
                        val prefix = if (packageName.isEmpty()) "" else "$packageName."
                        AnnotatedClass(
                            binaryName = prefix + (enclosing.filterNotNull() + name).joinToString("$"),
                            simpleName = name,
                            isNested = enclosing.isNotEmpty(),
                            annotation = call.copy(arguments = args),
                            classKeywordOffset = tokens[classIndex].start,
                        )
                    }
                }
            },
        )
        return parsed
    }

    private fun isDeclarationKeyword(tokens: List<Token>, i: Int, isJava: Boolean): Boolean {
        val prev = tokens.getOrNull(i - 1)
        if (prev != null && (prev.text == "." || prev.text == "::")) return false
        val t = tokens[i]
        if (t.text == "enum") {
            // Kotlin `enum class X` is handled by `class`; Java `enum X {`.
            return isJava && tokens.getOrNull(i + 1)?.type == TokenType.IDENT
        }
        if (t.text == "object" && isJava) return false
        return true
    }

    /** From the token after an annotation, skip other annotations/modifiers and return the index of `class`. */
    private fun findAnnotatedDeclaration(tokens: List<Token>, from: Int, line: Int): Int {
        var i = from
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t.text == "@" -> {
                    val (_, after) = qualifiedName(tokens, i + 1)
                    i = if (tokens.getOrNull(after)?.text == "(") matching(tokens, after) + 1 else after
                }
                t.type == TokenType.IDENT && t.text == "class" -> {
                    val name = tokens.getOrNull(i + 1)
                    if (name?.type != TokenType.IDENT) throw SourceSyntaxException("Expected class name", t.line)
                    return i
                }
                t.type == TokenType.IDENT && t.text in modifiers -> i++
                else -> throw SourceSyntaxException(
                    "@Manifest annotations must be placed on a class declaration (found '${t.text}')", t.line,
                )
            }
        }
        throw SourceSyntaxException("@Manifest annotation is not followed by a class declaration", line)
    }

    private fun qualifiedName(tokens: List<Token>, from: Int): Pair<List<String>, Int> {
        val first = tokens.getOrNull(from)
        if (first == null || first.type != TokenType.IDENT) {
            throw SourceSyntaxException("Expected identifier", first?.line ?: tokens.lastOrNull()?.line ?: 1)
        }
        val parts = arrayListOf(first.text)
        var i = from + 1
        while (tokens.getOrNull(i)?.text == "." && tokens.getOrNull(i + 1)?.type == TokenType.IDENT) {
            parts += tokens[i + 1].text
            i += 2
        }
        return parts to i
    }

    /** Index of the bracket closing the one at [open]. */
    private fun matching(tokens: List<Token>, open: Int): Int {
        val openText = tokens[open].text
        val closeText = when (openText) { "(" -> ")"; "[" -> "]"; "{" -> "}"; else -> error(openText) }
        var depth = 0
        for (i in open until tokens.size) {
            val t = tokens[i]
            if (t.type != TokenType.SYMBOL) continue
            if (t.text == openText) depth++
            if (t.text == closeText) { depth--; if (depth == 0) return i }
        }
        throw SourceSyntaxException("Unbalanced '$openText'", tokens[open].line)
    }

    // ---- expression parsing (annotation arguments only) ----

    private fun parseArguments(tokens: List<Token>, open: Int): Pair<List<Argument>, Int> {
        check(tokens[open].text == "(")
        val args = ArrayList<Argument>()
        var i = open + 1
        if (tokens.getOrNull(i)?.text == ")") return args to i + 1
        while (true) {
            val t = tokens.getOrNull(i) ?: throw SourceSyntaxException("Unterminated argument list", tokens[open].line)
            var name: String? = null
            if (t.type == TokenType.IDENT && tokens.getOrNull(i + 1)?.text == "=") {
                name = t.text
                i += 2
            }
            val (value, next) = parseExpression(tokens, i)
            args += Argument(name, value, t.line)
            i = next
            when (tokens.getOrNull(i)?.text) {
                "," -> {
                    i++
                    if (tokens.getOrNull(i)?.text == ")") return args to i + 1
                }
                ")" -> return args to i + 1
                else -> throw SourceSyntaxException(
                    "Unexpected '${tokens.getOrNull(i)?.text}' in annotation arguments", tokens.getOrNull(i)?.line ?: t.line,
                )
            }
        }
    }

    private fun parseExpression(tokens: List<Token>, from: Int): Pair<AnnotationValue, Int> {
        var (value, i) = parsePrimary(tokens, from)
        while (tokens.getOrNull(i)?.text == "+") {
            val (rhs, next) = parsePrimary(tokens, i + 1)
            if (value !is StringValue || rhs !is StringValue) {
                throw SourceSyntaxException("Only string literals can be concatenated in @Manifest arguments", value.line)
            }
            value = StringValue(value.value + rhs.value, value.line)
            i = next
        }
        return value to i
    }

    private fun parsePrimary(tokens: List<Token>, from: Int): Pair<AnnotationValue, Int> {
        val t = tokens.getOrNull(from) ?: throw SourceSyntaxException("Expected a value", tokens.lastOrNull()?.line ?: 1)
        when (t.type) {
            TokenType.STRING -> {
                if (t.isTemplate) throw SourceSyntaxException("String templates are not supported in @Manifest arguments", t.line)
                return StringValue(t.text, t.line) to from + 1
            }
            TokenType.NUMBER -> return NumberValue(t.text, t.line) to from + 1
            TokenType.CHAR -> throw SourceSyntaxException("Char literals are not supported in @Manifest arguments", t.line)
            TokenType.SYMBOL -> when (t.text) {
                "-" -> {
                    val n = tokens.getOrNull(from + 1)
                    if (n?.type != TokenType.NUMBER) throw SourceSyntaxException("Expected number after '-'", t.line)
                    return NumberValue("-" + n.text, t.line) to from + 2
                }
                "[" -> return parseList(tokens, from, "]", t.line)
                "{" -> return parseList(tokens, from, "}", t.line)
                "@" -> {
                    val (parts, after) = qualifiedName(tokens, from + 1)
                    return parseCall(tokens, parts, after, t.line)
                }
                "(" -> {
                    val (inner, after) = parseExpression(tokens, from + 1)
                    if (tokens.getOrNull(after)?.text != ")") throw SourceSyntaxException("Expected ')'", t.line)
                    return inner to after + 1
                }
                else -> throw SourceSyntaxException("Unsupported expression '${t.text}' in @Manifest arguments", t.line)
            }
            TokenType.IDENT -> {
                if (t.text == "true" || t.text == "false") return BooleanValue(t.text == "true", t.line) to from + 1
                val (parts, after) = qualifiedName(tokens, from)
                if (parts.size == 1 && parts[0] in arrayFactories && tokens.getOrNull(after)?.text == "(") {
                    return parseList(tokens, after, ")", t.line)
                }
                if (tokens.getOrNull(after)?.text == "::" && tokens.getOrNull(after + 1)?.text == "class") {
                    return ClassRefValue(parts, t.line) to after + 2
                }
                if (parts.size > 1 && parts.last() == "class") {
                    return ClassRefValue(parts.dropLast(1), t.line) to after
                }
                if (tokens.getOrNull(after)?.text == "(") return parseCall(tokens, parts, after, t.line)
                return ReferenceValue(parts, t.line) to after
            }
        }
    }

    private val arrayFactories = setOf("arrayOf", "emptyArray", "intArrayOf", "floatArrayOf", "booleanArrayOf")

    private fun parseCall(tokens: List<Token>, parts: List<String>, after: Int, line: Int): Pair<AnnotationValue, Int> {
        return if (tokens.getOrNull(after)?.text == "(") {
            val (args, next) = parseArguments(tokens, after)
            AnnotationCall(parts, args, line) to next
        } else {
            AnnotationCall(parts, emptyList(), line) to after
        }
    }

    private fun parseList(tokens: List<Token>, open: Int, close: String, line: Int): Pair<AnnotationValue, Int> {
        val items = ArrayList<AnnotationValue>()
        var i = open + 1
        if (tokens.getOrNull(i)?.text == close) return ArrayValue(items, line) to i + 1
        while (true) {
            val (v, next) = parseExpression(tokens, i)
            items += v
            i = next
            when (tokens.getOrNull(i)?.text) {
                "," -> {
                    i++
                    if (tokens.getOrNull(i)?.text == close) return ArrayValue(items, line) to i + 1
                }
                close -> return ArrayValue(items, line) to i + 1
                else -> throw SourceSyntaxException("Expected ',' or '$close' in array", tokens.getOrNull(i)?.line ?: line)
            }
        }
    }
}
