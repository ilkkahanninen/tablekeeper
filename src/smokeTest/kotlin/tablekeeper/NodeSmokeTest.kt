package tablekeeper

import io.ktor.client.statement.bodyAsText
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeSmokeTest {
    @Test
    fun `autosave stores valid fields, refuses invalid ones and keeps what isn't posted`() = smoke {
        val cid = campaign("heart", "Saves")
        val l = node(cid, "heart.landmark", "Derelict").id
        assertEquals("", errors(save(cid, l, "prop.tier" to "Tier 2", "prop.domains" to "Occult", "prop.domains" to "Wild", "body" to "Notes")))
        assertEquals(mapOf("tier" to "Tier 2", "domains" to listOf("Occult", "Wild")), node(cid, l).props.filterKeys { it in setOf("tier", "domains") })
        assertEquals("Derelict", node(cid, l).title)   // the main form posts no title: the stored one stays

        assertTrue("tier" in errors(save(cid, l, "prop.tier" to "Tier 99", "body" to "Changed")))
        assertEquals("Tier 2" to "Notes", node(cid, l).let { it.props["tier"] to it.body })

        // The title form (only=) changes just the title; a form without notes keeps them
        save(cid, l, "title" to "Red Market", "only" to "")
        assertEquals(Triple("Red Market", "Notes", "Tier 2"), node(cid, l).let { Triple(it.title, it.body, it.props["tier"]) })
    }

    @Test
    fun `links from Add new, slots, checklists and the Link box, and removing them`() = smoke {
        val cid = campaign("heart", "Links")
        val market = node(cid, "heart.landmark", "Derelict").id
        val silvia = quickCreate(cid, market, "core.npc|located_at:in", "Silvia")
        assertEquals(listOf("located_at:out Derelict"), links(cid, silvia))

        // A slot holds one link: choosing another place replaces it; empty clears it
        val other = create(cid, "heart.landmark", "Red Market")
        post("/c/$cid/n/$silvia/slot", "slot" to "0", "value" to "located_at:out|$other")
        assertEquals(listOf("located_at:out Red Market"), links(cid, silvia))
        post("/c/$cid/n/$silvia/slot", "slot" to "0", "value" to "")
        assertEquals(emptyList(), links(cid, silvia))

        val session = node(cid, "core.session", "First Session").id
        post("/c/$cid/n/$session/check", "rel" to "features:out", "other" to "$other", "checked" to "on")
        assertTrue("features:out Red Market" in links(cid, session))
        post("/c/$cid/n/$session/check", "rel" to "features:out", "other" to "$other")
        assertTrue("features:out Red Market" !in links(cid, session))

        val faction = create(cid, "core.faction", "The Hounds")
        assertTrue("The Hounds" in ok("/c/$cid/n/$silvia/link-search?q=hou").bodyAsText())
        post("/c/$cid/n/$silvia/links", "rel" to "member_of:out", "other" to "$faction")
        assertEquals(listOf("member_of:out The Hounds"), links(cid, silvia))
        post("/c/$cid/n/$silvia/unlink", "rel" to "member_of:out", "other" to "$faction")
        assertEquals(emptyList(), links(cid, silvia))

        post("/c/$cid/n/$silvia/links", "rel" to "member_of:out", "other" to "$faction")
        val edge = deps.graph.links(cid, silvia).single().edgeId
        post("/c/$cid/n/$silvia/links/$edge/delete")
        assertEquals(emptyList(), links(cid, silvia))

        // Can't link a node to a type the rule doesn't take
        assertEquals(400, post("/c/$cid/n/$silvia/links", "rel" to "member_of:out", "other" to "$market").status.value)
    }

    @Test
    fun `mentions link notes to nodes and follow a rename`() = smoke {
        val cid = campaign("heart", "Mentions")
        val market = node(cid, "heart.landmark", "Derelict").id
        val silvia = quickCreate(cid, market, "core.npc|located_at:in", "Silvia")
        save(cid, market, "body" to "Ask [[silvia]] about the lift.")
        assertTrue("Silvia" in ok("/c/$cid/n/$market/mentions?q=sil").bodyAsText())
        assertTrue("Derelict" in ok("/c/$cid/n/$silvia").bodyAsText().substringAfter("Mentioned in"))

        save(cid, silvia, "title" to "Silvia Rust", "only" to "")
        assertEquals("Ask [[Silvia Rust]] about the lift.", node(cid, market).body)
    }

    @Test
    fun `duplicate copies fields but not links, and delete takes the node's links with it`() = smoke {
        val cid = campaign("heart", "Copies")
        val market = node(cid, "heart.landmark", "Derelict")
        val copy = node(cid, nodeId(post("/c/$cid/n/${market.id}/duplicate").target()))
        assertEquals(Triple("Derelict (copy)", "L2", market.props), Triple(copy.title, copy.code, copy.props))
        assertEquals(emptyList(), links(cid, copy.id))

        val session = node(cid, "core.session", "First Session").id
        assertEquals("/c/$cid", post("/c/$cid/n/${market.id}/delete").target())
        assertEquals(listOf("First Session", "Derelict (copy)").sorted(), nodes(cid).map { it.title }.sorted())
        assertEquals(emptyList(), links(cid, session))
    }

    @Test
    fun `nodes made at the same time get different codes`() = smoke {
        val cid = campaign("heart", "Codes")
        val pool = Executors.newFixedThreadPool(8)
        try {
            (1..24).map { pool.submit<UUID> { deps.graph.createNode(cid, "core.npc", "N", "NPC $it").id } }.forEach { it.get() }
        } finally { pool.shutdown() }
        assertEquals((1..24).map { "N$it" }.toSet(), nodes(cid).filter { it.type == "core.npc" }.map { it.code }.toSet())
    }

    /** The error list in a save status; empty when it saved. */
    private fun errors(status: String) = Regex("<li>(.*?)</li>").findAll(status).joinToString { it.groupValues[1] }
}
