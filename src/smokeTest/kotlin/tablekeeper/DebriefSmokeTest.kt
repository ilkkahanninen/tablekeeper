package tablekeeper

import io.ktor.client.statement.bodyAsText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DebriefSmokeTest {
    @Test
    fun `a session's debrief saves single fields to other nodes and answers quietly`() = smoke {
        val cid = campaign("heart", "Debrief")
        val session = node(cid, "core.session", "First Session").id
        val pc = create(cid, "heart.pc", "Thonal")
        save(cid, pc, "prop.class" to "Cleaver", "prop.beat1" to "Find the Hound", "prop.beat2" to "Pay the debt")
        post("/c/$cid/n/$session/check", "rel" to "features:out", "other" to "$pc", "checked" to "on")
        assertTrue("Find the Hound" in ok("/c/$cid/n/$session/debrief").bodyAsText())

        // One beat edited: just that prop changes, the others stay
        save(cid, pc, "only" to "beat1", "prop.beat1" to "Found the Hound")
        assertEquals(listOf("Cleaver", "Found the Hound", "Pay the debt"), node(cid, pc).props.let { listOf(it["class"], it["beat1"], it["beat2"]) })

        // Done: the beat's fields emptied, and the debrief told to reload
        val done = post("/c/$cid/n/$pc", "autosave" to "1", "only" to "beat1Type", "only" to "beat1", "clear" to "1", "refresh" to "1")
        assertEquals("debrief-changed", done.headers["HX-Trigger"])
        assertEquals(listOf("Cleaver", null, "Pay the debt"), node(cid, pc).props.let { listOf(it["class"], it["beat1"], it["beat2"]) })

        // A fallout added and removed from the debrief: no page parts in the answer
        val added = post("/c/$cid/n/$pc/quick-create", "choice" to "heart.fallout|suffers:out", "title" to "Hollow cough", "quiet" to "1", "refresh" to "1")
        assertEquals("" to "debrief-changed", added.bodyAsText() to added.headers["HX-Trigger"])
        val fallout = node(cid, "heart.fallout", "Hollow cough").id
        assertTrue("suffers:out Hollow cough" in links(cid, pc))
        assertEquals("", post("/c/$cid/n/$pc/unlink", "rel" to "suffers:out", "other" to "$fallout", "quiet" to "1").bodyAsText())
        assertEquals(listOf("features:in First Session"), links(cid, pc))
        assertEquals("", post("/c/$cid/n/$fallout/delete", "quiet" to "1").bodyAsText())
        assertTrue(nodes(cid).none { it.id == fallout })
    }
}
