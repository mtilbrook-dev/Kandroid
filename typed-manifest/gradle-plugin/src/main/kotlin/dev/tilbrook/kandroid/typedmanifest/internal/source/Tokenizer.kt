package dev.tilbrook.kandroid.typedmanifest.internal.source

/**
 * A deliberately small lexer for Kotlin and Java sources. It understands enough to find
 * annotations, class declarations, imports and literal values while skipping comments,
 * strings and character literals correctly.
 */
internal enum class TokenType { IDENT, STRING, CHAR, NUMBER, SYMBOL }

internal data class Token(
    val type: TokenType,
    /** Identifier text, symbol text, number text, or the *decoded* string value. */
    val text: String,
    val start: Int,
    val end: Int,
    val line: Int,
    /** True for Kotlin strings that contain `$` templates; they are not compile-time literals we can read. */
    val isTemplate: Boolean = false,
)

internal class SourceSyntaxException(message: String, val line: Int) : RuntimeException(message)

internal object Tokenizer {

    private val multiCharSymbols = listOf("::", "->", "?.", "?:", "==", "!=", "<=", ">=", "&&", "||", "..")

    fun tokenize(source: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        var line = 1
        val n = source.length

        fun countLines(from: Int, to: Int) {
            for (k in from until to) if (source[k] == '\n') line++
        }

        while (i < n) {
            val c = source[i]
            when {
                c == '\n' -> { line++; i++ }
                c.isWhitespace() -> i++
                c == '/' && i + 1 < n && source[i + 1] == '/' -> {
                    while (i < n && source[i] != '\n') i++
                }
                c == '/' && i + 1 < n && source[i + 1] == '*' -> {
                    // Kotlin block comments nest; Java's don't, but nested openers are rare in Java.
                    val startLine = line
                    var depth = 1
                    var j = i + 2
                    while (j < n && depth > 0) {
                        if (source.startsWith("/*", j)) { depth++; j += 2 }
                        else if (source.startsWith("*/", j)) { depth--; j += 2 }
                        else j++
                    }
                    if (depth > 0) throw SourceSyntaxException("Unterminated block comment", startLine)
                    countLines(i, j)
                    i = j
                }
                c == '"' && source.startsWith("\"\"\"", i) -> {
                    val startLine = line
                    val close = source.indexOf("\"\"\"", i + 3)
                    if (close < 0) throw SourceSyntaxException("Unterminated raw string", startLine)
                    var end = close + 3
                    while (end < n && source[end] == '"') end++ // """ab""""  -> trailing quotes belong to content
                    val raw = source.substring(i + 3, end - 3)
                    tokens += Token(TokenType.STRING, raw, i, end, startLine, isTemplate = hasTemplate(raw))
                    countLines(i, end)
                    i = end
                }
                c == '"' -> {
                    val startLine = line
                    val sb = StringBuilder()
                    var j = i + 1
                    var template = false
                    while (true) {
                        if (j >= n || source[j] == '\n') throw SourceSyntaxException("Unterminated string literal", startLine)
                        val ch = source[j]
                        if (ch == '"') { j++; break }
                        if (ch == '\\') {
                            if (j + 1 >= n) throw SourceSyntaxException("Unterminated string literal", startLine)
                            val (decoded, consumed) = decodeEscape(source, j, startLine)
                            sb.append(decoded)
                            j += consumed
                            continue
                        }
                        if (ch == '$' && j + 1 < n && (source[j + 1] == '{' || source[j + 1].isJavaIdentifierStart())) {
                            template = true
                        }
                        sb.append(ch)
                        j++
                    }
                    tokens += Token(TokenType.STRING, sb.toString(), i, j, startLine, isTemplate = template)
                    i = j
                }
                c == '\'' -> {
                    val startLine = line
                    var j = i + 1
                    while (j < n && source[j] != '\'') {
                        if (source[j] == '\\') j++
                        if (j < n && source[j] == '\n') throw SourceSyntaxException("Unterminated char literal", startLine)
                        j++
                    }
                    if (j >= n) throw SourceSyntaxException("Unterminated char literal", startLine)
                    tokens += Token(TokenType.CHAR, source.substring(i + 1, j), i, j + 1, startLine)
                    i = j + 1
                }
                c == '`' -> {
                    val close = source.indexOf('`', i + 1)
                    if (close < 0) throw SourceSyntaxException("Unterminated backtick identifier", line)
                    tokens += Token(TokenType.IDENT, source.substring(i + 1, close), i, close + 1, line)
                    i = close + 1
                }
                c.isJavaIdentifierStart() -> {
                    var j = i + 1
                    while (j < n && source[j].isJavaIdentifierPart()) j++
                    tokens += Token(TokenType.IDENT, source.substring(i, j), i, j, line)
                    i = j
                }
                c.isDigit() || (c == '.' && i + 1 < n && source[i + 1].isDigit()) -> {
                    var j = i
                    if (source.startsWith("0x", j) || source.startsWith("0X", j) ||
                        source.startsWith("0b", j) || source.startsWith("0B", j)
                    ) {
                        j += 2
                        while (j < n && (source[j].isLetterOrDigit() || source[j] == '_')) j++
                    } else {
                        while (j < n && (source[j].isDigit() || source[j] == '_')) j++
                        if (j < n && source[j] == '.' && j + 1 < n && source[j + 1].isDigit()) {
                            j++
                            while (j < n && (source[j].isDigit() || source[j] == '_')) j++
                        }
                        if (j < n && (source[j] == 'e' || source[j] == 'E')) {
                            j++
                            if (j < n && (source[j] == '+' || source[j] == '-')) j++
                            while (j < n && source[j].isDigit()) j++
                        }
                        while (j < n && source[j] in "fFdDlLuU") j++
                    }
                    tokens += Token(TokenType.NUMBER, source.substring(i, j), i, j, line)
                    i = j
                }
                else -> {
                    val multi = multiCharSymbols.firstOrNull { source.startsWith(it, i) }
                    val text = multi ?: c.toString()
                    tokens += Token(TokenType.SYMBOL, text, i, i + text.length, line)
                    i += text.length
                }
            }
        }
        return tokens
    }

    private fun hasTemplate(raw: String): Boolean {
        for (k in 0 until raw.length - 1) {
            if (raw[k] == '$' && (raw[k + 1] == '{' || raw[k + 1].isJavaIdentifierStart())) return true
        }
        return false
    }

    /** Returns decoded text and the number of source chars consumed (starting at the backslash). */
    private fun decodeEscape(source: String, at: Int, line: Int): Pair<String, Int> {
        return when (val e = source[at + 1]) {
            'n' -> "\n" to 2
            't' -> "\t" to 2
            'r' -> "\r" to 2
            'b' -> "\b" to 2
            '0' -> "\u0000" to 2
            '\\' -> "\\" to 2
            '"' -> "\"" to 2
            '\'' -> "'" to 2
            '$' -> "$" to 2
            'u' -> {
                val hex = source.substring(at + 2, minOf(at + 6, source.length))
                val code = hex.toIntOrNull(16)
                    ?: throw SourceSyntaxException("Invalid unicode escape \\u$hex", line)
                code.toChar().toString() to 6
            }
            else -> throw SourceSyntaxException("Unsupported escape \\$e", line)
        }
    }
}
