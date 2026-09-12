package com.interstellar.proxy.data.config

/**
 * Minimal YAML emitter for machine-generated config files (mihomo Clash YAML).
 * Supports maps / lists / scalars only — enough for a config we fully control,
 * with quoting aggressive enough for node names containing emoji, CJK and
 * punctuation.
 */
object Yaml {
    /** A YAML mapping under construction — a LinkedHashMap so the writer's `is Map` branch handles it. */
    class Node : LinkedHashMap<String, Any?>()

    fun map(block: Node.() -> Unit): Node = Node().apply(block)

    fun write(root: Map<String, Any?>): String = buildString {
        writeMap(this, root, 0)
    }

    private fun writeMap(out: StringBuilder, value: Map<String, Any?>, indent: Int) {
        for ((k, v) in value) {
            if (v == null) continue
            out.append("  ".repeat(indent)).append(scalar(k)).append(":")
            when (v) {
                is Map<*, *> -> {
                    if (v.isEmpty()) {
                        out.append(" {}\n")
                    } else {
                        out.append("\n")
                        @Suppress("UNCHECKED_CAST")
                        writeMap(out, v as Map<String, Any?>, indent + 1)
                    }
                }

                is List<*> -> {
                    if (v.isEmpty()) {
                        out.append(" []\n")
                    } else {
                        out.append("\n")
                        writeList(out, v, indent + 1)
                    }
                }

                else -> out.append(" ").append(scalar(v)).append("\n")
            }
        }
    }

    private fun writeList(out: StringBuilder, value: List<*>, indent: Int) {
        for (item in value) {
            out.append("  ".repeat(indent)).append("-")
            when (item) {
                null -> out.append(" null\n")
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val m = item as Map<String, Any?>
                    if (m.isEmpty()) {
                        out.append(" {}\n")
                    } else {
                        val sb = StringBuilder()
                        writeMap(sb, m, 0)
                        // first entry rides the dash line, rest re-indent
                        val lines = sb.trimEnd('\n').split('\n')
                        lines.forEachIndexed { i, line ->
                            out.append(if (i == 0) " " else "\n" + "  ".repeat(indent) + "  ")
                            out.append(line)
                        }
                        out.append("\n")
                    }
                }

                is List<*> -> {
                    out.append("\n")
                    writeList(out, item, indent + 1)
                }

                else -> out.append(" ").append(scalar(item)).append("\n")
            }
        }
    }

    private fun scalar(v: Any): String = when (v) {
        is Boolean, is Int, is Long -> v.toString()
        is String -> quote(v)
        else -> quote(v.toString())
    }

    private fun needsQuotes(s: String): Boolean =
        s.isEmpty() ||
            s != s.trim() ||
            s.any { it in ":#{}[],&*?|<>=!%@`\\\n\t\"" } ||
            s.startsWith("-") || s.startsWith("?") || s.startsWith("&") ||
            s.first().isDigit() ||
            s in setOf("true", "false", "null", "yes", "no", "on", "off", "~") ||
            s.startsWith("0x") ||
            s.toDoubleOrNull() != null ||
            s.endsWith(":")

    private fun quote(s: String): String =
        if (needsQuotes(s)) "'" + s.replace("'", "''") + "'" else s
}
