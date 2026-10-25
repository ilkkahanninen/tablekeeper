package tablekeeper.modules

/**
 * Small text templates made of parts, e.g. ["{category} {quality.value}", "{tags}"] or ["{title}", "({player})"].
 * `{name}` is a prop's display text, `{name.value}` its stored value; [extra] values (e.g. "title") take precedence.
 * Empty values drop out, a part whose values are all empty is dropped whole (literal text included), and the
 * remaining parts are joined with the separator. Used for print summaries and display titles.
 */
object Parts {
    val PLACEHOLDER = Regex("""\{(\w+)(\.value)?}""")

    fun names(parts: List<String>): Set<String> = parts.flatMap { p -> PLACEHOLDER.findAll(p).map { it.groupValues[1] } }.toSet()

    fun fill(parts: List<String>, fields: Map<String, FormField>, separator: String, extra: Map<String, String> = emptyMap()): String =
        parts.mapNotNull { part ->
            val values = PLACEHOLDER.findAll(part).associate { m ->
                val name = m.groupValues[1]
                val f = fields[name]
                m.value to (extra[name] ?: (if (m.groupValues[2].isEmpty()) f?.text else f?.storedText)).orEmpty()
            }
            if (values.values.all { it.isEmpty() }) null
            else PLACEHOLDER.replace(part) { values.getValue(it.value) }.replace(Regex("\\s+"), " ").trim()
        }.joinToString(separator)
}
