package tablekeeper.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class CampaignGraphTest {
    private val campaign = UUID.randomUUID()
    private fun node(title: String, type: String = "core.place") = Node(UUID.randomUUID(), campaign, type, title, title, "", emptyMap(), "gm")

    private val market = node("Red Market")
    private val tunnel = node("Tunnel")
    private val silvia = node("Silvia", "core.npc")
    private val graph = CampaignGraph(
        listOf(market, tunnel, silvia),
        listOf(EdgeRef(tunnel.id, market.id, "connects"), EdgeRef(silvia.id, market.id, "located_at")),
    )

    @Test
    fun `linked follows one edge type in the given direction`() {
        assertEquals(listOf(market.id), graph.linked(tunnel.id, "connects", Direction.OUT))
        assertEquals(listOf(tunnel.id), graph.linked(market.id, "connects", Direction.IN))
        assertEquals(listOf(silvia.id), graph.linked(market.id, "located_at", Direction.IN))
        assertEquals(emptyList(), graph.linked(market.id, "connects", Direction.OUT))
    }

    @Test
    fun `a filtered graph keeps its edges but skips removed nodes`() {
        val places = graph.filter { it.type == "core.place" }
        assertEquals(listOf(market, tunnel), places.nodes)
        assertEquals(listOf(tunnel), places.linkedNodes(market.id, "connects", Direction.IN))
        assertEquals(emptyList(), places.linkedNodes(market.id, "located_at", Direction.IN))
    }
}
