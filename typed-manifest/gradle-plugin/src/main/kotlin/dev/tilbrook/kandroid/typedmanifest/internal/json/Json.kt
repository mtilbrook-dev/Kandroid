package dev.tilbrook.kandroid.typedmanifest.internal.json

/**
 * Minimal JSON support (the plugin avoids runtime dependencies). Objects are [LinkedHashMap]s so
 * key order survives a round trip; numbers are kept as [JsonNumber] with their original text.
 */
internal data class JsonNumber(val text: String) {
    fun toInt(): Int? = text.toIntOrNull() ?: text.toDoubleOrNull()?.toInt()
}

internal object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.value()
        p.skipWhitespace()
        require(p.done) { "Trailing content at ${p.pos}" }
        return value
    }

    fun write(value: Any?, indent: String = "  "): String = StringBuilder().apply { write(this, value, indent, 0) }.toString()

    private fun write(sb: StringBuilder, value: Any?, indent: String, depth: Int) {
        val pad = indent.repeat(depth + 1)
        val end = indent.repeat(depth)
        when (value) {
            null -> sb.append("null")
            is String -> string(sb, value)
            is Boolean -> sb.append(value)
            is JsonNumber -> sb.append(value.text)
            is Number -> sb.append(value)
            is Map<*, *> -> if (value.isEmpty()) sb.append("{}") else {
                sb.append("{\n")
                value.entries.forEachIndexed { i, (k, v) ->
                    sb.append(pad)
                    string(sb, k.toString())
                    sb.append(": ")
                    write(sb, v, indent, depth + 1)
                    sb.append(if (i < value.size - 1) ",\n" else "\n")
                }
                sb.append(end).append('}')
            }
            is List<*> -> if (value.isEmpty()) sb.append("[]") else {
                sb.append("[\n")
                value.forEachIndexed { i, v ->
                    sb.append(pad)
                    write(sb, v, indent, depth + 1)
                    sb.append(if (i < value.size - 1) ",\n" else "\n")
                }
                sb.append(end).append(']')
            }
            else -> error("Unsupported JSON value ${value::class}")
        }
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        s.forEach { c ->
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0
        val done get() = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWhitespace()
            require(!done) { "Unexpected end of JSON" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, pos)) { "Expected $word at $pos" }
            pos += word.length
            return value
        }

        private fun num(): JsonNumber {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            return JsonNumber(s.substring(start, pos))
        }

        private fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                require(!done) { "Unterminated string" }
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[pos++]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                            else -> sb.append(e) // " \ /
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun obj(): LinkedHashMap<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (s[pos] == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                val key = str()
                skipWhitespace()
                require(s[pos] == ':') { "Expected ':' at $pos" }
                pos++
                map[key] = value()
                skipWhitespace()
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> error("Expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        private fun arr(): ArrayList<Any?> {
            pos++
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (s[pos] == ']') { pos++; return list }
            while (true) {
                list += value()
                skipWhitespace()
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> error("Expected ',' or ']' at ${pos - 1}")
                }
            }
        }
    }
}
