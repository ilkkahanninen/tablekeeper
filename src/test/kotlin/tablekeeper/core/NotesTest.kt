package tablekeeper.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class NotesTest {
    private val market = MentionTarget(UUID.randomUUID(), "L3", "Red Market", "Red Market")
    private val thonal = MentionTarget(UUID.randomUUID(), "PC1", "Thonal", "Thonal (Jori)")
    private val mentions = Mentions(listOf(market, thonal))
    private fun screen(text: String) = Notes.html(text, mentions) { "/n/${it.code}" }

    @Test
    fun `paragraphs, line breaks and lists`() {
        assertEquals("<p>One<br>two</p><p>Three</p>", screen("One\ntwo\n\nThree"))
        assertEquals("<p>Loot:</p><ul><li>a lamp</li><li>coins</li></ul><p>After</p>", screen("Loot:\n- a lamp\n* coins\nAfter"))
    }

    @Test
    fun `bold and italic, but not lone stars`() {
        assertEquals("<p><strong>Run</strong> or <em>hide</em></p>", screen("**Run** or *hide*"))
        assertEquals("<p>2 * 3 * 4</p>", screen("2 * 3 * 4"))
    }

    @Test
    fun `mentions by title, code or display title, any case`() {
        assertEquals("<p>Meet at <a href=\"/n/L3\">Red Market</a></p>", screen("Meet at [[red market]]"))
        assertEquals("<p><a href=\"/n/L3\">Red Market</a></p>", screen("[[L3]]"))
        assertEquals("<p><a href=\"/n/PC1\">Thonal (Jori)</a> and <a href=\"/n/PC1\">Thonal (Jori)</a></p>", screen("[[Thonal]] and [[Thonal (Jori)]]"))
    }

    @Test
    fun `unknown mentions are plain text, prints show known ones in bold`() {
        assertEquals("<p>Ask Nobody</p>", screen("Ask [[Nobody]]"))
        assertEquals("<p><b class=\"mention\">Red Market</b></p>", Notes.html("[[Red Market]]", mentions))
    }

    @Test
    fun `a mention inside bold, and everything else escaped`() {
        assertEquals("<p><strong><a href=\"/n/L3\">Red Market</a></strong></p>", screen("**[[Red Market]]**"))
        assertEquals("<p>&lt;script&gt;x&lt;/script&gt; &amp; [[</p>", screen("<script>x</script> & [["))
    }

    @Test
    fun `inline text keeps line breaks without paragraphs`() {
        assertEquals("<em>Tense</em><br><b class=\"mention\">Red Market</b>", Notes.inline("*Tense*\n[[Red Market]]", mentions))
    }
}
