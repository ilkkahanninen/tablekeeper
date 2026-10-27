package tablekeeper

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CampaignSmokeTest {
    @Test
    fun `a new Heart campaign starts with its place and session, and every page and print renders`() = smoke {
        val cid = campaign("heart", "Heart pages")
        val landmark = node(cid, "heart.landmark", "Derelict")
        val session = node(cid, "core.session", "First Session")
        assertEquals(listOf("features:in First Session"), links(cid, landmark.id))
        assertTrue("Heart pages" in ok("/").bodyAsText())

        listOf("", "/map", "/tables", "/v/inspiration", "/find?q=Dere").forEach { ok("/c/$cid$it") }
        assertTrue("Derelict" in ok("/c/$cid/find?q=dere").bodyAsText())
        pdf("/c/$cid/map.pdf")
        listOf(landmark, session).forEach { n ->
            ok("/c/$cid/n/${n.id}")
            ok("/c/$cid/n/${n.id}/print")
            pdf("/c/$cid/n/${n.id}/print.pdf")
        }
        ok("/c/$cid/n/${session.id}/debrief")
    }

    @Test
    fun `a campaign is renamed from its title and deleted with everything in it`() = smoke {
        val cid = campaign("heart", "Before")
        post("/c/$cid", "name" to "After")
        post("/c/$cid", "name" to " ")   // empty keeps the name
        assertEquals("After", deps.campaigns.get(deps.campaigns.ensureUser("alice"), cid)?.name)

        assertEquals("/", post("/c/$cid/delete").target())
        assertNull(deps.campaigns.get(deps.campaigns.ensureUser("alice"), cid))
        assertEquals(emptyList(), nodes(cid))
        assertEquals(HttpStatusCode.NotFound, get("/c/$cid").status)
    }

    @Test
    fun `a MÖRK BORG campaign gets the game's tables, and a generated dungeon has rooms, a map and a print`() = smoke {
        val cid = campaign("morkborg", "Dungeons")
        assertTrue(deps.tables.list(cid).any { it.name == "Bestiary" })

        val dungeon = nodeId(post("/c/$cid/generate/dungeon").target())
        val rooms = nodes(cid).filter { it.type == "mb.room" }
        assertEquals(10, rooms.size)
        assertTrue(rooms.all { r -> links(cid, r.id).any { it == "room_in:out ${node(cid, dungeon).title}" } })
        ok("/c/$cid/n/$dungeon/map")
        pdf("/c/$cid/n/$dungeon/map.pdf")
        pdf("/c/$cid/n/$dungeon/print.pdf")

        val creature = node(cid, nodeId(post("/c/$cid/generate/creature").target()))
        assertTrue(creature.props["hp"] != null, "A generated creature has its stat block: ${creature.props}")

        // The session packet with the dungeon ticked: one landscape PDF of everything in it
        val session = node(cid, "core.session", "First Session")
        post("/c/$cid/n/${session.id}/check", "rel" to "features:out", "other" to dungeon.toString(), "checked" to "on")
        pdf("/c/$cid/n/${session.id}/print.pdf")
    }

    @Test
    fun `random tables fill fields and name new nodes`() = smoke {
        val cid = campaign("heart", "Tables")
        val tid = post("/c/$cid/tables", "name" to "Names").target().substringAfter("/tables/")
        post("/c/$cid/tables/$tid", "name" to "Names", "items" to "Silvia\nOrlo", "fields" to "core.npc:title", "autosave" to "1")
        assertTrue(ok("/c/$cid/tables/$tid/roll").bodyAsText() in setOf("Silvia", "Orlo"))
        assertEquals("Orlo", ok("/c/$cid/tables/$tid/roll?avoid=Silvia").bodyAsText())

        val npc = create(cid, "core.npc")   // no title: rolled
        assertTrue(node(cid, npc).title in setOf("Silvia", "Orlo"))

        post("/c/$cid/tables/$tid/delete")
        assertEquals(emptyList(), deps.tables.list(cid))
        assertEquals("New NPC", node(cid, create(cid, "core.npc")).title)
    }
}
