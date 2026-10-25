package tablekeeper.web

import tablekeeper.core.CampaignGraph
import tablekeeper.core.EdgeRef
import tablekeeper.core.Node
import tablekeeper.modules.GameModules
import tablekeeper.print.PacketSelection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class DebriefTest {
    private val heart = GameModules.load(System.getProperty("modulesDir") ?: "modules").get("heart")
    private val campaign = UUID.randomUUID()
    private fun node(type: String, title: String) = Node(UUID.randomUUID(), campaign, type, title, title, "", emptyMap(), "gm")

    private val market = node("heart.landmark", "Red Market")
    private val alley = node("heart.minor_location", "Butcher's Row")
    private val elsewhere = node("heart.landmark", "Far Away")
    private val ash = node("heart.pc", "Ash")
    private val idle = node("heart.pc", "Idle")
    private val silvia = node("core.npc", "Silvia")
    private val lamp = node("heart.resource", "Lamp")
    private val saw = node("heart.equipment", "Bone Saw")
    private val coin = node("heart.resource", "Coin")
    private val rope = node("heart.equipment", "Rope")
    private val graph = CampaignGraph(
        listOf(market, alley, elsewhere, ash, idle, silvia, lamp, saw, coin, rope),
        listOf(
            EdgeRef(alley.id, market.id, "part_of"),
            EdgeRef(idle.id, market.id, "located_at"),     // not playing this session, though at the landmark
            EdgeRef(lamp.id, alley.id, "located_at"),      // inside a selected landmark
            EdgeRef(silvia.id, saw.id, "carries"),         // carried by an NPC in the packet
            EdgeRef(ash.id, coin.id, "carries"),           // carried by a selected character
            EdgeRef(rope.id, elsewhere.id, "located_at"),  // somewhere else
        ),
    )
    // The session features Red Market and Ash; Silvia is around (located at Red Market)
    private val selection = PacketSelection(listOf(market, ash), emptyList(), listOf(silvia), emptyList())

    @Test
    fun `characters are the selected ones only`() {
        val pcs = heart.debrief("core.session")[0]
        assertEquals(listOf(ash), Debrief.members(heart, pcs, selection, graph))
    }

    @Test
    fun `items at, inside or carried by something in the packet are listed`() {
        val items = heart.debrief("core.session")[2]
        assertEquals(setOf("Lamp", "Bone Saw", "Coin"), Debrief.members(heart, items, selection, graph).map { it.title }.toSet())
    }
}
