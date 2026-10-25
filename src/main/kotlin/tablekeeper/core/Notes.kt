package tablekeeper.core

import java.util.UUID

/** A node a `[[mention]]` can point at: found by [code], [title] or [display] title ("Thonal (Jori)"), shown as [display]. */
data class MentionTarget(val id: UUID, val code: String, val title: String, val display: String)

/** The campaign's nodes by the names a `[[mention]]` may use, ignoring case: code first, then title, then display title. */
class Mentions(targets: List<MentionTarget>) {
    private val byCode = targets.associateBy { it.code.lowercase() }
    private val byTitle = targets.reversed().associateBy { it.title.lowercase() }   // reversed: the first one wins
    private val byDisplay = targets.reversed().associateBy { it.display.lowercase() }

    fun find(name: String): MentionTarget? = name.trim().lowercase().let { byCode[it] ?: byTitle[it] ?: byDisplay[it] }

    companion object {
        val NONE = Mentions(emptyList())

        fun of(nodes: List<Node>, display: (Node) -> String) = Mentions(nodes.map { MentionTarget(it.id, it.code, it.title, display(it)) })
    }
}

/**
 * Notes as safe HTML: `[[Name]]` mentions, `**bold**`, `*italic*`, and lines starting with "- " or "* " as bullet lists.
 * Blank lines separate paragraphs; single line breaks are kept. Everything else is escaped text.
 * A known mention becomes a link via [href] (screen), or bold text when [href] is null (print); an unknown one plain text.
 */
object Notes {
    val MENTION = Regex("""\[\[([^\[\]\n]+)]]""")
    private val BULLET = Regex("""^\s*[-*]\s+(.*)$""")
    private val BOLD = Regex("""\*\*(?=\S)(.+?)(?<=\S)\*\*""")
    private val ITALIC = Regex("""(?<![*\w])\*(?=[^\s*])(.+?)(?<=[^\s*])\*(?![*\w])""")

    fun html(text: String, mentions: Mentions, href: ((MentionTarget) -> String)? = null): String = buildString {
        var paragraph = mutableListOf<String>()
        var list = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) append("<p>").append(paragraph.joinToString("<br>")).append("</p>")
            if (list.isNotEmpty()) append("<ul>").append(list.joinToString("") { "<li>$it</li>" }).append("</ul>")
            paragraph = mutableListOf(); list = mutableListOf()
        }
        for (line in text.replace("\r\n", "\n").split("\n")) {
            val bullet = BULLET.find(line)
            when {
                line.isBlank() -> flush()
                bullet != null -> { if (paragraph.isNotEmpty()) flush(); list += inline(bullet.groupValues[1], mentions, href) }
                else -> { if (list.isNotEmpty()) flush(); paragraph += inline(line, mentions, href) }
            }
        }
        flush()
    }

    /** One line or short text (e.g. a fact's): mentions, bold and italic; line breaks as `<br>`, no paragraphs or lists. */
    fun inline(text: String, mentions: Mentions, href: ((MentionTarget) -> String)? = null): String {
        // Mentions become placeholders first, so their names aren't read as bold/italic and links can sit inside **bold**
        val links = mutableListOf<String>()
        val marked = MENTION.replace(text.trim()) { m ->
            val name = m.groupValues[1]
            links += mentions.find(name)?.let { t ->
                href?.let { "<a href=\"${escape(it(t))}\">${escape(t.display)}</a>" } ?: "<b class=\"mention\">${escape(t.display)}</b>"
            } ?: escape(name.trim())
            "\u0000${links.lastIndex}\u0000"
        }
        val formatted = ITALIC.replace(BOLD.replace(escape(marked)) { "<strong>${it.groupValues[1]}</strong>" }) { "<em>${it.groupValues[1]}</em>" }
        return Regex("\u0000(\\d+)\u0000").replace(formatted.replace("\n", "<br>")) { links[it.groupValues[1].toInt()] }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
